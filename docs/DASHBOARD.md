# Web Dashboard

Веб-дашборд для просмотра статистики использования бота: кто запрашивал
экспорты, какие чаты/топики качал, сколько сообщений и байт было выгружено
за выбранный период.

## Архитектура

Модуль живёт внутри того же Spring Boot процесса, что и бот (`telegram-cleaner`),
по адресу `/dashboard/**`. Хранилище — SQLite в host bind mount
`${HOST_DATA_PATH}/dashboard:/data/stats`, файл `/data/stats/dashboard.db`.
Миграции схемы — Liquibase (formatted SQL), единый changelog
`src/main/resources/db/changelog/db.changelog-master.sql`.

```
java-bot (Spring Boot :8080)
├── /api/**                          — REST API экспорта (X-API-Key, internal-only)
└── /dashboard/**                    — Thymeleaf SSR + JSON API
        ├── /dashboard/login         — страница логина (Telegram Login Widget)
        ├── /dashboard/login/telegram — POST initData → session
        ├── /dashboard/me            — личный кабинет USER (своя статистика)
        ├── /dashboard/mini-app      — Telegram Mini App entry
        ├── /dashboard/overview      — KPI + графики (ADMIN only)
        ├── /dashboard/users         — таблица юзеров (ADMIN only)
        ├── /dashboard/user/{id}     — карточка юзера (ADMIN)
        ├── /dashboard/chats         — чаты + top-20 chart
        ├── /dashboard/events        — raw-лог экспортов
        ├── /dashboard/api/me/**     — JSON API текущего USER
        ├── /dashboard/api/stats/**  — JSON API ADMIN
        └── /dashboard/api/admin/**  — admin-only (cache-metrics)
                │
                ▼
           SQLite (bind mount: ${HOST_DATA_PATH}/dashboard → /data/stats)
           ├── bot_users         — пользователи Telegram-бота
           ├── chats             — экспортируемые чаты/каналы/топики
           ├── export_events     — события экспорта (источник правды)
           ├── chat_subscriptions — периодические подписки пользователей
           └── dashboard_users   — логины веб-UI (роли ADMIN / USER)

    Ingestion: Redis Stream stats:events → StatsStreamConsumer → БД
```

## Периодические подписки

Пользователи могут подписываться на регулярный экспорт чатов (24, 48, 72 или 168 часов).

### Логика работы
- **Уникальность:** В alpha-версии разрешена только одна активная подписка на одного пользователя.
- **Окно запуска:** Подписка считается готовой к запуску за 30 минут до желаемого времени (МСК) по истечении периода.
- **Подтверждение:** Раз в 7 дней бот запрашивает подтверждение актуальности подписки. Если пользователь не подтверждает в течение 48 часов, подписка архивируется.
- **Авто-пауза:** После 2 подряд неудачных попыток экспорта (например, из-за блокировки бота или удаления из чата) подписка переходит в статус `PAUSED`.

### REST API Подписок

| Метод | Path | Доступ | Назначение |
|---|---|---|---|
| GET  | `/dashboard/api/subscriptions`          | auth | Список подписок (USER — свои, ADMIN — все) |
| POST | `/dashboard/api/subscriptions`          | USER | Создание новой подписки |
| PATCH| `/dashboard/api/subscriptions/{id}/pause` | auth | Приостановка подписки |
| PATCH| `/dashboard/api/subscriptions/{id}/resume`| auth | Возобновление подписки |
| DELETE| `/dashboard/api/subscriptions/{id}`      | auth | Удаление подписки |

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
JSESSIONID, выдаётся через Telegram Mini App login).
Пользователь с ролью `USER` видит только свой `botUserId`; чужой `userId` → 403.

### Текущий USER

| Метод | Path | Доступ | Назначение |
|---|---|---|---|
| GET  | `/dashboard/api/me`                     | auth | `{username, role, botUserId, language}` |
| GET  | `/dashboard/api/me/overview`            | USER | KPI + topChats + statusBreakdown своего профиля |
| GET  | `/dashboard/api/me/chats`               | USER | топ чатов своего профиля |
| GET  | `/dashboard/api/me/events`              | USER | raw-лог последних N экспортов своего профиля |
| GET  | `/dashboard/api/me/timeseries`          | USER | для Chart.js (свой профиль) |
| GET  | `/dashboard/api/me/status-breakdown`    | USER | свой `{COMPLETED:n, FAILED:n, ...}` |
| POST | `/dashboard/api/me/settings/language`   | USER | `{language: "ru"\|"en"\|...}` — смена языка UI |

