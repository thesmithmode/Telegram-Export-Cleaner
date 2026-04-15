package com.tcleaner.dashboard.dto;

/**
 * Строка в таблице "топ пользователей" для дашборда.
 * Поля дублируют денорм-счётчики из {@code bot_users} — читаются одним SELECT без JOIN.
 */
public record UserStatsRow(
        long botUserId,
        String username,
        String displayName,
        int totalExports,
        long totalMessages,
        long totalBytes,
        String lastSeen
) {}
