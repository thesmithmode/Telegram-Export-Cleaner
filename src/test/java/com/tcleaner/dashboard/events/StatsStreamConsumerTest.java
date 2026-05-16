package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверяет, что consumer десериализует payload в {@link StatsEventPayload},
 * вызывает {@code handle()} и ACK'ает запись — даже если обработка бросила
 * исключение (at-least-once на уровне стрима, идемпотентность — выше).
 */
@DisplayName("StatsStreamConsumer")
class StatsStreamConsumerTest {

    private ObjectMapper mapper;
    private StringRedisTemplate redis;
    private StreamOperations<String, Object, Object> streamOps;
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
        // consumer кидает RuntimeException из handle → catch(Exception) → ack остаётся false
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

        // ACK НЕ должен быть вызван — иначе сообщение потеряется из PEL
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(String[].class));
    }

    @Test
    @DisplayName("XACK сам бросает exception → consumer не падает (graceful warn)")
    void xackFailureIsGraceful() {
        doThrow(new RuntimeException("Redis down"))
                .when(streamOps).acknowledge(anyString(), anyString(), any(String[].class));

        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(props.key())
                .withId(RecordId.of("0-6"))
                .ofMap(Map.of("payload", "{not-json"));  // JsonProcessingException → ack=true

        // Не должно бросить ничего наружу — log.warn внутри try/catch
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
        // captured.get() остаётся null т.к. в setUp() handle переопределён
        // — но в этом тесте нужно проверить default handle (с service==null)
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
    private ObjectProvider<com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService> mockedNullProvider() {
        ObjectProvider<com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService> p =
                mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }
}
