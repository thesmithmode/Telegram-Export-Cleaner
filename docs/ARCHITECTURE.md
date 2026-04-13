# Architecture

## Компоненты

1. **Java Bot/API (Spring Boot, порт 8080)**
   - Telegram long-polling bot (`ExportBot`).
   - Приём задач и постановка в Redis (`ExportJobProducer`).
   - REST API: `/api/convert`, `/api/health`.

2. **Python Worker (`export-worker`)**
   - Читает очередь Redis (`QueueConsumer`, `BLMOVE`).
   - Экспортирует сообщения через Pyrogram (`TelegramClient`).
   - Кэширует сообщения в SQLite (`MessageCache`).
   - Отправляет результат в Java API (`JavaBotClient`).

3. **Redis**
   - Очереди задач, признаки статусов и флаги отмены.
   - Не хранит сами сообщения (они в SQLite кэше worker-а).

---

## Поток данных

1. Пользователь пишет боту `@username`/`t.me/...`.
2. `ExportBot` собирает параметры даты и вызывает `ExportJobProducer.enqueue(...)`.
3. `ExportJobProducer`:
   - ставит lock `active_export:{userId}` (SET NX + TTL),
   - кладёт JSON задачи в `telegram_export` или `telegram_export_express`.
4. Worker берёт задачу через `BLMOVE` в staging-очередь.
5. Worker:
   - проверяет доступ к чату,
   - пытается отдать данные из SQLite-кэша,
   - при miss догружает из Telegram API,
   - отправляет собранный JSON в `POST /api/convert`.
6. Java API потоково форматирует JSON в plain text и отдаёт файл.
7. Worker отправляет итог пользователю через Telegram Bot API.

---

## Кэш и очереди

### Redis (оркестрация)

Основные ключи:
- `telegram_export`, `telegram_export_express` — очереди.
- `telegram_export_processing`, `telegram_export_express_processing` — staging при обработке.
- `active_export:{userId}` — защита от параллельного экспорта одним пользователем.
- `cancel_export:{taskId}` — флаг отмены.
- `job:processing:*`, `job:completed:*`, `job:failed:*` — маркеры статуса.
- `canonical:*` — маппинг входного идентификатора чата в canonical ID.

### SQLite (данные сообщений)

`export-worker/message_cache.py` хранит:
- таблицу `messages(chat_id, msg_id, msg_ts, data)`;
- интервалы покрытия по ID и датам (`chat_id_ranges`, `chat_date_ranges`);
- метаданные LRU (`chat_meta`) для эвикции по дисковому лимиту.

---

## Отказоустойчивость

- Worker использует `BLMOVE` в staging-очередь, чтобы не терять задачу между pop/push.
- При рестарте worker выполняет recovery зависших задач из staging.
- `active_export` и job-маркеры имеют TTL для автоочистки.
- Отмена задачи работает даже если она уже в обработке: worker периодически проверяет `cancel_export:{taskId}`.
