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
