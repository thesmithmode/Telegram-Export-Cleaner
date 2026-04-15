# Web Dashboard

Веб-дашборд для просмотра статистики использования бота: кто запрашивал
экспорты, какие чаты/топики качал, сколько сообщений и байт было выгружено
за выбранный период.

> **Статус:** в активной разработке. План — `cheeky-mixing-castle.md` (см. ветку
> `dashboard`). Текущий PR-1 закладывает только инфраструктуру: SQLite-БД,
> Liquibase-changelog со схемой, пустые таблицы. Ни UI, ни авторизации, ни
> сборщика событий ещё нет.

## Архитектура

Модуль живёт внутри того же Spring Boot процесса, что и бот (`telegram-cleaner`),
по адресу `/dashboard/**`. Хранилище — SQLite в отдельном Docker volume
`dashboard_data`, файл `/data/stats/dashboard.db`. Миграции схемы — Liquibase
(formatted SQL), единый changelog `src/main/resources/db/changelog/db.changelog-master.sql`.
Liquibase выбран вместо Flyway: в OSS Flyway 10+ SQLite-диалект отсутствует
(вынесен в коммерческий `flyway-database-sqlite`), а Liquibase поддерживает
SQLite из коробки.

```
java-bot (Spring Boot)
├── /api/**          — текущий REST API экспорта (без изменений)
└── /dashboard/**    — веб-UI + /dashboard/api/stats/** (в разработке)
        │
        ▼
   SQLite (dashboard_data volume)
   ├── bot_users         — пользователи Telegram-бота
   ├── chats             — экспортируемые чаты/каналы/топики
   ├── export_events     — каждое событие экспорта (источник правды)
   └── dashboard_users   — логины веб-UI (роли ADMIN / USER)
```

## Схема БД (changeset `001-init-dashboard-schema`)

Полный DDL — `src/main/resources/db/changelog/db.changelog-master.sql`.
Ключевые контракты:

| Таблица | Назначение | Идентичность |
|---------|------------|--------------|
| `bot_users` | агрегаты по пользователю Telegram-бота (счётчики `total_*`) | PK `bot_user_id` (Telegram user_id) |
| `chats` | таргеты экспорта; `chat_type` пока NULL | UNIQUE `(canonical_chat_id, COALESCE(topic_id,-1))` |
| `export_events` | один экспорт = одна строка | UNIQUE `task_id` (идемпотентный ключ из `ExportJobProducer`) |
| `dashboard_users` | веб-логины; `bot_user_id` связывает USER-роль с Telegram user_id | UNIQUE `username` |

**Индексы для агрегаций** (`export_events`):
`(bot_user_id, started_at DESC)`, `(chat_ref_id, started_at DESC)`,
`(started_at DESC)`, `(status)`. Этого достаточно для запросов overview/users/chats
на ожидаемых объёмах (сотни-тысячи событий в день). Rollup-таблицы добавим
позже, если индексы перестанут вытягивать.

## Конфигурация

Production (`application.properties`):
- `spring.datasource.url=jdbc:sqlite:${DASHBOARD_DB_PATH:./dashboard.db}`
- `spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect`
- `spring.jpa.hibernate.ddl-auto=none` — DDL владеет Liquibase; `validate` с SQLite ловит false-positive несоответствия типов (TEXT affinity ≠ VARCHAR)
- `spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.sql`
- Hikari `connection-init-sql=PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;`

Tests (`src/test/resources/application.properties`):
- `spring.datasource.url=jdbc:sqlite::memory:`
- `spring.datasource.hikari.maximum-pool-size=1` — иначе каждое новое
  соединение получит свою in-memory БД и Liquibase/тесты разойдутся.

ENV (`docker-compose.yml`):
- `DASHBOARD_DB_PATH=/data/stats/dashboard.db`
- volume `dashboard_data:/data/stats`

## REST API (PR-8)

JSON под `/dashboard/api/**`. Все эндпоинты требуют аутентификации (cookie-based
JSESSIONID, form-login); `/stats/users` — ADMIN-only (URL-guard в `DashboardSecurityConfig`
+ дубль через `BotUserAccessPolicy` в коде). Пользователь с ролью `USER` видит
только свой `botUserId`; попытка указать чужой `userId` → 403.

| Метод | Path | Доступ | Назначение |
|---|---|---|---|
| GET | `/dashboard/api/me` | auth | `{username, role, botUserId}` для фронта |
| GET | `/dashboard/api/stats/overview?period=&from=&to=&userId=` | auth (USER→свой) | totals + topUsers + topChats + statusBreakdown |
| GET | `/dashboard/api/stats/users?limit=` | **ADMIN only** | список `UserStatsRow` |
| GET | `/dashboard/api/stats/user/{botUserId}` | auth (USER → только свой) | `UserDetailDto` |
| GET | `/dashboard/api/stats/chats?period=&from=&to=&userId=&limit=` | auth (USER→свой) | топ чатов по байтам |
| GET | `/dashboard/api/stats/timeseries?period=&from=&to=&metric=&granularity=&userId=` | auth (USER→свой) | для графиков Chart.js |
| GET | `/dashboard/api/stats/status-breakdown?period=&from=&to=&userId=` | auth (USER→свой) | `{COMPLETED: n, FAILED: n, ...}` |
| GET | `/dashboard/api/stats/events?userId=&chatId=&status=&limit=` | auth (USER→свой) | raw-таблица последних N экспортов |

**`period`:** `day` (today-1d) · `week` (today-7d) · `month` (today-30d) ·
`year` (today-1y) · `all` (2020-01-01..today) · `custom` (требует `from`+`to`).
Granularity auto: ≤31d→DAY, ≤365d→WEEK, иначе MONTH (override через `granularity=day|week|month`).

**`metric`** для timeseries: `exports` (COUNT), `messages` (SUM messages_count),
`bytes` (SUM bytes_count). Бакеты — `strftime('%Y-%m-%d'|'%Y-W%W'|'%Y-%m', started_at)`.

**Ошибки (`DashboardExceptionHandler`, scoped to `com.tcleaner.dashboard.web`):**
- `AccessDeniedException` → 403 `{"error":"forbidden"}`
- `DateTimeParseException` / `MethodArgumentTypeMismatchException` → 400 `{"error":"bad_request"}`
- `IllegalArgumentException` → 400 (например, `granularity=bogus`)
- `EmptyResultDataAccessException` → 404

Юзер-детали (`/stats/user/{id}`) читаются из денорм-счётчиков в `bot_users`,
а агрегации по периоду — из `export_events` через native SQL (см. `StatsQueryService`).

## Что дальше

Следующие итерации (см. план `cheeky-mixing-castle.md`):

- PR-2..PR-8 — готово (инфраструктура, ingestion, stats query, security, REST API)
- PR-9 — Thymeleaf layout + login + error page + общий CSS/JS + Chart.js vendor
- PR-10 — Overview page + графики (Chart.js time series + period filter)
- PR-11 — Users / user-detail pages
- PR-12 — Chats / events pages
- PR-13 — Traefik + HTTPS под доменом `tec.searchingforgamesforever.online`
- PR-14 — `docs/SERVER_SETUP.md` и финальная инфраструктурная документация
