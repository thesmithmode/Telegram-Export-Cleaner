--liquibase formatted sql

-- =============================================================================
-- 001: Initial dashboard schema (statistics & auth)
-- See docs/DASHBOARD.md for the rationale and read/write paths.
--
-- SQLite-specific notes:
--   * INTEGER PRIMARY KEY is the rowid alias and auto-increments.
--   * SQLite uses dynamic typing; BIGINT/TEXT/TIMESTAMP are affinity hints only.
--   * Foreign keys are advisory unless `PRAGMA foreign_keys = ON` (set by Hikari
--     `connection-init-sql` in application.properties).
--   * Timestamps are stored as ISO-8601 TEXT (UTC) for portability and
--     human-readable backups.
-- =============================================================================

--changeset app:001-init-dashboard-schema splitStatements:true endDelimiter:;
--validCheckSum 9:f2a8e79be9ea0baed44724cd2c9026a1

-- -----------------------------------------------------------------------------
-- bot_users — Telegram users who interacted with the bot.
-- Denormalized counters (total_*) accelerate "top users" queries; they are
-- maintained by the ingestion service alongside event inserts.
-- -----------------------------------------------------------------------------
CREATE TABLE bot_users (
    bot_user_id     BIGINT PRIMARY KEY,
    username        TEXT,
    display_name    TEXT,
    first_seen      TEXT NOT NULL,
    last_seen       TEXT NOT NULL,
    total_exports   INTEGER NOT NULL DEFAULT 0,
    total_messages  BIGINT  NOT NULL DEFAULT 0,
    total_bytes     BIGINT  NOT NULL DEFAULT 0
);

CREATE INDEX idx_bot_users_last_seen ON bot_users (last_seen DESC);
CREATE INDEX idx_bot_users_username  ON bot_users (username);

-- -----------------------------------------------------------------------------
-- chats — export targets (channel/group/private/forum topic).
-- canonical_chat_id is the resolved numeric id from Pyrogram (or the raw input
-- if resolution failed); topic_id is non-null only for forum topics.
-- COALESCE(topic_id, -1) collapses NULL into the unique index key.
-- -----------------------------------------------------------------------------
CREATE TABLE chats (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    canonical_chat_id   TEXT NOT NULL,
    chat_id_raw         TEXT NOT NULL,
    topic_id            INTEGER,
    chat_title          TEXT,
    chat_type           TEXT,
    first_seen          TEXT NOT NULL,
    last_seen           TEXT NOT NULL
);

CREATE UNIQUE INDEX uk_chats_canonical_topic
    ON chats (canonical_chat_id, COALESCE(topic_id, -1));

-- -----------------------------------------------------------------------------
-- export_events — single source of truth for every export attempt.
-- task_id is the idempotency key (UUID from ExportJobProducer).
-- Late-arriving completion events upsert by task_id (see ingestion service).
-- -----------------------------------------------------------------------------
CREATE TABLE export_events (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id           TEXT NOT NULL UNIQUE,
    bot_user_id       BIGINT NOT NULL,
    chat_ref_id       INTEGER NOT NULL,
    started_at        TEXT NOT NULL,
    finished_at       TEXT,
    status            TEXT NOT NULL,
    messages_count    BIGINT,
    bytes_count       BIGINT,
    from_date         TEXT,
    to_date           TEXT,
    keywords          TEXT,
    exclude_keywords  TEXT,
    source            TEXT NOT NULL DEFAULT 'bot',
    error_message     TEXT,
    created_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (bot_user_id) REFERENCES bot_users (bot_user_id),
    FOREIGN KEY (chat_ref_id) REFERENCES chats (id)
);

CREATE INDEX idx_events_user_started ON export_events (bot_user_id, started_at DESC);
CREATE INDEX idx_events_chat_started ON export_events (chat_ref_id, started_at DESC);
CREATE INDEX idx_events_started      ON export_events (started_at DESC);
CREATE INDEX idx_events_status       ON export_events (status);

