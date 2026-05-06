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
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

        if (s.top_chats != null && !s.top_chats.isEmpty()) {
            // batch DB lookup: one query for all chatIds
            List<String> chatIdStrs = s.top_chats.stream()
                    .map(rc -> String.valueOf(rc.chat_id))
                    .distinct()
                    .collect(Collectors.toList());
            Map<String, Chat> dbChatMap = chatRepository.findAllByCanonicalChatIdIn(chatIdStrs)
                    .stream()
                    .collect(Collectors.toMap(
                            c -> c.getCanonicalChatId() + ":" + c.getTopicId(),
                            c -> c,
                            (a, b) -> a));

            // batch Redis mget: canonical:<id> and canonical:<id>:type for each chat
            List<String> redisKeys = s.top_chats.stream()
                    .flatMap(rc -> Stream.of("canonical:" + rc.chat_id, "canonical:" + rc.chat_id + ":type"))
                    .collect(Collectors.toList());
            List<String> redisVals;
            try {
                redisVals = redis.opsForValue().multiGet(redisKeys);
            } catch (Exception e) {
                log.debug("Redis multiGet failed: {}", e.getMessage());
                redisVals = null;
            }
            // Lettuce/Jedis возвращают list с size == request (null для missing key); но
            // при partial response / mock-defaults размер может расходиться — degrade
            // в "redis недоступен" вместо IOOBE.
            boolean redisOk = redisVals != null && redisVals.size() == redisKeys.size();

            for (int i = 0; i < s.top_chats.size(); i++) {
                RawChat rc = s.top_chats.get(i);
                Integer tid = (rc.topic_id == 0) ? null : rc.topic_id;
                Chat dbChat = dbChatMap.get(rc.chat_id + ":" + tid);

                String username = redisOk ? redisVals.get(i * 2) : null;
                if (username != null && (username.startsWith("-") || username.chars().allMatch(Character::isDigit))) {
                    username = null;
                }
                String redisType = redisOk ? redisVals.get(i * 2 + 1) : null;
                String dbType = dbChat != null ? dbChat.getChatType() : null;
                String finalType = (redisType != null && !redisType.isBlank()) ? redisType : dbType;
                String title = dbChat != null ? dbChat.getChatTitle() : null;

                enrichedTop.add(new CacheMetricsDto.ChatCacheEntry(
                        rc.chat_id,
                        tid,
                        title,
                        username,
                        finalType,
                        rc.msg_count,
                        rc.size_bytes,
                        rc.pct,
                        rc.last_accessed));

                String segKey = finalType != null ? finalType : "unknown";
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
