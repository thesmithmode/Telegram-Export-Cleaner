package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверяет десериализацию, ACK-стратегию и восстановление transient-событий из Redis PEL.
 */
@DisplayName("StatsStreamConsumer")
class StatsStreamConsumerTest {

    private ObjectMapper mapper;
    private StringRedisTemplate redis;
    private StreamOperations<String, String, String> streamOps;
    private StatsStreamProperties props;
    private AtomicReference<StatsEventPayload> captured;
    private StatsStreamConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn((StreamOperations) streamOps);
        props = new StatsStreamProperties("stats:events", "dashboard-writer", "java-bot-1", 1000, true);

        captured = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ObjectProvider<com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService> noIngestion =
                mock(ObjectProvider.class);
        when(noIngestion.getIfAvailable()).thenReturn(null);
        consumer = new StatsStreamConsumer(mapper, redis, props, noIngestion) {
            @Override
            void handle(StatsEventPayload payload) {
                captured.set(payload);
            }
        };
    }

    @Test
    @DisplayName("успешная обработка → handle + XACK")
    void handlesAndAcks() throws Exception {
        StatsEventPayload original = StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId("task-123")
                .botUserId(42L)
                .ts(Instant.parse("2026-04-15T12:00:00Z"))
                .build();
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-1"))
                .ofMap(Map.of("payload", mapper.writeValueAsString(original)));

        consumer.onMessage(record);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getType()).isEqualTo(StatsEventType.EXPORT_STARTED);
        assertThat(captured.get().getTaskId()).isEqualTo("task-123");
        verify(streamOps, times(1)).acknowledge(props.key(), props.group(), "0-1");
    }

    @Test
    @DisplayName("битый JSON → не падает, всё равно XACK")
    void broken_json_still_acks() {
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-2"))
                .ofMap(Map.of("payload", "{not-json"));

        consumer.onMessage(record);

        assertThat(captured.get()).isNull();
        verify(streamOps, times(1)).acknowledge(anyString(), anyString(), any(String[].class));
    }

    @Test
    @DisplayName("отсутствует payload field → пропуск + XACK")
    void missing_payload_field_acks() {
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-3"))
                .ofMap(Map.of("other", "x"));

        consumer.onMessage(record);

        assertThat(captured.get()).isNull();
        verify(streamOps).acknowledge(props.key(), props.group(), "0-3");
    }

    @Test
    @DisplayName("blank payload (whitespace) → poison-ACK, handle не вызывается")
    void blankPayloadAcksAsPoison() {
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-4"))
                .ofMap(Map.of("payload", "   "));

        consumer.onMessage(record);

        assertThat(captured.get()).isNull();
        verify(streamOps).acknowledge(props.key(), props.group(), "0-4");
    }

    @Test
    @DisplayName("transient exception в handle (не JsonProcessing) → НЕТ ACK (будет retry)")
    @SuppressWarnings("unchecked")
    void transientExceptionInHandleSkipsAck() throws Exception {
        ObjectProvider<com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService> noIngestion =
                mock(ObjectProvider.class);
        when(noIngestion.getIfAvailable()).thenReturn(null);
        StatsStreamConsumer throwingConsumer = new StatsStreamConsumer(mapper, redis, props, noIngestion) {
            @Override
            void handle(StatsEventPayload payload) {
                throw new RuntimeException("DB transient error");
            }
        };

        StatsEventPayload original = StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED).taskId("task-x").ts(Instant.now()).build();
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-5"))
                .ofMap(Map.of("payload", mapper.writeValueAsString(original)));

        throwingConsumer.onMessage(record);

        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(String[].class));
    }

    @Test
    @DisplayName("stale PEL → XCLAIM → повторная обработка → XACK")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryPendingClaimsStaleAndProcesses() throws Exception {
        PendingMessages pending = pendingWith("0-9", Duration.ofMinutes(1));
        when(streamOps.pending(eq(props.key()), eq(props.group()), any(Range.class), anyLong()))
                .thenReturn(pending);

        StatsEventPayload original = StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId("task-retry")
                .ts(Instant.now())
                .build();
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-9"))
                .ofMap(Map.of("payload", mapper.writeValueAsString(original)));
        when(streamOps.claim(
                eq(props.key()), eq(props.group()), eq(props.consumer()),
                eq(Duration.ofSeconds(30)), any(RecordId[].class)))
                .thenReturn(List.of(record));

        consumer.retryPending();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getTaskId()).isEqualTo("task-retry");
        verify(streamOps).acknowledge(props.key(), props.group(), "0-9");
    }

    @Test
    @DisplayName("свежий PEL младше 30 секунд → не claim")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryPendingSkipsFreshEntries() {
        when(streamOps.pending(eq(props.key()), eq(props.group()), any(Range.class), anyLong()))
                .thenReturn(pendingWith("0-10", Duration.ofSeconds(5)));

        consumer.retryPending();

        verify(streamOps, never()).claim(
                anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }

    @Test
    @DisplayName("пустой PEL → retry ничего не делает")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryPendingEmptyIsNoop() {
        PendingMessages pending = mock(PendingMessages.class);
        when(pending.isEmpty()).thenReturn(true);
        when(streamOps.pending(eq(props.key()), eq(props.group()), any(Range.class), anyLong()))
                .thenReturn(pending);

        consumer.retryPending();

        verify(streamOps, never()).claim(
                anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }

    @Test
    @DisplayName("ошибка Redis при retry PEL → scheduler не падает")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryPendingRedisFailureIsGraceful() {
        when(streamOps.pending(eq(props.key()), eq(props.group()), any(Range.class), anyLong()))
                .thenThrow(new RuntimeException("Redis down"));

        consumer.retryPending();
    }

    @Test
    @DisplayName("XACK сам бросает exception → consumer не падает (graceful warn)")
    void xackFailureIsGraceful() {
        doThrow(new RuntimeException("Redis down"))
                .when(streamOps).acknowledge(anyString(), anyString(), any(String[].class));

        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-6"))
                .ofMap(Map.of("payload", "{not-json"));

        consumer.onMessage(record);

        verify(streamOps).acknowledge(props.key(), props.group(), "0-6");
    }

    @Test
    @DisplayName("handle с доступным ingestion service → service.ingest вызван")
    @SuppressWarnings("unchecked")
    void handleWithIngestionServiceDelegates() throws Exception {
        com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService realService =
                mock(com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService.class);
        ObjectProvider<com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(realService);

        StatsStreamConsumer realConsumer = new StatsStreamConsumer(mapper, redis, props, provider);

        StatsEventPayload original = StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED).taskId("task-real").ts(Instant.now()).build();
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-7"))
                .ofMap(Map.of("payload", mapper.writeValueAsString(original)));

        realConsumer.onMessage(record);

        verify(realService).ingest(any(StatsEventPayload.class));
        verify(streamOps).acknowledge(props.key(), props.group(), "0-7");
    }

    @Test
    @DisplayName("handle без ingestion service → log.debug, не падает")
    void handleWithoutIngestionServiceIsSilent() throws Exception {
        StatsStreamConsumer defaultConsumer = new StatsStreamConsumer(mapper, redis, props,
                mockedNullProvider());

        StatsEventPayload original = StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED).taskId("task-no-svc").ts(Instant.now()).build();
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-8"))
                .ofMap(Map.of("payload", mapper.writeValueAsString(original)));

        defaultConsumer.onMessage(record);

        verify(streamOps).acknowledge(props.key(), props.group(), "0-8");
    }

    @SuppressWarnings("unchecked")
    private PendingMessages pendingWith(String id, Duration idle) {
        PendingMessages pending = mock(PendingMessages.class);
        PendingMessage message = mock(PendingMessage.class);
        when(pending.isEmpty()).thenReturn(false);
        when(pending.iterator()).thenReturn(List.of(message).iterator());
        when(message.getElapsedTimeSinceLastDelivery()).thenReturn(idle);
        when(message.getId()).thenReturn(RecordId.of(id));
        return pending;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService> mockedNullProvider() {
        ObjectProvider<com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService> p =
                mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }
}