-- -----------------------------------------------------------------------------
-- dashboard_users — login accounts for the web UI.
-- bot_user_id binds a USER role to a Telegram user_id (личный кабинет).
-- provider stays 'LOCAL' for env-bootstrapped users; future Telegram Login
-- Widget integration uses 'TELEGRAM' without schema changes.
-- -----------------------------------------------------------------------------
CREATE TABLE dashboard_users (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    username        TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    role            TEXT NOT NULL,
    bot_user_id     BIGINT,
    provider        TEXT NOT NULL DEFAULT 'LOCAL',
    created_at      TEXT NOT NULL,
    last_login_at   TEXT,
    enabled         INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_dashboard_users_bot_user_id ON dashboard_users (bot_user_id);

--rollback DROP INDEX IF EXISTS idx_dashboard_users_bot_user_id;
--rollback DROP INDEX IF EXISTS idx_events_status;
--rollback DROP INDEX IF EXISTS idx_events_started;
--rollback DROP INDEX IF EXISTS idx_events_chat_started;
--rollback DROP INDEX IF EXISTS idx_events_user_started;
--rollback DROP TABLE IF EXISTS export_events;
--rollback DROP INDEX IF EXISTS uk_chats_canonical_topic;
--rollback DROP TABLE IF EXISTS chats;
--rollback DROP INDEX IF EXISTS idx_bot_users_username;
--rollback DROP INDEX IF EXISTS idx_bot_users_last_seen;
--rollback DROP TABLE IF EXISTS bot_users;
--rollback DROP TABLE IF EXISTS dashboard_users;

-- =============================================================================
-- 002: Telegram Login Widget support.
-- Добавляет telegram_id для идентификации по Telegram ID вместо пароля.
-- password_hash остаётся NOT NULL для обратной совместимости — новые записи
-- получают пустую строку; old credentials не используются.
-- =============================================================================

--changeset app:002-telegram-login splitStatements:true endDelimiter:;
--validCheckSum 9:eb4454a2498905dc7ed47cd0daaefc52

ALTER TABLE dashboard_users ADD COLUMN telegram_id BIGINT;

CREATE UNIQUE INDEX idx_dashboard_users_telegram_id
    ON dashboard_users (telegram_id)
    WHERE telegram_id IS NOT NULL;

--rollback DROP INDEX IF EXISTS idx_dashboard_users_telegram_id;
-- NOTE: SQLite не поддерживает DROP COLUMN — откат столбца telegram_id невозможен без пересоздания таблицы.

-- =============================================================================
-- 003: Очистка orphan-записей dashboard_users без telegram_id.
-- Остатки прошлых экспериментов — пустой telegram_id = не привязан к TG-аккаунту.
-- =============================================================================

--changeset app:003-cleanup-orphaned-dashboard-users splitStatements:true endDelimiter:;
--validCheckSum 9:cce5698e3138e2d4ba8c68c44e470fda

DELETE FROM dashboard_users WHERE telegram_id IS NULL;

--rollback -- NOTE: откат changeset 003 невозможен — удалённые строки без резервной копии не восстановить.

-- =============================================================================
-- 004: Предпочитаемый язык UI пользователя (бот + дашборд).
-- NULL означает "ещё не выбран" — бот при /start покажет клавиатуру выбора
-- языка, дашборд до выбора резолвит по Accept-Language (см. BotUserLocaleResolver).
-- Поддерживаемые коды: ru, en, es, pt-BR, de, tr, id, fa, ar, zh
-- (см. com.tcleaner.core.BotLanguage).
-- =============================================================================

--changeset app:004-bot-users-language splitStatements:true endDelimiter:;
--validCheckSum 9:ba87f4f4aa953e11bdfac671240bca8f

ALTER TABLE bot_users ADD COLUMN language VARCHAR(5);

--rollback -- NOTE: SQLite не поддерживает DROP COLUMN — откат столбца language невозможен без пересоздания таблицы.

-- =============================================================================
-- 005: Периодические подписки пользователей на экспорт чатов.
-- Пользователь подписывается на регулярный экспорт конкретного чата с
-- заданным периодом (24/48/72/168 ч) и желаемым временем доставки в МСК (UTC+3).
--
-- Жизненный цикл статуса: ACTIVE → PAUSED → ARCHIVED.
--   ACTIVE  — подписка работает, SubscriptionScheduler включает её в выборку.
--   PAUSED  — временно приостановлена пользователем, легко восстановить.
--   ARCHIVED — деактивирована (вручную или после 48 ч без подтверждения);
--              новые итерации не запускаются, запись сохраняется для истории.
--
-- Alpha-ограничение: не более одной ACTIVE-подписки на пользователя.
-- Обеспечивается partial unique index uk_subscriptions_one_active_per_user.
-- При создании второй подписки сервис обязан перевести предыдущую в ARCHIVED.
--
-- confirm_sent_at — момент отправки confirmation-запроса; NULL означает, что
-- подтверждение не требуется (пользователь только что создал или подтвердил).
-- ConfirmationScheduler переводит подписки в ARCHIVED через 48 ч после отправки
-- confirm_sent_at, если last_confirm_at не обновился.
-- =============================================================================

--changeset app:005-chat-subscriptions splitStatements:true endDelimiter:;
--validCheckSum 9:5698e3138e2d4ba8c68c44e470fda569
--validCheckSum 9:f065391456d8bb485ed15f958ed307d0
--validCheckSum 9:e9aa6424a854c7f0cea0ef2f148104ee

CREATE TABLE chat_subscriptions (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    bot_user_id             BIGINT  NOT NULL,
    chat_ref_id             INTEGER NOT NULL,
    period_hours            INTEGER NOT NULL
                                CHECK (period_hours IN (24, 48, 72, 168)),
    desired_time_msk        TEXT    NOT NULL,
    since_date              TEXT    NOT NULL,
    status                  TEXT    NOT NULL DEFAULT 'ACTIVE'
                                CHECK (status IN ('ACTIVE', 'PAUSED', 'ARCHIVED')),
    last_run_at             TEXT,
    last_success_at         TEXT,
    last_failure_at         TEXT,
    consecutive_failures    INTEGER NOT NULL DEFAULT 0,
    last_confirm_at         TEXT    NOT NULL,
    confirm_sent_at         TEXT,
    created_at              TEXT    NOT NULL,
    updated_at              TEXT    NOT NULL,
    FOREIGN KEY (bot_user_id) REFERENCES bot_users (bot_user_id),
    FOREIGN KEY (chat_ref_id) REFERENCES chats (id)
);

-- SQLite 3.8.0+ поддерживает WHERE в индексах. Уникальность обеспечена индексом 007 + сервисом.
CREATE INDEX idx_subscriptions_status_last_run
    ON chat_subscriptions (status, last_run_at);

CREATE INDEX idx_subscriptions_user
    ON chat_subscriptions (bot_user_id);

--rollback DROP INDEX IF EXISTS idx_subscriptions_user;
--rollback DROP INDEX IF EXISTS idx_subscriptions_status_last_run;
--rollback DROP TABLE IF EXISTS chat_subscriptions;

-- =============================================================================
-- 006: Поле subscription_id в export_events для аудита итераций подписки.
-- =============================================================================

--changeset app:006-export-events-subscription-id splitStatements:true endDelimiter:;
--validCheckSum 9:a4c2e5698e3138e2d4ba8c68c44e470f
--validCheckSum 9:05243c9c30c22ab0e2ff307374f3e0d5
--validCheckSum 9:c503a602a0bb934e2f9ec7321279ae5f

ALTER TABLE export_events ADD COLUMN subscription_id INTEGER;

CREATE INDEX idx_events_subscription
    ON export_events (subscription_id);

--rollback DROP INDEX IF EXISTS idx_events_subscription;
-- NOTE: SQLite не поддерживает DROP COLUMN — откат столбца subscription_id невозможен без пересоздания таблицы.

-- =============================================================================
-- 007: Уникальный partial index — не более одной ACTIVE-подписки на пользователя.
-- SQLite 3.8.0+ поддерживает WHERE в индексах. Прод: 3.46.1.
-- =============================================================================

--changeset app:007-subscriptions-unique-active-index splitStatements:true endDelimiter:;

CREATE UNIQUE INDEX uk_subscriptions_one_active_per_user
    ON chat_subscriptions (bot_user_id)
    WHERE status = 'ACTIVE';

--rollback DROP INDEX IF EXISTS uk_subscriptions_one_active_per_user;

-- =============================================================================
-- 008: FK dashboard_users.bot_user_id → bot_users(bot_user_id) ON DELETE SET NULL.
-- SQLite не поддерживает ADD CONSTRAINT, поэтому пересоздаём таблицу.
-- =============================================================================

--changeset app:008-fk-dashboard-users-bot-user-id splitStatements:true endDelimiter:;

DELETE FROM dashboard_users
WHERE bot_user_id IS NOT NULL
  AND bot_user_id NOT IN (SELECT bot_user_id FROM bot_users);

CREATE TABLE dashboard_users_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    username        TEXT    NOT NULL UNIQUE,
    password_hash   TEXT    NOT NULL,
    role            TEXT    NOT NULL,
    bot_user_id     BIGINT,
    provider        TEXT    NOT NULL DEFAULT 'LOCAL',
    created_at      TEXT    NOT NULL,
    last_login_at   TEXT,
    enabled         INTEGER NOT NULL DEFAULT 1,
    telegram_id     BIGINT,
    FOREIGN KEY (bot_user_id) REFERENCES bot_users (bot_user_id) ON DELETE SET NULL
);

