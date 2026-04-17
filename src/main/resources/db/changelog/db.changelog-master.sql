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

-- =============================================================================
-- 002: Telegram Login Widget support.
-- Добавляет telegram_id для идентификации по Telegram ID вместо пароля.
-- password_hash остаётся NOT NULL для обратной совместимости — новые записи
-- получают пустую строку; old credentials не используются.
-- =============================================================================

--changeset app:002-telegram-login splitStatements:true endDelimiter:;

ALTER TABLE dashboard_users ADD COLUMN telegram_id BIGINT;

CREATE UNIQUE INDEX idx_dashboard_users_telegram_id
    ON dashboard_users (telegram_id)
    WHERE telegram_id IS NOT NULL;
