package com.tcleaner.dashboard.config;

import com.tcleaner.dashboard.events.StatsStreamConsumer;
import com.tcleaner.dashboard.events.StatsStreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RedisStreamsConfig — ensureConsumerGroup")
class RedisStreamsConfigTest {

    private StringRedisTemplate redisMock;
    @SuppressWarnings("rawtypes")
    private StreamOperations streamOpsMock;
    private StatsStreamProperties props;
    private RedisStreamsConfig config;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisMock = mock(StringRedisTemplate.class);
        streamOpsMock = mock(StreamOperations.class);
        when(redisMock.opsForStream()).thenReturn(streamOpsMock);
        props = new StatsStreamProperties("stats:events", "dashboard-writer", "java-bot-1", 1000L, false);
        config = new RedisStreamsConfig(redisMock, props);
    }

    @Nested
    @DisplayName("ensureConsumerGroup (PostConstruct)")
    class EnsureConsumerGroup {

        @Test
        @DisplayName("Happy path: createGroup успешно → нет предупреждений")
        @SuppressWarnings("unchecked")
        void createGroupSuccess() {
            config.ensureConsumerGroup();

            verify(streamOpsMock).createGroup(
                    eq("stats:events"), eq(ReadOffset.from("0")), eq("dashboard-writer"));
        }

        @Test
        @DisplayName("BUSYGROUP exception: тихо игнорируется (group уже существует)")
        @SuppressWarnings("unchecked")
        void busyGroupExceptionIgnored() {
            doThrow(new RuntimeException("BUSYGROUP Consumer Group 'dashboard-writer' already exists"))
                    .when(streamOpsMock).createGroup(anyString(), any(), anyString());

            config.ensureConsumerGroup();
        }

        @Test
        @DisplayName("Другое исключение: логируется предупреждение, не пробрасывается")
        @SuppressWarnings("unchecked")
        void otherExceptionLogsWarning() {
            doThrow(new RuntimeException("Connection refused"))
                    .when(streamOpsMock).createGroup(anyString(), any(), anyString());

            config.ensureConsumerGroup();
        }

        @Test
        @DisplayName("Nested cause содержит BUSYGROUP: распознаётся как busyGroup")
        @SuppressWarnings("unchecked")
        void nestedCauseBusyGroup() {
            RuntimeException root = new RuntimeException("wrapper",
                    new RuntimeException("BUSYGROUP already exists"));
            doThrow(root).when(streamOpsMock).createGroup(anyString(), any(), anyString());

            config.ensureConsumerGroup();

            verify(streamOpsMock).createGroup(anyString(), any(), anyString());
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
