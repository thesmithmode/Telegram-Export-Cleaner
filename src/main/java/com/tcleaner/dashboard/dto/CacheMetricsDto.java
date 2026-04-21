package com.tcleaner.dashboard.dto;

import java.util.List;
import java.util.Map;

/**
 * Снапшот заполненности кэша сообщений (Python worker SQLite) для админ-дашборда.
 * Собирается в Redis worker'ом раз в минуту, Java читает на запрос и обогащает
 * top-чаты метаданными из {@code chats} таблицы (title, chat_type).
 *
 * @param available {@code false} если снапшот ещё не опубликован worker'ом
 *                  (первая минута после старта / worker выключен) — UI показывает
 *                  placeholder вместо пустых нулей.
 */
public record CacheMetricsDto(
        boolean available,
        long usedBytes,
        long limitBytes,
        double pct,
        long totalChats,
        long totalMessages,
        long generatedAt,
        List<ChatCacheEntry> topChats,
        List<HeatmapBucket> heatmap,
        Map<String, ChatTypeSegment> chatTypeSegmentation) {

    public record ChatCacheEntry(
            long chatId,
            Integer topicId,
            String title,
            String chatType,
            long msgCount,
            long sizeBytes,
            double pct,
            double lastAccessed) {}

    public record HeatmapBucket(
            String bucket,
            long chatCount,
            long sizeBytes) {}

    public record ChatTypeSegment(
            long chatCount,
            long sizeBytes,
            long msgCount) {}

    public static CacheMetricsDto unavailable() {
        return new CacheMetricsDto(
                false, 0L, 0L, 0.0, 0L, 0L, 0L,
                List.of(), List.of(), Map.of());
    }
}
