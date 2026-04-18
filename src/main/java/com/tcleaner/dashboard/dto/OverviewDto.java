package com.tcleaner.dashboard.dto;

import java.util.List;
import java.util.Map;

/**
 * Агрегат для главной страницы дашборда.
 * {@code topUsers} и {@code topChats} — топ-10 по {@code totalExports};
 * {@code statusBreakdown} — карта {@code status → count} за выбранный период.
 */
public record OverviewDto(
        long totalExports,
        long totalMessages,
        long totalBytes,
        long totalUsers,
        List<UserStatsRow> topUsers,
        List<ChatStatsRow> topChats,
        Map<String, Long> statusBreakdown
) {
    /** Пустой DTO — для /api/me/overview когда у пользователя ещё нет данных. */
    public static OverviewDto empty() {
        return new OverviewDto(0L, 0L, 0L, 0L, List.of(), List.of(), Map.of());
    }
}
