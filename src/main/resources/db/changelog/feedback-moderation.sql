--liquibase formatted sql

--changeset app:014-feedback-moderation splitStatements:true endDelimiter:;
ALTER TABLE bot_users ADD COLUMN feedback_blocked BOOLEAN NOT NULL DEFAULT FALSE;

--rollback ALTER TABLE bot_users DROP COLUMN feedback_blocked;
