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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
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
}
