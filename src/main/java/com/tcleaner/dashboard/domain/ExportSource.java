package com.tcleaner.dashboard.domain;

/**
 * Источник экспорта — кто инициировал задачу.
 * {@link #BOT} — экспорт через Telegram-бота, {@link #API} — прямой вызов REST-API,
 * {@link #SUBSCRIPTION} — автоматический запуск через планировщик подписок.
 */
public enum ExportSource {
    BOT,
    API,
    SUBSCRIPTION
}
