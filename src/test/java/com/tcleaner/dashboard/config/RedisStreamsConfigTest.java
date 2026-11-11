package com.tcleaner.dashboard.config;

import com.tcleaner.dashboard.events.StatsStreamConsumer;
import com.tcleaner.dashboard.events.StatsStreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RedisStreamsConfig — ensureConsumerGroup")
class RedisStreamsConfigTest {

    private StringRedisTemplate redisMock;
    private RedisConnection connectionMock;
    private RedisStreamCommands streamCommandsMock;
    private StatsStreamProperties props;
    private RedisStreamsConfig config;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        redisMock = mock(StringRedisTemplate.class);
        connectionMock = mock(RedisConnection.class);
        streamCommandsMock = mock(RedisStreamCommands.class);
        when(connectionMock.streamCommands()).thenReturn(streamCommandsMock);
        when(redisMock.execute(any(RedisCallback.class))).thenAnswer(invocation ->
                ((RedisCallback) invocation.getArgument(0)).doInRedis(connectionMock));
        props = new StatsStreamProperties("stats:events", "dashboard-writer", "java-bot-1", 1000L, false);
        config = new RedisStreamsConfig(redisMock, props);
    }

    @Nested
    @DisplayName("ensureConsumerGroup (PostConstruct)")
    class EnsureConsumerGroup {

        @Test
        @DisplayName("Happy path: createGroup успешно → нет предупреждений")
        void createGroupSuccess() {
            config.ensureConsumerGroup();

            verify(streamCommandsMock).xGroupCreate(
                    aryEq("stats:events".getBytes()), eq("dashboard-writer"),
                    eq(ReadOffset.from("0")), eq(true));
        }

        @Test
        @DisplayName("BUSYGROUP exception: тихо игнорируется (group уже существует)")
        void busyGroupExceptionIgnored() {
            doThrow(new RuntimeException("BUSYGROUP Consumer Group 'dashboard-writer' already exists"))
                    .when(streamCommandsMock).xGroupCreate(any(byte[].class), anyString(), any(), eq(true));

            config.ensureConsumerGroup();
        }

        @Test
        @DisplayName("Другое исключение: логируется предупреждение, не пробрасывается")
        void otherExceptionLogsWarning() {
            doThrow(new RuntimeException("Connection refused"))
                    .when(streamCommandsMock).xGroupCreate(any(byte[].class), anyString(), any(), eq(true));

            config.ensureConsumerGroup();
        }

        @Test
        @DisplayName("Nested cause содержит BUSYGROUP: распознаётся как busyGroup")
        void nestedCauseBusyGroup() {
            RuntimeException root = new RuntimeException("wrapper",
                    new RuntimeException("BUSYGROUP already exists"));
            doThrow(root).when(streamCommandsMock).xGroupCreate(any(byte[].class), anyString(), any(), eq(true));

            config.ensureConsumerGroup();

            verify(streamCommandsMock).xGroupCreate(any(byte[].class), anyString(), any(), eq(true));
        }
    }

    @Nested
    @DisplayName("shutdown (PreDestroy)")
    class Shutdown {

        @Test
        @DisplayName("shutdown без инициализированного container: нет NPE")
        void shutdownWithNullContainer() {
            config.shutdown();
        }
    }

    @Test
    void readFailureDoesNotCancelSubscriptionAndRetainedEventIsDelivered() throws Exception {
        assertReadRecovery(new RuntimeException("Redis temporarily unavailable"), false);
    }

    @Test
    void missingGroupIsRecreatedBeforeReadingRetainedEvent() throws Exception {
        assertReadRecovery(new RuntimeException("wrapper", new RuntimeException("NOGROUP missing")), true);
    }

    @SuppressWarnings("unchecked")
    private void assertReadRecovery(RuntimeException failure, boolean groupMissing) throws Exception {
        config = spy(config);
        // No wall-clock sleeps: control the outage backoff with a latch.
        CountDownLatch backoffEntered = new CountDownLatch(1);
        CountDownLatch releaseBackoff = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        doAnswer(invocation -> {
            backoffEntered.countDown();
            assertThat(releaseBackoff.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        }).when(config).awaitReadRetry();

        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connectionMock);
        ByteRecord record = StreamRecords.newRecord().in(props.key().getBytes())
                .withId(RecordId.of("1-0"))
                .ofBytes(Map.of("payload".getBytes(), "{}".getBytes()));
        when(streamCommandsMock.xReadGroup(any(), any(), any(StreamOffset[].class)))
                .thenThrow(failure)
                .thenReturn(List.of(record))
                .thenAnswer(invocation -> {
                    releaseRead.await(5, TimeUnit.SECONDS);
                    return List.of();
                });
        StatsStreamConsumer consumer = mock(StatsStreamConsumer.class);
        var container = config.statsStreamContainer(factory, consumer);
        try {
            assertThat(backoffEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // No busy retry loop while Redis is unavailable.
            verify(streamCommandsMock).xReadGroup(any(), any(), any(StreamOffset[].class));
            releaseBackoff.countDown();
            verify(consumer, timeout(5000)).onMessage(any());
            verify(streamCommandsMock, groupMissing ? times(1) : never())
                    .xGroupCreate(any(byte[].class), anyString(), eq(ReadOffset.from("0")), eq(true));
            assertThat(container.isRunning()).isTrue();
        } finally {
            config.shutdown();
            releaseBackoff.countDown();
            releaseRead.countDown();
        }
        assertThat(container.isRunning()).isFalse();
    }

    @Test
    void shutdownReleasesBackoffImmediately() throws Exception {
        config.shutdown();
        assertThat(config.awaitReadRetry()).isTrue();
        config.handleReadError(new RuntimeException("stopped"));
    }

    @Test
    void interruptedReaderPreservesInterruptFlag() throws Exception {
        config = spy(config);
        var container = mock(org.springframework.data.redis.stream.StreamMessageListenerContainer.class);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "container", container);
        when(container.isRunning()).thenReturn(true);
        doThrow(new InterruptedException()).when(config).awaitReadRetry();
        try {
            config.handleReadError(new RuntimeException("connection lost"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shutdownDuringBackoffDoesNotRecreateGroup() {
        var container = mock(org.springframework.data.redis.stream.StreamMessageListenerContainer.class);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "container", container);
        when(container.isRunning()).thenReturn(true);
        config.shutdown();
        config.handleReadError(new RuntimeException("NOGROUP missing"));
        verify(streamCommandsMock, never()).xGroupCreate(any(byte[].class), anyString(), any(), eq(true));
        when(container.isRunning()).thenReturn(false);
        config.handleReadError(new RuntimeException("stopped"));
        config.shutdown();
    }
}
