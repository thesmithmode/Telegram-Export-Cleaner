# Web Dashboard

Веб-UI и JSON API для просмотра статистики бота. Живёт внутри того же Spring Boot процесса (`telegram-cleaner`) под `/dashboard/**`. Хранилище — SQLite в bind mount `${HOST_DATA_PATH}/dashboard:/data/stats`. Миграции — Liquibase, единый changelog `src/main/resources/db/changelog/db.changelog-master.sql`.

```
java-bot (:8080)
├── /api/**             REST экспорта (X-API-Key, internal-only)
└── /dashboard/**       Thymeleaf SSR + JSON API (cookie session)
        │
        ▼
   SQLite (bind mount)
   ├── bot_users           агрегаты по Telegram user_id
   ├── chats               таргеты экспорта
   ├── export_events       источник правды (UNIQUE task_id)
   ├── chat_subscriptions  периодические подписки
   └── dashboard_users     веб-логины (роли ADMIN/USER)

Ingestion: Redis Stream stats:events → StatsStreamConsumer → SQLite
```

## Security

Три `SecurityFilterChain` (см. `*SecurityConfig`):
- `ActuatorSecurityConfig` (HIGHEST): `/actuator/health` permitAll, остальное denyAll.
- `dashboardFilterChain` (Order 1): `/dashboard/**`, CSRF on, stateful. Auth — только Telegram Mini App (никаких form-login/BCrypt). Admin-страницы — `hasRole('ADMIN')`.
- `apiFilterChain` (Order 2): `/api/**`, STATELESS, CSRF off, `ApiKeyFilter` (исключение — `/api/health`).

RBAC поверх — `BotUserAccessPolicy.effectiveUserId()`.

**Anti-IDOR:** USER, обращающийся к чужой подписке/ресурсу, получает `404`, не `403` — статус не должен раскрывать существование ID.

**`tg_uid` cookie (identity-guard):** JS сравнивает с `Telegram.WebApp.initDataUnsafe.user.id`; mismatch → принудительный re-login. Закрывает переиспользование WebView в Telegram attachment menu.

**`EnvUserBootstrap`:** активен только при `DASHBOARD_ENABLE_BOOTSTRAP=true`. По `DASHBOARD_ADMIN_TG_ID` создаёт/апгрейдит admin'а. Дефолт `false` — иначе при рестарте бы перезатирал ручные изменения роли.

## Periodic subscriptions

Регулярный экспорт чата с периодом 24/48/72/168 ч.

- **Уникальность активной:** на пользователя одна подписка ACTIVE-или-PAUSED (partial unique index `uk_subscriptions_one_active_per_user`). При создании новой PAUSED auto-архивируется (смена чата без ручного удаления).
- **Окно запуска:** открывается за 30 мин до `desired_time_msk` и остаётся открытым до конца суток (catch-up если воркер был занят). Повтор в тех же сутках исключён через `last_success_at + period - 30min`.
- **Confirmation:** раз в 7 дней бот спрашивает актуальность; нет ответа 48 ч → ARCHIVED.
- **Auto-pause:** 2 подряд failure → PAUSED.
- **Anti-duplicate:** `recordRunStarted` зовётся ДО `enqueueSubscription` — иначе крэш между шагами привёл бы к повторному выстрелу подписки в следующем тике.
- **UTC range:** Java усекает `Instant.now()` до секунд (`truncatedTo(SECONDS)`), форматирует `ISO_LOCAL_DATE_TIME` в UTC. Python worker трактует naive datetime как UTC; нс или МСК → DLQ.

## REST API

JSON под `/dashboard/api/**`. Cookie session (Telegram Mini App login). USER видит только свои данные; чужой `userId` → 404.

| Path | Метод | Доступ |
|---|---|---|
| `/me` | GET | auth |
| `/me/{overview,chats,events,timeseries,status-breakdown}` | GET | USER |
| `/me/settings/language` | POST | USER |
| `/stats/{overview,users,chats,timeseries,status-breakdown,recent}` | GET | ADMIN |
| `/stats/user/{botUserId}` | GET | ADMIN |
| `/admin/cache-metrics` | GET | ADMIN |
| `/subscriptions` | GET | USER свои / ADMIN все |
| `/subscriptions` | POST | только USER (для себя) |
| `/subscriptions/{id}/{pause,resume}` | PATCH | USER свои / ADMIN любые |
| `/subscriptions/{id}` | DELETE | USER свои / ADMIN любые |

**Auth:** `POST /dashboard/login/telegram` — initData → HMAC verify → session + `tg_uid`.

**Query (общие):** `period=day|week|month|year|all|custom`, `from`, `to`, `userId` (ADMIN). Granularity auto: ≤31д→DAY, ≤365д→WEEK, иначе MONTH.

**Subscription error codes:** 400 невалид params · 401 USER без `botUserId` · 404 не найдена либо чужая · 409 попытка POST второй ACTIVE.

## Ingestion (Redis Streams)

Stream `stats:events`, JSON в поле `payload` (`StatsEventPayload`). Consumer-group `dashboard-writer`. Идемпотентность — UNIQUE `task_id` в `export_events`.

ACK-стратегия (`StatsStreamConsumer`): poison (битый JSON, пустой payload) → ACK; transient (DB/Redis/downstream) → no ACK → at-least-once retry. Иначе один битый event блокировал бы PEL навсегда.

## Frontend

SSR (Thymeleaf, `templates/dashboard/`) рендерит каркас, данные и Chart.js — через fetch к JSON API.

Chart.js не в репо (`.gitignore`). Dockerfile скачивает `chart.min.js` (`ARG CHART_JS_VERSION=4.4.1`) при сборке образа.

## Config

```properties
spring.datasource.url=jdbc:sqlite:${DASHBOARD_DB_PATH:./dashboard.db}?date_class=text
spring.jpa.hibernate.ddl-auto=none
server.forward-headers-strategy=NATIVE
dashboard.auth.bootstrap.enabled=${DASHBOARD_ENABLE_BOOTSTRAP:false}
dashboard.auth.admin.telegram-id=${DASHBOARD_ADMIN_TG_ID:0}
```

## i18n

Язык UI — `bot_users.language` (единый источник правды бот ↔ дашборд). Смена через `POST /dashboard/api/me/settings/language`. 10 языков, см. `I18N.md`.

## Future

- Rollup-таблиц нет — индексов хватает.
- SQLite → PostgreSQL: заменить dialect + compose-сервис; API/репозитории не меняются.
- Audit log для write-операций — отложен до появления мутирующего UI.