### ADMIN

| Метод | Path | Доступ | Назначение |
|---|---|---|---|
| GET | `/dashboard/api/stats/overview`        | ADMIN | KPI + topUsers + topChats + statusBreakdown |
| GET | `/dashboard/api/stats/users`           | ADMIN | список `UserStatsRow` |
| GET | `/dashboard/api/stats/user/{botUserId}` | ADMIN | `UserDetailDto` |
| GET | `/dashboard/api/stats/chats`           | ADMIN | топ чатов по байтам |
| GET | `/dashboard/api/stats/timeseries`      | ADMIN | для Chart.js |
| GET | `/dashboard/api/stats/status-breakdown` | ADMIN | `{COMPLETED:n, FAILED:n, ...}` |
| GET | `/dashboard/api/stats/recent`          | ADMIN | raw-лог последних N экспортов всех юзеров |
| GET | `/dashboard/api/admin/cache-metrics`   | ADMIN | заполненность SQLite-кэша worker'а: totals, top-50 чатов, heatmap LRU, сегментация по chat_type |

### Аутентификация

| Метод | Path | Назначение |
|---|---|---|
| POST | `/dashboard/login/telegram` | Принимает `initData` от Telegram Mini App → проверка HMAC → session + `tg_uid` cookie |

**Query-параметры (общие):** `period=day|week|month|year|all|custom`, `from=YYYY-MM-DD`,
`to=YYYY-MM-DD`, `userId=<botUserId>` (только ADMIN).

**`period` → диапазон дат:** `day`=сутки · `week`=7д · `month`=30д · `year`=365д ·
`all`=с 2020-01-01 · `custom`=требует `from`+`to`.

**`metric`** (для timeseries): `exports` / `messages` / `bytes`.

**Granularity auto:** ≤31д→DAY · ≤365д→WEEK · иначе MONTH.

## Security

Три `SecurityFilterChain`:
- `@Order(HIGHEST_PRECEDENCE) ActuatorSecurityConfig` — `/actuator/**`: `/health` `permitAll`, остальное `denyAll()`.
- `@Order(1) dashboardFilterChain` — `/dashboard/**`: CSRF on, stateful сессия. Только Telegram Mini App auth (никакого FormLogin/BCrypt). Статика, `/dashboard/login`, `/dashboard/login/telegram`, `/dashboard/mini-app` — `permitAll`. Admin-страницы — `hasRole('ADMIN')`.
- `@Order(2) apiFilterChain` — `/api/**`: STATELESS, CSRF off, `ApiKeyFilter` требует header `X-API-Key` (исключение: `/api/health` публичен). Эндпоинт открыт только внутри Docker-сети.

RBAC дополнительно enforced в коде через `BotUserAccessPolicy.effectiveUserId()`.

**Identity-guard cookie** (`tg_uid`): JS читает значение и сравнивает с
`Telegram.WebApp.initDataUnsafe.user.id`. Mismatch → принудительный re-login.
Закрывает дыру переиспользования WebView в Telegram attachment menu.

**Env-bootstrap** (`EnvUserBootstrap`, активен только при
`DASHBOARD_ENABLE_BOOTSTRAP=true`): при старте создаёт **одного** admin-пользователя
по `DASHBOARD_ADMIN_TG_ID`. Пароль не хранится — auth через Telegram. Если
`telegram-id` уже есть в БД → upsert роли в ADMIN. Дубликата при перезапуске не создаёт.

## Frontend

Подход: SSR (Thymeleaf) рендерит каркас страницы сразу; данные и графики догружаются
через fetch к JSON API.

**Шаблоны** (`src/main/resources/templates/dashboard/`):
- `layout.html` — базовый layout (head + header + footer)
- `fragments/header.html` — навигация, username, logout-форма
- `fragments/period-filter.html` — кнопки All/Year/Month/Week/Day
- `fragments/chart-block.html` — переиспользуемый `<canvas>` для Chart.js
- `login.html`, `error.html`, `me.html`, `mini-app.html`, `overview.html`, `users.html`, `user-detail.html`, `chats.html`, `events.html`

**Статика** (`src/main/resources/static/dashboard/`):
- `css/app.css` — монохромная палитра, компоненты
- `js/app.js` — `window.Dashboard`: fetchJson, formatNumber, formatBytes, formatDate, period-filter
- `js/pages/{overview,users,user-detail,chats,events,me,mini-app}.js` — Chart.js рендер и данные
- `vendor/chart.min.js` — Chart.js 4.x (загружается в Dockerfile при сборке образа)

