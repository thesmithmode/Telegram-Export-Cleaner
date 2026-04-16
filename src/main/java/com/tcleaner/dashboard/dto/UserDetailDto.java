package com.tcleaner.dashboard.dto;

/**
 * Детальная карточка пользователя для страницы {@code /dashboard/user/{botUserId}}.
 */
public record UserDetailDto(
        long botUserId,
        String username,
        String displayName,
        int totalExports,
        long totalMessages,
        long totalBytes,
        String firstSeen,
        String lastSeen
) {}
