# Architecture

## Компоненты

**java-bot (Spring Boot :8080)**
- Telegram long-polling bot (`ExportBot`)
- Управление сессией и диалогом (`UserSession`)
- Постановка задач в Redis (`ExportJobProducer`)
- REST: `POST /api/convert`, `GET /api/health`
- Web Dashboard `/dashboard/**` (Thymeleaf + JSON API, SQLite)

**export-worker (Python)**
- `QueueConsumer` — получение задач из Redis
- `TelegramClient` (Pyrogram) — экспорт сообщений
- `MessageCache` — кэш в SQLite
- `JavaBotClient` — отправка JSON в `/api/convert`

**Redis** — очереди задач, статусы, дедупликация, canonical mapping.

---

## Поток обработки

1. Пользователь → `@username` / `t.me` ссылка боту.
2. `ExportBot` → `ExportJobProducer.enqueue(...)`.
3. Producer: lock `active_export:{userId}` + metadata + payload в `telegram_export` / `telegram_export_express`.
4. Worker: `BLMOVE` atomically в staging → проверка доступа → нормализация chat ID → кэш hit/miss → Telegram API → JSON → `POST /api/convert`.
5. Java форматирует → текстовый файл → пользователь в Telegram.

---

## Redis ключи

**Очереди (по приоритету):**

| Очередь | Источник | Приоритет |
|---|---|---|
| `telegram_export_express` | cache-hit ручных экспортов | 1 (высший) |
| `telegram_export` | ручные без кэша | 2 |
| `telegram_export_subscription` | периодические подписки | 3 (низший) |

Staging: `telegram_export_*_processing` — crash-safe. Worker при старте recovery-дрейнит staging.

**Управление:**
- `active_export:{userId}` — запрет параллельного экспорта
- `cancel_export:{taskId}` — флаг отмены (проверяется каждые 200 msg + FloodWait)
- `job:processing|completed|failed:{taskId}` — lifecycle маркеры
- `canonical:{input}` → canonical chat ID (кэш нормализации)
- `worker:heartbeat:{taskId}` — JSON `{ts, stage}` TTL 120s; piggybacked на cancel-poll. Observability для долгих экспортов без kill-логики.
- `stats:events` (Redis Stream) — события экспорта → Dashboard ingestion

---

## SQLite cache (worker)

Таблицы: `messages` (msgpack), `chat_id_ranges`, `chat_date_ranges`, `chat_meta` (LRU).

Запись батчами, merge диапазонов, LRU eviction при превышении `CACHE_MAX_DISK_GB`.

---

## Надёжность

- `BLMOVE` + staging → не теряем задачу между pop и push.
- Worker recovery при старте — задачи из staging возвращаются в обработку.
- TTL у lock/status — нет «вечных» зависаний.
- Отмена: worker проверяет `cancel_export:{taskId}` каждые 200 сообщений и в FloodWait. Батч сохраняется перед throw — данные не теряются.

---

## Periodic Subscriptions

`SubscriptionScheduler` (cron 5 мин): находит готовые подписки → enqueue в `telegram_export_subscription` только при `hasActiveProcessingJob() == false && queueLength == 0`.

**Anti-duplicate:** `recordRunStarted` зовётся ДО `enqueueSubscription`. Если процесс упадёт между шагами — `lastRunAt` уже выставлен, следующий тик не выстрелит повторно.

**UTC range:** `fromIso`/`toIso` в UTC (`LocalDateTime.ofInstant(now, UTC).truncatedTo(SECONDS)`). Python `ensure_utc` трактует naive datetime как UTC; МСК без offset → 3 последних часа выпадают из выборки.

Lifecycle: `ACTIVE` ↔ `PAUSED` (2 failure подряд или вручную) → `ARCHIVED` (нет confirm 48 ч).