**Chart.js** не поставляется в репозитории (`.gitignore`). Dockerfile скачивает
`chart.min.js` при сборке образа через `curl` с `ARG CHART_JS_VERSION=4.4.1`.

## Конфигурация

```properties
# SQLite (bind mount: ${HOST_DATA_PATH}/dashboard → /data/stats)
spring.datasource.url=jdbc:sqlite:${DASHBOARD_DB_PATH:./dashboard.db}?date_class=text
spring.jpa.hibernate.ddl-auto=none

# Прокси (для корректных redirect и Secure-cookie за Traefik)
server.forward-headers-strategy=NATIVE

# Auth bootstrap (Telegram-only)
dashboard.auth.bootstrap.enabled=${DASHBOARD_ENABLE_BOOTSTRAP:false}
dashboard.auth.admin.telegram-id=${DASHBOARD_ADMIN_TG_ID:0}
```

**Обязательные env при `DASHBOARD_ENABLE_BOOTSTRAP=true`:**
- `DASHBOARD_ADMIN_TG_ID` — Telegram user_id админа (узнать у `@userinfobot`).

## Ingestion (Redis Streams)

Данные пишутся в Redis Stream `stats:events`. Схема — `StatsEventPayload` (JSON в поле
`payload`). Типы событий: `bot_user.seen`, `export.started`, `export.completed`,
`export.failed`, `export.cancelled`, `export.bytes_measured`.

`StatsStreamConsumer` читает стрим consumer-group'ой `dashboard-writer`, делает
idempotent upsert через `ExportEventIngestionService` (UNIQUE `task_id`), XACK'ает.
Pending-list сообщений переживает рестарты java-bot — at-least-once гарантия.

## i18n

Язык UI хранится в `bot_users.language` — единый источник правды для бота и
дашборда. Смена через `POST /dashboard/api/me/settings/language` обновляет эту
колонку. Поддерживаются 10 языков (см. `docs/I18N.md`).

## Subscriptions

Страница `/dashboard/subscriptions`. Управление периодическими подписками. Видимость:
- USER видит свои подписки, может создавать/паузить/удалять
- ADMIN видит все подписки, может паузить/удалять, но НЕ создавать за других

### Форма создания (только USER)
- Chat ID — числовой ID чата из Telegram
- Period — 4 радио: Каждый день / Каждые 2 дня / Каждые 3 дня / Раз в неделю (соответствует 24/48/72/168 часов)
- Desired time — желаемое время МСК (UTC+3) в формате HH:MM
- Since — дата начала исторического окна (не старше одного периода, не в будущем)

### Таблица подписок
- Колонки: Chat, Period, Time MSK, Since, Status, Next run, Actions
- Next run рассчитывается локально: `lastSuccessAt + period` (или "Waiting for first run" если ни разу не запускалась)
- Actions: Pause/Resume/Delete (доступны в зависимости от status и роли)

### REST API

| Метод | URL | Что делает | RBAC |
|---|---|---|---|
| GET | `/dashboard/api/subscriptions[?userId=]` | List | USER: свои; ADMIN: все или фильтр |
| GET | `/dashboard/api/subscriptions/{id}` | Получить одну | USER: только свои |
| POST | `/dashboard/api/subscriptions` | Создать | Только USER, только для себя |
| PATCH | `/dashboard/api/subscriptions/{id}/pause` | Пауза | USER: свои; ADMIN: любые |
| PATCH | `/dashboard/api/subscriptions/{id}/resume` | Возобновить | USER: свои; ADMIN: любые |
| DELETE | `/dashboard/api/subscriptions/{id}` | Hard delete | USER: свои; ADMIN: любые |

### Коды ошибок

- 400: невалидные параметры (period_hours не из {24,48,72,168}; desired_time_msk не HH:MM; since_date в будущем или старше period)
- 401: USER без привязки к Telegram user_id
- 403: USER пытается открыть чужую подписку; ADMIN пытается POST
- 404: подписка не найдена
- 409: попытка создать вторую ACTIVE подписку

---

## Известные ограничения и планы

- Rollup-таблицы не реализованы — индексов достаточно на текущих объёмах
- Миграция SQLite → PostgreSQL: заменить dialect + docker-compose сервис; API/репозитории не меняются
- Audit log для write-операций UI: отложен до появления мутирующего UI
