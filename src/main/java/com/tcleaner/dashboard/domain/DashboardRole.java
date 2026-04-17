package com.tcleaner.dashboard.domain;

/**
 * Роль пользователя веб-дашборда. ADMIN видит статистику всех, USER — только свою
 * (через связь {@code dashboard_users.bot_user_id → bot_users.bot_user_id}).
 */
public enum DashboardRole {
    ADMIN,
    USER;

    public String authority() {
        return "ROLE_" + name();
    }
}
