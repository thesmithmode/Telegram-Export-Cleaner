package com.tcleaner.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.dashboard.events.StatsStreamPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExportJobProducer")
class ExportJobProducerTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private ListOperations<String, String> listOps;

    private ExportJobProducer jobProducer;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForList()).thenReturn(listOps);
        @SuppressWarnings("unchecked")
        ObjectProvider<StatsStreamPublisher> noPublisher = mock(ObjectProvider.class);
        lenient().when(noPublisher.getIfAvailable()).thenReturn(null);
        jobProducer = new ExportJobProducer(redis, new ObjectMapper(), "telegram_export", noPublisher);
    }

    @Nested
    @DisplayName("Защита от дубликатов (SET NX)")
    class DuplicateProtectionTests {

        @Test
        @DisplayName("должен добавить первый экспорт успешно")
        void shouldEnqueueFirstExport() {
            long userId = 12345L;

            when(valueOps.setIfAbsent(eq("active_export:" + userId), anyString(),
                    eq(60L), eq(TimeUnit.MINUTES))).thenReturn(true);

            String taskId = jobProducer.enqueue(userId, userId, 123456789L);

            assertNotNull(taskId);
            assertTrue(taskId.startsWith("export_"));
            verify(listOps).rightPush(anyString(), anyString());
        }

        @Test
        @DisplayName("должен отклонить второй экспорт при активном первом (SET NX)")
        void shouldRejectDuplicateExport() {
            long userId = 54321L;

            when(valueOps.setIfAbsent(eq("active_export:" + userId), anyString(),
                    eq(60L), eq(TimeUnit.MINUTES))).thenReturn(false);
            when(valueOps.get("active_export:" + userId)).thenReturn("export_existing");

            assertThrows(
                    IllegalStateException.class,
                    () -> jobProducer.enqueue(userId, userId, 987654321L)
            );

            verify(listOps, never()).rightPush(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Функция отмены (cancel)")
    class CancelTests {

        @Test
        @DisplayName("должен установить флаг отмены в Redis")
        void shouldSetCancelFlag() {
            long userId = 11111L;
            String taskId = "export_abc123";

            when(valueOps.get("active_export:" + userId)).thenReturn(taskId);
            when(valueOps.get("job_json:" + taskId)).thenReturn("{\"task_id\":\"" + taskId + "\"}");
            when(valueOps.get("job_queue:" + taskId)).thenReturn("telegram_export");

            jobProducer.cancelExport(userId);

            verify(valueOps).set(eq("cancel_export:" + taskId), eq("1"),
                    eq(60L), eq(TimeUnit.MINUTES));
            verify(redis).delete("active_export:" + userId);
        }

        @Test
        @DisplayName("должен обработать отмену несуществующего экспорта без ошибки")
        void shouldHandleCancelOfNonexistentExport() {
            long userId = 22222L;

            when(valueOps.get("active_export:" + userId)).thenReturn(null);

            assertDoesNotThrow(() -> jobProducer.cancelExport(userId));
            verify(valueOps, never()).set(anyString(), eq("1"), anyLong(), any(TimeUnit.class));
        }
    }

    @Nested
    @DisplayName("Subscription-очередь (enqueueSubscription)")
    class SubscriptionQueueTests {

        @Test
        @DisplayName("taskId начинается с 'sub_' и задача попадает в очередь telegram_export_subscription")
        void shouldEnqueueToSubscriptionQueueWithSubPrefix() {
            long userId = 99001L;
            long userChatId = 99001L;
            long subscriptionId = 42L;

            String taskId = jobProducer.enqueueSubscription(
                    userId, userChatId, "@testchannel",
                    "2026-01-01", "2026-01-31", subscriptionId
            );

            assertNotNull(taskId);
            assertTrue(taskId.startsWith("sub_"), "taskId должен начинаться с 'sub_', получено: " + taskId);
            verify(listOps).rightPush(eq("telegram_export_subscription"), anyString());
        }

        @Test
        @DisplayName("job-JSON содержит source=subscription и subscription_id")
        void shouldIncludeSourceAndSubscriptionIdInJson() throws Exception {
            long userId = 99002L;
            long userChatId = 99002L;
            long subscriptionId = 77L;
            ObjectMapper mapper = new ObjectMapper();

            jobProducer.enqueueSubscription(
                    userId, userChatId, "@anotherchannel",
                    "2026-02-01", "2026-02-28", subscriptionId
            );

            verify(listOps).rightPush(
                    eq("telegram_export_subscription"),
                    argThat(json -> {
                        try {
                            Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() {});
                            return "subscription".equals(parsed.get("source"))
                                    && subscriptionId == ((Number) parsed.get("subscription_id")).longValue();
                        } catch (Exception e) {
                            return false;
                        }
                    })
            );
        }

        @Test
        @DisplayName("active_export:<userId> НЕ выставляется при enqueueSubscription")
        void shouldNotSetActiveExportLock() {
            long userId = 99003L;
            long subscriptionId = 55L;

            jobProducer.enqueueSubscription(
                    userId, userId, "@lockchannel",
                    "2026-03-01", "2026-03-31", subscriptionId
            );

            verify(valueOps, never()).setIfAbsent(
                    eq("active_export:" + userId),
                    anyString(),
                    anyLong(),
                    any(TimeUnit.class)
            );
        }
    }
}
