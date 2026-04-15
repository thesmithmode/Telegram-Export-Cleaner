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

## Что дальше

Следующие итерации (см. план `cheeky-mixing-castle.md`):

- PR-2 — JPA-сущности + репозитории
- PR-3 — Redis Streams event-bus (`stats:events`)
- PR-4 — Ingestion service + хуки в `ExportBot`/`ExportJobProducer`
- PR-5 — Измерение `bytes_count` в `TelegramController`
- PR-6 — Stats query service
- PR-7 — Security (два SecurityFilterChain, env-bootstrap)
- PR-8 — REST API дашборда
- PR-9..12 — Thymeleaf UI
- PR-13 — Traefik + HTTPS под доменом `tec.example.com`
- PR-14 — `docs/SERVER_SETUP.md` и финальная инфраструктурная документация
