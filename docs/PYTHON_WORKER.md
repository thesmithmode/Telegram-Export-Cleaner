# Python Worker

Worker находится в папке `export-worker/` и запускается как отдельный контейнер.

## Что делает worker

- Читает задачи из Redis (`QueueConsumer`).
- Проверяет доступ к чату через Pyrogram (`TelegramClient`).
- Резолвит вход (`@username`/ссылка) в canonical chat ID.
- Отдаёт сообщения из SQLite-кэша или догружает из Telegram API.
- Передаёт данные в Java API (`POST /api/convert`).
- Отправляет пользователю прогресс и финальный результат.

## Очередь и recovery

Используется атомарный перенос задачи `BLMOVE`:
- из `telegram_export_express` → `telegram_export_express_processing`,
- или из `telegram_export` → `telegram_export_processing`.

Если worker падает, незавершённые задачи из staging восстанавливаются при старте.

## Кэш сообщений

`MessageCache` использует SQLite (`CACHE_DB_PATH`, по умолчанию `/data/cache/messages.db`).

Особенности:
- хранение msgpack-сериализованных сообщений;
- индексы по `chat_id/msg_id` и timestamp;
- интервалы покрытых диапазонов (по ID и датам);
- LRU-эвикция по лимиту диска (`CACHE_MAX_DISK_GB`).

## Отмена задач

Java-бот выставляет `cancel_export:{taskId}` в Redis.
Worker проверяет флаг во время экспорта и завершает задачу корректно.

## Ключевые env-переменные

- `TELEGRAM_API_ID`, `TELEGRAM_API_HASH`
- `TELEGRAM_SESSION_STRING` (рекомендуется для production)
- `TELEGRAM_PHONE_NUMBER` (нужен для file-based session)
- `TELEGRAM_BOT_TOKEN`
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_DB`, `REDIS_QUEUE_NAME`
- `JAVA_API_BASE_URL`
- `CACHE_ENABLED`, `CACHE_DB_PATH`, `CACHE_MAX_DISK_GB`
- `JOB_TIMEOUT`, `MAX_RETRIES`, `RETRY_BASE_DELAY`, `RETRY_MAX_DELAY`
