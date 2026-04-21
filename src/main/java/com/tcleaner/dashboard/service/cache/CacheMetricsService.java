package com.tcleaner.dashboard.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.dto.CacheMetricsDto;
import com.tcleaner.dashboard.repository.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Читает снапшот статистики кэша Python-worker'а из Redis и обогащает топ-чаты
 * метаданными (title, chat_type) из JPA-репозитория. Без снапшота возвращает
 * {@link CacheMetricsDto#unavailable()} — UI покажет placeholder вместо падения.
 *
 * Источник: ключ {@code cache:stats:snapshot}, TTL 300с, пишет worker каждые ~60с.
 */
@Service
public class CacheMetricsService {

    private static final Logger log = LoggerFactory.getLogger(CacheMetricsService.class);
    public static final String SNAPSHOT_KEY = "cache:stats:snapshot";

    private final StringRedisTemplate redis;
    private final ChatRepository chatRepository;
    private final ObjectMapper objectMapper;

    public CacheMetricsService(StringRedisTemplate redis,
                               ChatRepository chatRepository,
                               ObjectMapper objectMapper) {
        this.redis = redis;
        this.chatRepository = chatRepository;
        this.objectMapper = objectMapper;
    }

    public CacheMetricsDto get() {
        String raw;
        try {
            raw = redis.opsForValue().get(SNAPSHOT_KEY);
        } catch (Exception e) {
            log.warn("Redis недоступен при чтении cache snapshot: {}", e.getMessage());
            return CacheMetricsDto.unavailable();
        }
        if (raw == null || raw.isBlank()) {
            return CacheMetricsDto.unavailable();
        }
        RawSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(raw, RawSnapshot.class);
        } catch (JsonProcessingException e) {
            log.warn("Невалидный JSON в {}: {}", SNAPSHOT_KEY, e.getOriginalMessage());
            return CacheMetricsDto.unavailable();
        }
        return buildDto(snapshot);
    }

    private CacheMetricsDto buildDto(RawSnapshot s) {
        List<CacheMetricsDto.ChatCacheEntry> enrichedTop = new ArrayList<>();
        Map<String, CacheMetricsDto.ChatTypeSegment> segmentation = new HashMap<>();

        if (s.top_chats != null) {
            for (RawChat rc : s.top_chats) {
                ChatMeta meta = resolveChatMeta(rc.chat_id, rc.topic_id);
                enrichedTop.add(new CacheMetricsDto.ChatCacheEntry(
                        rc.chat_id,
                        rc.topic_id == 0 ? null : rc.topic_id,
                        meta.title(),
                        meta.username(),
                        meta.chatType(),
                        rc.msg_count,
                        rc.size_bytes,
                        rc.pct,
                        rc.last_accessed));

                String segKey = meta.chatType() != null ? meta.chatType() : "unknown";
                segmentation.merge(segKey,
                        new CacheMetricsDto.ChatTypeSegment(1, rc.size_bytes, rc.msg_count),
                        (existing, newVal) -> new CacheMetricsDto.ChatTypeSegment(
                                existing.chatCount() + newVal.chatCount(),
                                existing.sizeBytes() + newVal.sizeBytes(),
                                existing.msgCount() + newVal.msgCount()));
            }
        }

        List<CacheMetricsDto.HeatmapBucket> heatmap = new ArrayList<>();
        if (s.heatmap != null) {
            s.heatmap.forEach((bucket, data) -> heatmap.add(new CacheMetricsDto.HeatmapBucket(
                    bucket,
                    ((Number) data.getOrDefault("chat_count", 0L)).longValue(),
                    ((Number) data.getOrDefault("size_bytes", 0L)).longValue())));
        }

        return new CacheMetricsDto(
                true,
                s.used_bytes,
                s.limit_bytes,
                s.pct,
                s.total_chats,
                s.total_messages,
                (long) s.generated_at,
                enrichedTop,
                heatmap,
                segmentation);
    }

    private ChatMeta resolveChatMeta(long chatId, Integer topicId) {
        Integer tid = (topicId == null || topicId == 0) ? null : topicId;
        Chat dbChat = chatRepository
                .findByCanonicalChatIdAndTopicId(String.valueOf(chatId), tid)
                .orElse(null);
        String dbTitle = dbChat != null ? dbChat.getChatTitle() : null;
        String dbType = dbChat != null ? dbChat.getChatType() : null;

        // canonical:<id> может хранить numeric-id (fallback input→canonical) — отсекаем.
        String username = readRedisKey("canonical:" + chatId);
        if (username != null && (username.startsWith("-") || username.chars().allMatch(Character::isDigit))) {
            username = null;
        }
        String redisType = readRedisKey("canonical:" + chatId + ":type");
        String finalType = (redisType != null && !redisType.isBlank()) ? redisType : dbType;
        return new ChatMeta(dbTitle, finalType, username);
    }

    private String readRedisKey(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return (v != null && !v.isBlank()) ? v : null;
        } catch (Exception e) {
            // DEBUG, а не WARN: вызывается в цикле по каждому чату — падение Redis
            // даст тысячи WARN. Недоступность Redis уже логируется один раз в get().
            log.debug("Redis read failed for key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private record ChatMeta(String title, String chatType, String username) {}

    // ── wire-level records (snake_case, matches Python payload) ──────────────

    static class RawSnapshot {
        public long used_bytes;
        public long limit_bytes;
        public double pct;
        public long total_chats;
        public long total_messages;
        public double generated_at;
        public List<RawChat> top_chats;
        public Map<String, Map<String, Object>> heatmap;
    }

    static class RawChat {
        public long chat_id;
        public int topic_id;
        public long msg_count;
        public long size_bytes;
        public double last_accessed;
        public double pct;
    }
}
