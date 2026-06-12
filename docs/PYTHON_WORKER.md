# Python Worker

Код в `export-worker/`. Общий поток: `QueueConsumer` → BLMOVE staging → экспорт через Pyrogram → `JavaBotClient` → `POST /api/convert` → файл пользователю.

## Нетривиальные детали

### Пустой экспорт (0 сообщений)

Если `actual_count == 0` — `JavaBotClient.send_response()` **не** ходит в `/api/convert` (Telegram отклонил бы пустой документ). Вместо этого — `notify_empty_export()` с текстовым сообщением. Job помечается `completed` — это нормальный исход.

Subscription iteration с `messages_count == 0`: `notify_subscription_empty(chat_id, chat_label, from_date, to_date)` вместо файла.

### Streaming `/api/convert` response (java_client.py)

`JavaBotClient` не читает ответ `/api/convert` через `response.text`: очищенный текст стримится во временный `.txt` на диске, sentinel `##OK##` проверяется на лету. Общего лимита размера очищенного экспорта нет: лимитируется свободным местом на диске (`EXPORT_MIN_FREE_DISK_MB`) и временем job. Telegram-доставка тоже идет из файла; части больше лимита Bot API режутся последовательно во временные part-файлы, без списка всех частей в памяти.

### Direct cached export

`WORKER_DIRECT_CACHE_EXPORT=true` отправляет cache-hit результат без `/api/convert`: worker пишет txt напрямую из SQLite.

ID-cache теперь разделяет visible ranges и coverage ranges:
- `chat_id_ranges` означает “эти сообщения реально сохранены”.
- `chat_id_coverage_ranges` означает “этот ID-диапазон уже проверен в Telegram”.

Пустой или sparse gap после успешного `get_chat_history` помечается checked через `mark_id_range_checked()`. При cancel/error отметка не ставится.

`messages.formatted_line`, `filter_text`, `format_version` ускоряют повторный txt-export. Новые сообщения получают поля при записи; старые строки backfill-ятся лениво батчами в `iter_export_lines()`.

`export_artifacts` ускоряет только полный неотфильтрованный экспорт. Artifact создается после успешной прямой сборки txt, если файл больше `EXPORT_ARTIFACT_MIN_BYTES`; hit разрешен только при совпадении `coverage_max_id`, `message_count` и `format_version`. Потерянный artifact-файл считается miss.

Почему такое решение, даже если кажется странным:

Coverage решает корневую проблему tiny gaps без раздувания диска. Artifact-cache вторичен и ограничен LRU, потому что готовый txt не должен заменять canonical message-cache и не подходит для date/keyword/limit экспортов.

### Invisible-unicode защита (java_client.py)

После `/api/convert` проверяет временный файл с очищенным текстом на Unicode categories `Cc/Cf/Zs/Zl/Zp` потоково. Если весь текст — только invisible символы → `notify_empty_export()`. Защита от технически непустого, но визуально пустого ответа.

### Отмена и ExportCancelled

`get_chat_history` / `_get_topic_history` принимают `is_cancelled_fn` и проверяют каждые 200 сообщений. При FloodWait — `cancellable_floodwait_sleep` тикает посекундно. При срабатывании — сохраняют текущий батч и бросают `ExportCancelled` — данные не теряются.

`_make_cancel_checker` пишет heartbeat (`worker:heartbeat:{task_id}`) пиггибэком на cancel-poll.

### Heartbeat (observability, не kill)

**Ключ:** `worker:heartbeat:{task_id}` · **Значение:** `{"ts": <unix>, "stage": "start|fetch|convert"}` · **TTL:** 120 с.

Kill не делаем — admin смотрит и решает. `ts` > 60 с назад + TTL ≈ 120 = job живой но завис → смотреть `docker logs` по `task_id`.

```bash
docker exec -it telegram-cleaner-redis-1 redis-cli --scan --pattern 'worker:heartbeat:*' \
  | while read key; do
      val=$(docker exec -i telegram-cleaner-redis-1 redis-cli GET "$key")
      ttl=$(docker exec -i telegram-cleaner-redis-1 redis-cli TTL "$key")
      echo "$key  ttl=${ttl}s  $val"
    done
```

### Topics (forum)

`topic_id` добавлен во все 4 таблицы кэша. `topic_id=0` = sentinel для «всей группы» (General = 1 в Telegram, 0 не существует). Сообщения топика и всей группы хранятся изолированно. LRU эвикция по парам `(chat_id, topic_id)`.

### Priority drain (подписки не блокируют ручные)

BLPOP: `express` (1 с) → `main` (2 с) → `subscription` (2 с). Scheduler ставит в `subscription` только когда `hasActiveProcessingJob == false && queueLength == 0`.

## Конфигурация (env)

Обязательные: `TELEGRAM_API_ID`, `TELEGRAM_API_HASH`, `TELEGRAM_BOT_TOKEN`, `JAVA_API_KEY`.

Обычно нужны: `TELEGRAM_SESSION_STRING`, `REDIS_HOST/PORT/DB/PASSWORD`, `JAVA_API_BASE_URL` (default `http://java-bot:8080`), `CACHE_ENABLED/DB_PATH/MAX_DISK_GB` (default 25.0), `EXPORT_ARTIFACT_CACHE_ENABLED/EXPORT_ARTIFACT_DIR/EXPORT_ARTIFACT_MIN_BYTES/EXPORT_ARTIFACT_MAX_DISK_GB`, `EXPORT_TEMP_DIR/EXPORT_MIN_FREE_DISK_MB`, `JOB_TIMEOUT/MAX_RETRIES`, `STATS_STREAM_KEY` (default `stats:events`).