INSERT INTO dashboard_users_new
    (id, username, password_hash, role, bot_user_id, provider, created_at, last_login_at, enabled, telegram_id)
SELECT id, username, password_hash, role, bot_user_id, provider, created_at, last_login_at, enabled, telegram_id
FROM dashboard_users;

DROP TABLE dashboard_users;

ALTER TABLE dashboard_users_new RENAME TO dashboard_users;

CREATE INDEX idx_dashboard_users_bot_user_id ON dashboard_users (bot_user_id);

CREATE UNIQUE INDEX idx_dashboard_users_telegram_id
    ON dashboard_users (telegram_id)
    WHERE telegram_id IS NOT NULL;

--rollback DROP INDEX IF EXISTS idx_dashboard_users_telegram_id;
--rollback DROP INDEX IF EXISTS idx_dashboard_users_bot_user_id;
--rollback ALTER TABLE dashboard_users RENAME TO dashboard_users_new;
--rollback CREATE TABLE dashboard_users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, role TEXT NOT NULL, bot_user_id BIGINT, provider TEXT NOT NULL DEFAULT 'LOCAL', created_at TEXT NOT NULL, last_login_at TEXT, enabled INTEGER NOT NULL DEFAULT 1, telegram_id BIGINT);
--rollback INSERT INTO dashboard_users (id, username, password_hash, role, bot_user_id, provider, created_at, last_login_at, enabled, telegram_id) SELECT id, username, password_hash, role, bot_user_id, provider, created_at, last_login_at, enabled, telegram_id FROM dashboard_users_new;
--rollback DROP TABLE dashboard_users_new;
--rollback CREATE INDEX idx_dashboard_users_bot_user_id ON dashboard_users (bot_user_id);
--rollback CREATE UNIQUE INDEX idx_dashboard_users_telegram_id ON dashboard_users (telegram_id) WHERE telegram_id IS NOT NULL;

