package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контрактные тесты JSON-сериализации — Python-воркер шлёт тот же формат
 * через redis-py, любые drift'ы в field-name'ах сломают ingestion.
 */
@DisplayName("StatsEventPayload — JSON-контракт")
class StatsEventPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("сериализация: snake_case + NON_NULL")
    void serializesWithSnakeCaseFields() throws Exception {
        StatsEventPayload event = StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId("task-1")
                .botUserId(42L)
                .chatIdRaw("@chat")
                .ts(Instant.parse("2026-04-15T12:00:00Z"))
                .build();

        String json = mapper.writeValueAsString(event);

        assertThat(json)
                .contains("\"type\":\"export.started\"")
                .contains("\"task_id\":\"task-1\"")
                .contains("\"bot_user_id\":42")
                .contains("\"chat_id_raw\":\"@chat\"")
                .doesNotContain("display_name")
                .doesNotContain("bytes_count");
    }

    @Test
    @DisplayName("десериализация: ignore unknown + parse enum")
    void deserializesIgnoringExtraFields() throws Exception {
        String json = """
                {"type":"bot_user.seen","bot_user_id":7,"username":"a",
                 "future_field_from_python":"whatever"}
                """;

        StatsEventPayload event = mapper.readValue(json, StatsEventPayload.class);

        assertThat(event.getType()).isEqualTo(StatsEventType.BOT_USER_SEEN);
        assertThat(event.getBotUserId()).isEqualTo(7L);
        assertThat(event.getUsername()).isEqualTo("a");
    }

    @Test
    @DisplayName("неизвестный type падает с IllegalArgumentException (fail-fast)")
    void unknownTypeFailsFast() {
        String json = "{\"type\":\"nope.unknown\"}";
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mapper.readValue(json, StatsEventPayload.class));
    }
}
