# Architecture

## 1. Компоненты

### Java (Spring Boot, `java-bot`)

Ответственность:
- Telegram long-polling bot (`ExportBot`).
- Управление пользовательской сессией и диалогом выбора дат (`UserSession`).
- Постановка задач в Redis (`ExportJobProducer`).
- REST API:
  - `POST /api/convert`
  - `GET /api/health`

### Python (`export-worker`)

Ответственность:
- Получение задач из Redis (`QueueConsumer`).
- Проверка доступа к чату + экспорт через Pyrogram (`TelegramClient`).
- Кэширование сообщений в SQLite (`MessageCache`).
- Отправка результата пользователю через Java-путь/бот (`JavaBotClient`).

### Redis

Ответственность:
- Очереди задач.
- Сигналы статуса/отмены.
- Сервисные ключи (дедупликация, staging, canonical mapping).

---

## 2. Поток обработки

1. Пользователь отправляет `@username` или `t.me` ссылку боту.
2. `ExportBot` формирует параметры даты и вызывает `ExportJobProducer.enqueue(...)`.
3. `ExportJobProducer`:
   - ставит lock `active_export:{userId}`;
   - сохраняет metadata задачи;
   - отправляет payload в `telegram_export` или `telegram_export_express`.
4. Worker забирает задачу atomically через `BLMOVE` в staging-очередь.
5. Worker:
   - проверяет доступ к чату;
   - нормализует chat ID (canonical);
   - пробует отдать из SQLite cache;
   - при cache miss идет в Telegram API и дозаполняет кэш.
6. Worker отправляет JSON в Java `POST /api/convert`.
7. Java потоково форматирует и возвращает текстовый output.
8. Пользователь получает результат в Telegram.

---

## 3. Очереди и ключи Redis

### Очереди
- `telegram_export` — обычная очередь.
- `telegram_export_express` — приоритетная очередь (когда есть признаки cache hit).
- `telegram_export_processing`, `telegram_export_express_processing` — staging для crash-safe обработки.

### Статус/управление
- `active_export:{userId}` — запрет параллельного экспорта пользователя.
- `cancel_export:{taskId}` — флаг отмены.
- `job:processing:{taskId}` / `job:completed:{taskId}` / `job:failed:{taskId}` — маркеры job lifecycle.
- `staging:jobs`, `staging:meta:{taskId}` — восстановление задач после падения worker-а.
- `canonical:{input}` — кэш соответствия входного идентификатора чата canonical ID.
- `worker:heartbeat:{taskId}` — liveness-маркер долгого экспорта; JSON `{"ts": <unix_ts>, "stage": "start|fetch|convert"}`, TTL 120 сек. Пишется пиггибэком на cancel-poll и перед вызовом `/api/convert`.

---

## 4. SQLite cache (worker)

Кэш реализован в `export-worker/message_cache.py`.

Таблицы:
- `messages` — сериализованные сообщения (msgpack) + timestamp.
- `chat_id_ranges` — покрытие по диапазонам message ID.
- `chat_date_ranges` — покрытие по диапазонам дат.
- `chat_meta` — last_access + размер/количество, используется для LRU эвикции.

Поведение:
- запись батчами;
- merge диапазонов при дозагрузках;
- LRU eviction при превышении лимита диска (`CACHE_MAX_DISK_GB`).

---

## 5. Надежность и отказоустойчивость

- `BLMOVE` + staging защищает от потери задачи в момент между pop/push.
- На старте worker делает recovery задач, зависших в staging.
- TTL у lock/status ключей препятствует «вечным» зависаниям статусов.
- Отмена поддерживается через `ExportCancelled`: worker проверяет `cancel_export:{taskId}` каждые 200 сообщений и каждую секунду во время FloodWait (`cancellable_floodwait_sleep`). Текущий батч сохраняется перед выбросом исключения — данные не теряются.
- Heartbeat (`worker:heartbeat:{taskId}`) даёт observability для долгих экспортов: администратор может диагностировать зависания по `ts` и текущей стадии (`start`/`fetch`/`convert`) без kill-логики.

---

## 6. Границы ответственности

- **Java API**: безопасно и потоково форматирует текст, валидирует входные параметры.
- **Worker**: общается с Telegram API и отвечает за сбор/кэш исходных сообщений.
- **Redis**: координация очередей и состояний, не долговременное хранение message history.

---

## Subscriptions

Поток подписки: USER создаёт подписку в дашборде → `SubscriptionScheduler` (cron каждые 5 мин) находит готовые к запуску → idle-check → enqueue в низкоприоритетную очередь `telegram_export_subscription` → worker забирает по приоритету `express > main > subscription` → экспорт выполняется → файл идёт пользователю в ЛС.

### Lifecycle статуса

`ACTIVE` → `PAUSED` (две подряд неудачи или ручная пауза) → обратно `ACTIVE` через dashboard
`ACTIVE` → `ARCHIVED` (нет подтверждения в течение 48 ч после confirm-запроса)

### Confirmation flow

`ConfirmationScheduler` ежедневно в 07:00 UTC (10:00 МСК):
1. Для подписок с `lastConfirmAt > 7 дней назад` и `confirmSentAt IS NULL` — шлёт боту сообщение с inline-кнопкой "Подтвердить", выставляет `confirmSentAt = now`.
2. Для подписок с `confirmSentAt > 48 ч назад` — переводит в `ARCHIVED` и уведомляет пользователя.

### Приоритеты очередей Redis

| Очередь | Источник | Приоритет |
|---|---|---|
| `telegram_export_express` | Кэш-hit ручных экспортов | 1 (высший) |
| `telegram_export` | Ручные экспорты без кэша | 2 |
| `telegram_export_subscription` | Периодические итерации подписок | 3 (низший) |

`SubscriptionScheduler` ставит задачи в `_subscription` только когда `hasActiveProcessingJob() == false` и `getQueueLength() == 0`. Воркер drain'ит очереди по приоритету через последовательные BLPOP.

### База данных

Новая таблица `chat_subscriptions`:
- `id`, `bot_user_id`, `chat_ref_id`
- `period_hours` (24/48/72/168), `desired_time_msk` ("HH:MM"), `since_date` (Instant)
- `status` (ACTIVE/PAUSED/ARCHIVED) — partial UNIQUE index по `(bot_user_id)` WHERE status='ACTIVE'
- `last_run_at`, `last_success_at`, `last_failure_at`, `consecutive_failures`
- `last_confirm_at`, `confirm_sent_at`
- `created_at`, `updated_at`

Поле `subscription_id` в `export_events` связывает итерацию с родительской подпиской (NULL = ручной экспорт).
