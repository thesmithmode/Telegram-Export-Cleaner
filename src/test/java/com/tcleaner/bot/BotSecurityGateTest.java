package com.tcleaner.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotSecurityGateTest {

    private StringRedisTemplate redisMock;
    private ValueOperations<String, String> valueOpsMock;
    private BotSecurityGate gate;

    @BeforeEach
    void setUp() {
        redisMock = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        valueOpsMock = ops;
        when(redisMock.opsForValue()).thenReturn(valueOpsMock);

        gate = new BotSecurityGate(redisMock);
    }

    @Nested
    @DisplayName("Redis blacklist")
    class Blacklist {

        @Test
        @DisplayName("isBlocked возвращает true если Redis содержит bot:blocked:{userId}")
        void blockedWhenKeyExists() {
            when(redisMock.hasKey("bot:blocked:42")).thenReturn(Boolean.TRUE);

            assertThat(gate.isBlocked(42L)).isTrue();
        }

        @Test
        @DisplayName("isBlocked возвращает false если ключа нет")
        void notBlockedWhenKeyAbsent() {
            when(redisMock.hasKey("bot:blocked:99")).thenReturn(Boolean.FALSE);

            assertThat(gate.isBlocked(99L)).isFalse();
        }

        @Test
        @DisplayName("isBlocked возвращает false если Redis вернул null (failsafe)")
        void notBlockedOnNullRedisResponse() {
            when(redisMock.hasKey(anyString())).thenReturn(null);

            assertThat(gate.isBlocked(7L)).isFalse();
        }

        @Test
        @DisplayName("isBlocked не обращается к opsForValue")
        void doesNotUseOpsForValue() {
            when(redisMock.hasKey(anyString())).thenReturn(Boolean.FALSE);

            gate.isBlocked(5L);

            verify(redisMock, never()).opsForValue();
        }

        @Test
        @DisplayName("isBlocked возвращает false при исключении Redis — fail-open")
        void failOpenOnRedisException() {
            when(redisMock.hasKey(anyString())).thenThrow(new RuntimeException("connection refused"));

            assertThat(gate.isBlocked(8L)).isFalse();
        }
    }

    @Nested
    @DisplayName("Flood limiter")
    class FloodLimiter {

        @Test
        @DisplayName("первые 3 сообщения подряд от одного userId не флудят")
        void threeMessagesAllowed() {
            assertThat(gate.isFlooded(100L)).isFalse();
            assertThat(gate.isFlooded(100L)).isFalse();
            assertThat(gate.isFlooded(100L)).isFalse();
        }

        @Test
        @DisplayName("4-е сообщение в окне 5 секунд — flood")
        void fourthMessageIsFlooded() {
            gate.isFlooded(200L);
            gate.isFlooded(200L);
            gate.isFlooded(200L);

            assertThat(gate.isFlooded(200L)).isTrue();
        }

        @Test
        @DisplayName("разные userId независимы — flood одного не влияет на другого")
        void differentUsersAreIndependent() {
            gate.isFlooded(300L);
            gate.isFlooded(300L);
            gate.isFlooded(300L);
            gate.isFlooded(300L);

            assertThat(gate.isFlooded(301L)).isFalse();
        }

        @Test
        @DisplayName("isFlooded не трогает Redis")
        void doesNotTouchRedis() {
            gate.isFlooded(400L);
            gate.isFlooded(400L);
            gate.isFlooded(400L);
            gate.isFlooded(400L);

            verify(redisMock, never()).hasKey(anyString());
            verify(redisMock, never()).opsForValue();
        }
    }
}
