package com.tcleaner.dashboard.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки Redis-стрима статистики. Читаются из ENV:
 * <ul>
 *   <li>{@code STATS_STREAM_KEY} — ключ стрима (default {@code stats:events})</li>
 *   <li>{@code STATS_STREAM_GROUP} — имя consumer group (default {@code dashboard-writer})</li>
 *   <li>{@code STATS_STREAM_CONSUMER} — имя конкретного consumer (default {@code java-bot-1})</li>
 *   <li>{@code STATS_STREAM_MAXLEN} — approximate trim (default 100000)</li>
 * </ul>
 *
 * @param key название стрима
 * @param group имя consumer group
 * @param consumer имя consumer внутри group
 * @param maxlen approximate MAXLEN при каждом XADD
 */
@ConfigurationProperties(prefix = "dashboard.stats.stream")
public record StatsStreamProperties(
        String key,
        String group,
        String consumer,
        long maxlen,
        boolean enabled
) {
    public StatsStreamProperties {
        if (key == null || key.isBlank()) {
            key = "stats:events";
        }
        if (group == null || group.isBlank()) {
            group = "dashboard-writer";
        }
        if (consumer == null || consumer.isBlank()) {
            consumer = "java-bot-1";
        }
        if (maxlen <= 0) {
            maxlen = 100_000L;
        }
    }
}
