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
        Map<String, Long> statusBreakdown,
        /** Изменение totalExports vs предыдущий период той же длины, %. null если нет данных. */
        Double deltaExports,
        /** Изменение totalMessages, %. null если нет данных. */
        Double deltaMessages,
        /** Изменение totalBytes, %. null если нет данных. */
        Double deltaBytes,
        /** Изменение totalUsers vs предыдущий период той же длины, %. null если нет данных. */
        Double deltaUsers
) {
    /** Пустой DTO — для /api/me/overview когда у пользователя ещё нет данных. */
    public static OverviewDto empty() {
        return new OverviewDto(0L, 0L, 0L, 0L, List.of(), List.of(), Map.of(),
                null, null, null, null);
    }
}
