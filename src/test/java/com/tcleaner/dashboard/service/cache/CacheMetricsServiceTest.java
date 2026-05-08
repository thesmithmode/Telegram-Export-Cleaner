package com.tcleaner.dashboard.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.dto.CacheMetricsDto;
import com.tcleaner.dashboard.repository.ChatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты парсинга payload + обогащения ChatRepository. Без Spring context.
 */
@DisplayName("CacheMetricsService")
class CacheMetricsServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ChatRepository chatRepo;
    private CacheMetricsService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        chatRepo = mock(ChatRepository.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new CacheMetricsService(redis, chatRepo, new ObjectMapper());
    }

    @Test
    @DisplayName("нет снапшота → available=false")
    void missingSnapshotUnavailable() {
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(null);

        CacheMetricsDto dto = service.get();

        assertThat(dto.available()).isFalse();
        assertThat(dto.topChats()).isEmpty();
        assertThat(dto.usedBytes()).isZero();
    }

    @Test
    @DisplayName("битый JSON → available=false, без исключения")
    void corruptJson() {
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn("{not-json");

        CacheMetricsDto dto = service.get();
        assertThat(dto.available()).isFalse();
    }

    @Test
    @DisplayName("Redis-ошибка → available=false (fallback)")
    void redisFailure() {
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY))
                .thenThrow(new RuntimeException("connection refused"));

        CacheMetricsDto dto = service.get();
        assertThat(dto.available()).isFalse();
    }

    @Test
    @DisplayName("валидный снапшот + enrichment из ChatRepository")
    void enrichedFromRepository() {
        String json = """
                {
                  "used_bytes": 1500,
                  "limit_bytes": 10000,
                  "pct": 15.0,
                  "total_chats": 2,
                  "total_messages": 30,
                  "generated_at": 1700000000.5,
                  "top_chats": [
                    {"chat_id": -1001, "topic_id": 0, "msg_count": 20,
                     "size_bytes": 1000, "last_accessed": 1700000000.0, "pct": 66.67},
                    {"chat_id": -1002, "topic_id": 0, "msg_count": 10,
                     "size_bytes": 500, "last_accessed": 1699000000.0, "pct": 33.33}
                  ],
                  "heatmap": {
                    "hot": {"chat_count": 1, "size_bytes": 1000},
                    "warm": {"chat_count": 1, "size_bytes": 500},
                    "cold": {"chat_count": 0, "size_bytes": 0}
                  }
                }
                """;
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
        when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of(
                Chat.builder().canonicalChatId("-1001").topicId(null)
                        .chatTitle("Group A").chatType("supergroup").build(),
                Chat.builder().canonicalChatId("-1002").topicId(null)
                        .chatTitle("Channel B").chatType("channel").build()));
        // 2 chats × 2 keys (canonical + canonical:type) = 4 nulls (Redis unset)
        when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList(null, null, null, null));

        CacheMetricsDto dto = service.get();

        assertThat(dto.available()).isTrue();
        assertThat(dto.usedBytes()).isEqualTo(1500);
        assertThat(dto.limitBytes()).isEqualTo(10000);
        assertThat(dto.totalChats()).isEqualTo(2);
        assertThat(dto.topChats()).hasSize(2);
        assertThat(dto.topChats().get(0).title()).isEqualTo("Group A");
        assertThat(dto.topChats().get(0).chatType()).isEqualTo("supergroup");
        assertThat(dto.topChats().get(1).title()).isEqualTo("Channel B");
        assertThat(dto.heatmap()).hasSize(3);
        assertThat(dto.chatTypeSegmentation()).containsKeys("supergroup", "channel");
        assertThat(dto.chatTypeSegmentation().get("supergroup").sizeBytes()).isEqualTo(1000);
    }

    @Test
    @DisplayName("чат не найден в ChatRepository → сегментация 'unknown', title=null")
    void unknownChatFallsBackToUnknownSegment() {
        String json = """
                {
                  "used_bytes": 100, "limit_bytes": 1000, "pct": 10.0,
                  "total_chats": 1, "total_messages": 5, "generated_at": 0,
                  "top_chats": [
                    {"chat_id": -999, "topic_id": 0, "msg_count": 5,
                     "size_bytes": 100, "last_accessed": 0, "pct": 100.0}
                  ],
                  "heatmap": {}
                }
                """;
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
        when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of());
        when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList(null, null));

        CacheMetricsDto dto = service.get();

        assertThat(dto.topChats().get(0).title()).isNull();
        assertThat(dto.topChats().get(0).chatType()).isNull();
        assertThat(dto.chatTypeSegmentation()).containsKey("unknown");
    }

    @Test
    @DisplayName("Redis canonical:{id} → username в DTO, canonical:{id}:type перекрывает DB")
    void redisCanonicalMappingsEnrichUsernameAndType() {
        String json = """
                {
                  "used_bytes": 100, "limit_bytes": 1000, "pct": 10.0,
                  "total_chats": 1, "total_messages": 5, "generated_at": 0,
                  "top_chats": [
                    {"chat_id": -1001, "topic_id": 0, "msg_count": 5,
                     "size_bytes": 100, "last_accessed": 0, "pct": 100.0}
                  ],
                  "heatmap": {}
                }
                """;
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
        // batch multiGet: [canonical:-1001, canonical:-1001:type] → ["groupa", "channel"]
        when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList("groupa", "channel"));
        when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of(
                Chat.builder().canonicalChatId("-1001").topicId(null)
                        .chatTitle("Group A").chatType("supergroup").build()));

        CacheMetricsDto dto = service.get();

        assertThat(dto.topChats().get(0).username()).isEqualTo("groupa");
        assertThat(dto.topChats().get(0).title()).isEqualTo("Group A");
        assertThat(dto.topChats().get(0).chatType()).isEqualTo("channel");
        assertThat(dto.chatTypeSegmentation()).containsKey("channel");
    }

    @Test
    @DisplayName("canonical:{id} содержит numeric-id → username=null (не путать с id)")
    void redisCanonicalNumericIsNotUsername() {
        String json = """
                {
                  "used_bytes": 100, "limit_bytes": 1000, "pct": 10.0,
                  "total_chats": 1, "total_messages": 5, "generated_at": 0,
                  "top_chats": [
                    {"chat_id": -1001, "topic_id": 0, "msg_count": 5,
                     "size_bytes": 100, "last_accessed": 0, "pct": 100.0}
                  ],
                  "heatmap": {}
                }
                """;
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
        // canonical:-1001 = "-1001" → numeric-id, должно фильтроваться → username=null
        when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList("-1001", null));
        when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of());

        CacheMetricsDto dto = service.get();
        assertThat(dto.topChats().get(0).username()).isNull();
    }

    @ParameterizedTest(name = "blank username #{index} -> null")
    @ValueSource(strings = {
            "   ",
            "\t",
            "\n",
            " \t\n ",
            "\u00A0",
            "\u00A0\u00A0\u00A0",
            "\u2003",
            " \u00A0\t ",
            "\u200B",
            "\u200B\u200C\u200D",
            "\uFEFF",
            " \u200B "
    })
    @DisplayName("blank/whitespace + zero-width (NBSP, em-space, ZWSP, ZWNJ, ZWJ, BOM) -> null")
    void blankUsernameBecomesNull(String blank) {
        String json = """
                {
                  "used_bytes": 100, "limit_bytes": 1000, "pct": 10.0,
                  "total_chats": 1, "total_messages": 5, "generated_at": 0,
                  "top_chats": [
                    {"chat_id": -1001, "topic_id": 0, "msg_count": 5,
                     "size_bytes": 100, "last_accessed": 0, "pct": 100.0}
                  ],
                  "heatmap": {}
                }
                """;
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
        when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList(blank, null));
        when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of());

        CacheMetricsDto dto = service.get();
        assertThat(dto.topChats().get(0).username()).isNull();
    }

    @Test
    @DisplayName("Redis вернул пустую строку → username=null (документирует, не регрессирует)")
    void emptyUsernameBecomesNull() {
        // Пустая строка нуллировалась и до isBlank()-фикса: allMatch(isDigit) на пустом стриме
        // возвращает true (vacuous truth) → попадала в digit-фильтр. Sentinel на whitespace —
        // в blankUsernameBecomesNull. Этот тест фиксирует поведение для empty-string чтобы
        // будущая правка digit-фильтра не сломала контракт молча.
        String json = """
                {
                  "used_bytes": 100, "limit_bytes": 1000, "pct": 10.0,
                  "total_chats": 1, "total_messages": 5, "generated_at": 0,
                  "top_chats": [
                    {"chat_id": -1001, "topic_id": 0, "msg_count": 5,
                     "size_bytes": 100, "last_accessed": 0, "pct": 100.0}
                  ],
                  "heatmap": {}
                }
                """;
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
        when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList("", null));
        when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of());

        CacheMetricsDto dto = service.get();
        assertThat(dto.topChats().get(0).username()).isNull();
    }

    @Test
    @DisplayName("topic_id=0 → API отдаёт null (нет топика)")
    void zeroTopicBecomesNull() {
        String json = """
                {"used_bytes":0,"limit_bytes":0,"pct":0,"total_chats":0,"total_messages":0,
                 "generated_at":0,
                 "top_chats":[{"chat_id":1,"topic_id":0,"msg_count":0,"size_bytes":0,
                               "last_accessed":0,"pct":0}],
                 "heatmap":{}}""";
        when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
        when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList(null, null));
        when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of());

        CacheMetricsDto dto = service.get();
        assertThat(dto.topChats().get(0).topicId()).isNull();
    }
}
