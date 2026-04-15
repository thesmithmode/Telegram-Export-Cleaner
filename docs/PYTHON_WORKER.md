# Python Worker

Worker код находится в `export-worker/`.

## Основные обязанности

- Получать задачи из Redis очередей.
- Проверять доступ к чату и экспортировать сообщения через Pyrogram.
- Поддерживать дисковый кэш сообщений (SQLite).
- Передавать JSON в Java `/api/convert` и доставлять итог пользователю.

---

## Job lifecycle

1. `QueueConsumer.get_job()` делает `BLMOVE` в staging.
2. Worker помечает задачу как processing (`job:processing:*`).
3. Проверяет доступ к чату (`verify_and_get_info`).
4. Пробует cache-aware путь:
   - по диапазону дат,
   - или по ID-диапазонам.
5. При miss дозапрашивает Telegram API.
6. Периодически проверяет флаг отмены `cancel_export:{taskId}`.
7. Отправляет результат через `JavaBotClient`.
8. Ставит `job:completed:*`/`job:failed:*` и очищает staging.

### Пустой экспорт (0 сообщений за период)

Если Pyrogram вернул `actual_count == 0` (в чате нет сообщений в выбранном диапазоне дат / по фильтрам), `JavaBotClient.send_response()` **не** ходит в Java `/api/convert` — иначе Telegram отклонил бы отправку пустого документа и пользователь получал бы «❌ Не удалось отправить файл».

Вместо этого вызывается `notify_empty_export()` — обычный `sendMessage` с текстом «ℹ️ За период … — … в чате не найдено ни одного сообщения», а job помечается как **completed** (это не ошибка, а нормальный исход).

### Second-line защита от invisible-unicode (java_client.py)

После получения ответа от Java `java_client.py` дополнительно проверяет `cleaned_text`: если **весь** текст состоит из невидимых символов (Unicode categories `Cc`, `Cf`, `Zs`, `Zl`, `Zp`), Java не отправляется файл в Telegram — вместо этого вызывается `notify_empty_export()`. Это защита от случаев, когда Java возвращает технически непустой ответ, но содержащий только управляющие/пробельные символы.
---

## Очереди и crash recovery

Используемые очереди:
- `telegram_export`
- `telegram_export_express`
- `telegram_export_processing`
- `telegram_export_express_processing`

Подход `BLMOVE -> staging` защищает от потери задачи при падении между pop/ack.
На старте worker выполняет `recover_staging_jobs()`.

---

## SQLite message cache

`MessageCache` хранит данные в файле `CACHE_DB_PATH` (по умолчанию `/data/cache/messages.db`).

Ключевые таблицы:
- `messages`
- `chat_id_ranges`
- `chat_date_ranges`
- `chat_meta`

Функциональность:
- пакетная запись/чтение;
- слияние покрытых диапазонов;
- LRU-эвикция по дисковому лимиту (`CACHE_MAX_DISK_GB`);
- TTL-ориентированная логика актуальности диапазонов.

### Топики (forum topics)

Кэш полноценно поддерживает топики. Колонка `topic_id` добавлена во все 4 таблицы, PK включает `(chat_id, topic_id, ...)`. Значение `topic_id=0` означает экспорт всей группы (sentinel — в Telegram General topic = 1, ID 0 не существует). Сообщения одного топика и всей группы хранятся изолированно — нет загрязнения кэша. LRU-эвикция работает по парам `(chat_id, topic_id)`.

---

## Canonical ID mapping

Worker нормализует входы (`@username`, `t.me`, numeric) к единому canonical ID и сохраняет маппинги в Redis (`canonical:*`).
Это нужно для:
- стабильных cache key,
- предикта cache hit в Java (`express` queue).

---

## Конфигурация (env)

Минимум:
- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`
- `TELEGRAM_BOT_TOKEN`

На практике обычно нужны:
- `TELEGRAM_SESSION_STRING` (production)
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_DB`, `REDIS_QUEUE_NAME`
- `JAVA_API_BASE_URL`
- `CACHE_ENABLED`, `CACHE_DB_PATH`, `CACHE_MAX_DISK_GB`
- `JOB_TIMEOUT`, `MAX_RETRIES`, `RETRY_BASE_DELAY`, `RETRY_MAX_DELAY`

## Отмена и propagation ExportCancelled

Отмена экспорта реализована через исключение `ExportCancelled` (определено в `pyrogram_client.py`).

- `get_chat_history` и `_get_topic_history` принимают `is_cancelled_fn: CancelCheck` и проверяют флаг каждые 200 сообщений. При срабатывании — сохраняют текущий батч и бросают `ExportCancelled`.
- `cancellable_floodwait_sleep(seconds, is_cancelled_fn, on_floodwait)` — замена стандартному `asyncio.sleep` во время FloodWait. Тикает посекундно: каждую секунду проверяет `is_cancelled_fn()` и вызывает `on_floodwait`-callback для countdown-уведомлений. При отмене — немедленно бросает `ExportCancelled`.
- В `ExportWorker` все экспортирующие пути содержат `except ExportCancelled: save_batch; raise` — батч не теряется даже при прерывании.
- `_make_cancel_checker` возвращает `is_cancelled_fn` с побочным эффектом: при каждом poll пишет heartbeat в Redis (piggybacking).

---

## Heartbeat observability

Долгие экспорты (300k+ сообщений могут идти час+) не покрыть глобальным
таймаутом — есть риск false-positive kill во время легитимного FloodWait
или большого fetch-а. Вместо этого worker ведёт heartbeat:

**Ключ:** `worker:heartbeat:{task_id}` в Redis
**Значение:** JSON `{"ts": <unix_ts>, "stage": "start|fetch|convert"}`
**TTL:** 120 сек (порог liveness 60 сек, двойной запас на пропущенный tick)

Пишется пиггибэком на cancel-poll — тот уже вызывается каждые 200
сообщений и каждую секунду во время FloodWait, частота идеальная.
Отдельно — перед Java `/api/convert` (fetch закончен, cancel-poll не тикает).

**Kill мы не делаем** — это чисто observability. Админ смотрит и решает.

### Просмотр живых job-ов

```bash
docker exec -it telegram-cleaner-redis-1 redis-cli --scan --pattern 'worker:heartbeat:*' \
  | while read key; do
      val=$(docker exec -i telegram-cleaner-redis-1 redis-cli GET "$key")
      ttl=$(docker exec -i telegram-cleaner-redis-1 redis-cli TTL "$key")
      echo "$key  ttl=${ttl}s  $val"
    done
```

Старый `ts` (>60 сек назад) + TTL близок к 120 = job жив, но не отчитывался
→ смотреть `docker logs telegram-cleaner-python-worker-1` на его `task_id`.
