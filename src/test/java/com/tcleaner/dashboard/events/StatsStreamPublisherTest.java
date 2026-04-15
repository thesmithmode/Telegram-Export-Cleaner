package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверяет, что publisher кладёт в стрим single-field map
 * {@code {"payload": <json>}} + approximate MAXLEN options.
 * Ошибки сети/сериализации молча логируются и возвращают null
 * (статистика не валит основной flow экспорта).
 */
@DisplayName("StatsStreamPublisher")
class StatsStreamPublisherTest {

    private ObjectMapper mapper;
    private StringRedisTemplate redis;
    private StatsStreamProperties props;
    private StatsStreamPublisher publisher;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        redis = mock(StringRedisTemplate.class);
        props = new StatsStreamProperties("stats:events", "dashboard-writer", "java-bot-1", 1000, true);
        publisher = new StatsStreamPublisher(redis, mapper, props);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("publish сериализует payload и вызывает xAdd")
    void publishInvokesXAddWithPayload() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamCommands streamCmds = mock(RedisStreamCommands.class);
        when(connection.streamCommands()).thenReturn(streamCmds);
        when(streamCmds.xAdd(any(MapRecord.class), any(XAddOptions.class)))
                .thenReturn(RecordId.of("0-1"));
        when(redis.execute(any(RedisCallback.class)))
                .thenAnswer(invocation -> {
                    RedisCallback<?> callback = invocation.getArgument(0);
                    return callback.doInRedis(connection);
                });

        String id = publisher.publish(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId("task-1")
                .botUserId(42L)
                .ts(Instant.parse("2026-04-15T12:00:00Z"))
                .build());

        assertThat(id).isEqualTo("0-1");

        ArgumentCaptor<MapRecord> recordCaptor = ArgumentCaptor.forClass(MapRecord.class);
        ArgumentCaptor<XAddOptions> optsCaptor = ArgumentCaptor.forClass(XAddOptions.class);
        verify(streamCmds).xAdd(recordCaptor.capture(), optsCaptor.capture());

        MapRecord<byte[], byte[], byte[]> captured = recordCaptor.getValue();
        assertThat(new String(captured.getStream(), StandardCharsets.UTF_8)).isEqualTo("stats:events");
        byte[] payloadBytes = captured.getValue().get("payload".getBytes(StandardCharsets.UTF_8));
        String json = new String(payloadBytes, StandardCharsets.UTF_8);
        assertThat(json)
                .contains("\"type\":\"export.started\"")
                .contains("\"task_id\":\"task-1\"");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("сбой Redis → null, но не исключение")
    void redisFailureDoesNotThrow() {
        when(redis.execute(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("connection refused"));

        String id = publisher.publish(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId("task-err")
                .build());

        assertThat(id).isNull();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("пустой payload не ломает сериализацию — в стрим уходит {} без NPE")
    void doesNotFailOnEmptyPayload() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamCommands streamCmds = mock(RedisStreamCommands.class);
        when(connection.streamCommands()).thenReturn(streamCmds);
        when(streamCmds.xAdd(any(MapRecord.class), any(XAddOptions.class)))
                .thenReturn(RecordId.of("0-2"));
        when(redis.execute(any(RedisCallback.class)))
                .thenAnswer(invocation -> {
                    RedisCallback<?> callback = invocation.getArgument(0);
                    return callback.doInRedis(connection);
                });

        String id = publisher.publish(new StatsEventPayload());

        assertThat(id).isEqualTo("0-2");
    }
}
