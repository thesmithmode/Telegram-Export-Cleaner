package com.tcleaner.dashboard.domain;

/**
 * Источник учётной записи дашборда. {@link #LOCAL} — заведён через env-bootstrap,
 * {@link #TELEGRAM} — резерв под будущий Telegram Login Widget (HMAC-SHA256).
 */
public enum AuthProvider {
    LOCAL,
    TELEGRAM
}
