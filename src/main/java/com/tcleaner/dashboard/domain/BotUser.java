package com.tcleaner.dashboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * Агрегат по пользователю Telegram-бота.
 * Счётчики {@code totalExports/Messages/Bytes} денормализованы —
 * обновляются ingestion-сервисом вместе с insert в {@link ExportEvent}.
 * PK — Telegram user_id; автоинкремент не используем.
 */
@Entity
@Table(name = "bot_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class BotUser {

    @Id
    @Column(name = "bot_user_id", nullable = false, updatable = false)
    private Long botUserId;

    @Column(name = "username")
    private String username;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Column(name = "total_exports", nullable = false)
    private int totalExports;

    @Column(name = "total_messages", nullable = false)
    private long totalMessages;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;
}