-- =============================================================================
-- 009: @Version на ChatSubscription для optimistic lock.
-- =============================================================================

--changeset app:009-chat-subscriptions-version splitStatements:true endDelimiter:;

ALTER TABLE chat_subscriptions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

--rollback ALTER TABLE chat_subscriptions DROP COLUMN version;

-- =============================================================================
-- 010: Recompute bot_users.total_exports — исключить CANCELLED.
-- Поведение инкремента изменилось (см. ExportEventIngestionService): отмена
-- больше не считается завершённым экспортом. Приводим существующие счётчики
-- в соответствие, иначе дашборд показывает завышенные значения у пользователей
-- с историей CANCELLED-событий до этого изменения.
-- =============================================================================

--changeset app:010-recompute-total-exports splitStatements:true endDelimiter:;

UPDATE bot_users
SET total_exports = (
    SELECT COUNT(*)
    FROM export_events
    WHERE export_events.bot_user_id = bot_users.bot_user_id
      AND export_events.status IN ('COMPLETED', 'FAILED')
);

--rollback -- Recompute идемпотентен: rollback пересчитывает по старому правилу (включая CANCELLED).
--rollback UPDATE bot_users SET total_exports = (SELECT COUNT(*) FROM export_events WHERE export_events.bot_user_id = bot_users.bot_user_id AND export_events.status IN ('COMPLETED', 'FAILED', 'CANCELLED'));
