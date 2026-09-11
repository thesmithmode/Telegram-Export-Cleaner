package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatsStreamConsumerCoverageTest {

    private ObjectMapper mapper;
    private StringRedisTemplate redis;
    private StreamOperations<String, String, String> streamOps;
    private StatsStreamProperties props;
    private TaskScheduler retryScheduler;
    private StatsStreamConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        retryScheduler = mock(TaskScheduler.class);
        when(redis.opsForStream()).thenReturn((StreamOperations) streamOps);
        props = new StatsStreamProperties("stats:events", "dashboard-writer", "java-bot-1", 1000, true);
        consumer = new StatsStreamConsumer(mapper, redis, props, nullProvider(), retryScheduler);
    }

    @Test
    void disabledConsumerCoversRetryGuards() throws Exception {
        StatsStreamProperties disabledProps =
                new StatsStreamProperties("stats:events", "dashboard-writer", "java-bot-1", 1000, false);
        StatsStreamConsumer disabled = new StatsStreamConsumer(
                mapper, redis, disabledProps, nullProvider(), retryScheduler) {
            @Override
            void handle(StatsEventPayload payload) {
                throw new RuntimeException("transient");
            }
        };

        disabled.retryPending();
        disabled.retryPendingRecord("0-20");

        StatsEventPayload payload = StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId("disabled-retry")
                .ts(Instant.parse("2026-09-10T20:00:00Z"))
                .build();
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(disabledProps.key())
                .withId(RecordId.of("0-20"))
                .ofMap(Map.of("payload", mapper.writeValueAsString(payload)));
        disabled.onMessage(record);

        verify(retryScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryPendingStopsWhenPendingResultIsNull() {
        when(streamOps.pending(eq(props.key()), eq(props.group()), any(Range.class), anyLong()))
                .thenReturn(null);

        consumer.retryPending();

        verify(retryScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryPendingReschedulesWhenClaimResultIsNull() {
        PendingMessages pending = pendingWith("0-21", Duration.ofSeconds(31));
        when(streamOps.pending(eq(props.key()), eq(props.group()), any(Range.class), anyLong()))
                .thenReturn(pending);
        when(streamOps.claim(
                eq(props.key()), eq(props.group()), eq(props.consumer()),
                eq(Duration.ofSeconds(30)), eq(RecordId.of("0-21"))))
                .thenReturn(null);

        consumer.retryPending();

        verify(retryScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryPendingReschedulesWhenClaimResultIsEmpty() {
        PendingMessages pending = pendingWith("0-22", Duration.ofSeconds(31));
        when(streamOps.pending(eq(props.key()), eq(props.group()), any(Range.class), anyLong()))
                .thenReturn(pending);
        when(streamOps.claim(
                eq(props.key()), eq(props.group()), eq(props.consumer()),
                eq(Duration.ofSeconds(30)), eq(RecordId.of("0-22"))))
                .thenReturn(List.of());

        consumer.retryPending();

        verify(retryScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordRetryStopsWhenClaimAndPendingResultsAreNull() {
        when(streamOps.claim(
                eq(props.key()), eq(props.group()), eq(props.consumer()),
                eq(Duration.ofSeconds(30)), eq(RecordId.of("0-23"))))
                .thenReturn(null);
        when(streamOps.pending(eq(props.key()), eq(props.group()), any(Range.class), anyLong()))
                .thenReturn(null);

        consumer.retryPendingRecord("0-23");

        verify(retryScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordRetryUsesOneSecondDelayAtExactMinimumIdle() {
        PendingMessages pending = pendingWith("0-24", Duration.ofSeconds(30));
        when(streamOps.claim(
                eq(props.key()), eq(props.group()), eq(props.consumer()),
                eq(Duration.ofSeconds(30)), eq(RecordId.of("0-24"))))
                .thenReturn(List.of());
        when(streamOps.pending(eq(props.key()), eq(props.group()), any(Range.class), anyLong()))
                .thenReturn(pending);

        consumer.retryPendingRecord("0-24");

        verify(retryScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

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
    private ObjectProvider<ExportEventIngestionService> nullProvider() {
        ObjectProvider<ExportEventIngestionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
