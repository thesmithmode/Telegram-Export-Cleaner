# Web Dashboard

Веб-дашборд для просмотра статистики использования бота: кто запрашивал
экспорты, какие чаты/топики качал, сколько сообщений и байт было выгружено
за выбранный период.

## Архитектура

Модуль живёт внутри того же Spring Boot процесса, что и бот (`telegram-cleaner`),
по адресу `/dashboard/**`. Хранилище — SQLite в отдельном Docker volume
`dashboard_data`, файл `/data/stats/dashboard.db`. Миграции схемы — Liquibase
(formatted SQL), единый changelog `src/main/resources/db/changelog/db.changelog-master.sql`.

```
java-bot (Spring Boot :8080)
├── /api/**              — REST API экспорта (без изменений)
└── /dashboard/**        — Thymeleaf SSR + JSON API
        ├── /dashboard/login       — FormLogin
        ├── /dashboard/overview    — KPI + графики (Chart.js)
        ├── /dashboard/users       — таблица юзеров (ADMIN only)
        ├── /dashboard/user/{id}   — карточка юзера
        ├── /dashboard/chats       — чаты + top-20 chart
        ├── /dashboard/events      — raw-лог экспортов
        └── /dashboard/api/stats/** — JSON API для JS-слоя
                │
                ▼
           SQLite (dashboard_data volume)
           ├── bot_users         — пользователи Telegram-бота
           ├── chats             — экспортируемые чаты/каналы/топики
           ├── export_events     — события экспорта (источник правды)
           └── dashboard_users   — логины веб-UI (роли ADMIN / USER)

    Ingestion: Redis Stream stats:events → StatsStreamConsumer → БД
```

## Схема БД

Полный DDL — `src/main/resources/db/changelog/db.changelog-master.sql`.

| Таблица | Назначение | Уникальный ключ |
|---------|------------|-----------------|
| `bot_users` | агрегаты по пользователю Telegram-бота (счётчики `total_*`) | PK `bot_user_id` (Telegram user_id) |
| `chats` | таргеты экспорта; `chat_type` пока NULL | UNIQUE `(canonical_chat_id, COALESCE(topic_id,-1))` |
| `export_events` | один экспорт = одна строка | UNIQUE `task_id` (идемпотентный ключ) |
| `dashboard_users` | веб-логины; `bot_user_id` связывает USER с Telegram user_id | UNIQUE `username` |

## REST API

JSON под `/dashboard/api/**`. Все эндпоинты требуют аутентификации (cookie-based
JSESSIONID, form-login); `/stats/users` — ADMIN-only.
Пользователь с ролью `USER` видит только свой `botUserId`; чужой `userId` → 403.

| Метод | Path | Доступ | Назначение |
|---|---|---|---|
| GET | `/dashboard/api/me` | auth | `{username, role, botUserId}` |
| GET | `/dashboard/api/stats/overview` | auth (USER→свой) | KPI + topUsers + topChats + statusBreakdown |
| GET | `/dashboard/api/stats/users` | **ADMIN only** | список `UserStatsRow` |
| GET | `/dashboard/api/stats/user/{botUserId}` | auth (USER → только свой) | `UserDetailDto` |
| GET | `/dashboard/api/stats/chats` | auth (USER→свой) | топ чатов по байтам |
| GET | `/dashboard/api/stats/timeseries` | auth (USER→свой) | для Chart.js |
| GET | `/dashboard/api/stats/status-breakdown` | auth | `{COMPLETED: n, FAILED: n, …}` |
| GET | `/dashboard/api/stats/events` | auth (USER→свой) | raw-лог последних N экспортов |
| GET | `/dashboard/api/admin/cache-metrics` | **ADMIN only** | заполненность SQLite-кэша worker'а: totals, top-50 чатов, heatmap LRU, сегментация по chat_type |

**Query-параметры (общие):** `period=day|week|month|year|all|custom`, `from=YYYY-MM-DD`,
`to=YYYY-MM-DD`, `userId=<botUserId>`.

**`period` → диапазон дат:** `day`=сутки · `week`=7д · `month`=30д · `year`=365д ·
`all`=с 2020-01-01 · `custom`=требует `from`+`to`.

