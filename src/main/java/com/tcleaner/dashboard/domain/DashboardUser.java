package com.tcleaner.dashboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * Учётная запись веб-UI дашборда.
 * {@code telegramId} — Telegram user_id, основной идентификатор при логине (Telegram Login Widget).
 * {@code botUserId} связывает USER-роль с записью в {@code bot_users} (личный кабинет);
 * для ADMIN — {@code null}. {@code passwordHash} оставлен NOT NULL для совместимости,
 * новые записи содержат пустую строку — пароли не используются.
 */
@Entity
@Table(name = "dashboard_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "passwordHash")
public class DashboardUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private DashboardRole role;

    @Column(name = "telegram_id", unique = true)
    private Long telegramId;

    @Column(name = "bot_user_id")
    private Long botUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private AuthProvider provider;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;
}
