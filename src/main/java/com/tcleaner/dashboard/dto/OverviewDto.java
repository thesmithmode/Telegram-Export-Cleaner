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
) {}