**`metric`** (для timeseries): `exports` / `messages` / `bytes`.

**Granularity auto:** ≤31д→DAY · ≤365д→WEEK · иначе MONTH.

## Security

Два `SecurityFilterChain`:
- `@Order(1) dashboardFilterChain` — `/dashboard/**`: CSRF on, stateful сессия, FormLogin.
  Статика и `/dashboard/login` — `permitAll`. `/dashboard/users` — `hasRole('ADMIN')`.
- `@Order(2) apiFilterChain` — `/api/**`: STATELESS, CSRF off, без аутентификации (эндпоинт доступен только внутри Docker-сети).

RBAC дополнительно enforced в коде через `BotUserAccessPolicy.effectiveUserId()`.

**Env-bootstrap** (`EnvUserBootstrap`): при старте upsert двух юзеров — admin (ADMIN) и
user (USER) — из env-переменных с BCrypt-хэшем. Password обновляется только если
`matches()` = false.

**Rate-limit login:** in-memory `LoginAttemptService` — блокировка на 5 мин после 5 неудач.

## Frontend

Подход: SSR (Thymeleaf) рендерит каркас страницы сразу; данные и графики догружаются
через fetch к JSON API.

**Шаблоны** (`src/main/resources/templates/dashboard/`):
- `layout.html` — базовый layout (head + header + footer)
- `fragments/header.html` — навигация, username, logout-форма
- `fragments/period-filter.html` — кнопки All/Year/Month/Week/Day
- `fragments/chart-block.html` — переиспользуемый `<canvas>` для Chart.js
- `login.html`, `error.html`, `overview.html`, `users.html`, `user-detail.html`, `chats.html`, `events.html`

**Статика** (`src/main/resources/static/dashboard/`):
- `css/app.css` — монохромная палитра, компоненты
- `js/app.js` — `window.Dashboard`: fetchJson, formatNumber, formatBytes, formatDate, period-filter
- `js/pages/{overview,users,user-detail,chats,events}.js` — Chart.js рендер и данные
- `vendor/chart.min.js` — Chart.js 4.x (загружается в Dockerfile при сборке образа)

**Chart.js** не поставляется в репозитории (`.gitignore`). Dockerfile скачивает
`chart.min.js` при сборке образа через `curl` с `ARG CHART_JS_VERSION=4.4.1`.

## Конфигурация

```properties
# SQLite
spring.datasource.url=jdbc:sqlite:${DASHBOARD_DB_PATH:./dashboard.db}?date_class=text
spring.jpa.hibernate.ddl-auto=none

# Прокси (для корректных redirect и Secure-cookie за Traefik)
server.forward-headers-strategy=NATIVE

# Auth bootstrap
dashboard.auth.admin.username=${DASHBOARD_ADMIN_USERNAME:admin}
dashboard.auth.admin.password=${DASHBOARD_ADMIN_PASSWORD:admin}
dashboard.auth.test.username=${DASHBOARD_TEST_USERNAME:user}
dashboard.auth.test.password=${DASHBOARD_TEST_PASSWORD:user}
dashboard.auth.test.bot-user-id=${DASHBOARD_TEST_BOT_USER_ID:0}
```

## Ingestion (Redis Streams)

Данные пишутся в Redis Stream `stats:events`. Схема — `StatsEventPayload` (JSON в поле
`payload`). Типы событий: `bot_user.seen`, `export.started`, `export.completed`,
`export.failed`, `export.cancelled`, `export.bytes_measured`.

`StatsStreamConsumer` читает стрим consumer-group'ой `dashboard-writer`, делает
idempotent upsert через `ExportEventIngestionService` (UNIQUE `task_id`), XACK'ает.
Pending-list сообщений переживает рестарты java-bot — at-least-once гарантия.

## Известные ограничения и планы

- Rollup-таблицы не реализованы — индексов достаточно на текущих объёмах
- Миграция SQLite → PostgreSQL: заменить dialect + docker-compose сервис; API/репозитории не меняются
- Telegram Login Widget: задел в `dashboard_users.provider` (сейчас только `LOCAL`)
- Audit log для write-операций UI: отложен до появления мутирующего UI
