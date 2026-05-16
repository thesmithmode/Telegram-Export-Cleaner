package com.tcleaner.bot;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private List<Object> pipelineResult(boolean processing, boolean completed, boolean failed,
                                        long queue, long express, boolean payload) {
        List<Object> res = new ArrayList<>();
        res.add(processing);
        res.add(completed);
        res.add(failed);
        res.add(queue);
        res.add(express);
        res.add(payload);
        return res;
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

        @Test
        @DisplayName("rightPush бросает → откатывает active_export")
        void shouldRollBackActiveExportOnQueueFailure() {
            long userId = 55555L;
            when(valueOps.setIfAbsent(eq("active_export:" + userId), anyString(),
                    eq(60L), eq(TimeUnit.MINUTES))).thenReturn(true);
            when(listOps.rightPush(anyString(), anyString()))
                    .thenThrow(new RuntimeException("Redis down"));

            assertThrows(RuntimeException.class, () -> jobProducer.enqueue(userId, userId, 111L));

            verify(redis).delete("active_export:" + userId);
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

        @Test
        @DisplayName("targetQueue отсутствует → fallback на обе основные очереди")
        void shouldFallBackToBothQueuesWhenTargetQueueKeyMissing() {
            long userId = 33333L;
            String taskId = "export_fallback";

            when(valueOps.get("active_export:" + userId)).thenReturn(taskId);
            when(valueOps.get("job_json:" + taskId)).thenReturn("{\"task_id\":\"" + taskId + "\"}");
            when(valueOps.get("job_queue:" + taskId)).thenReturn(null);

            jobProducer.cancelExport(userId);

            verify(listOps).remove(eq("telegram_export"), eq(1L), anyString());
            verify(listOps).remove(eq("telegram_export_express"), eq(1L), anyString());
        }
    }

    @Nested
    @DisplayName("getActiveExport — все ветки")
    class GetActiveExportTests {

        @Test
        @DisplayName("нет active_export ключа → null")
        void whenNoActiveExport_returnsNull() {
            when(valueOps.get("active_export:1")).thenReturn(null);

            assertNull(jobProducer.getActiveExport(1L));
        }

        @Test
        @DisplayName("задача completed → очистить active_export, вернуть null")
        void whenTaskCompleted_clearsAndReturnsNull() {
            when(valueOps.get("active_export:2")).thenReturn("export_abc");
            when(redis.executePipelined(any(SessionCallback.class)))
                    .thenReturn(pipelineResult(false, true, false, 0L, 0L, false));

            assertNull(jobProducer.getActiveExport(2L));
            verify(redis).delete("active_export:2");
        }

        @Test
        @DisplayName("задача failed → очистить active_export, вернуть null")
        void whenTaskFailed_clearsAndReturnsNull() {
            when(valueOps.get("active_export:3")).thenReturn("export_abc");
            when(redis.executePipelined(any(SessionCallback.class)))
                    .thenReturn(pipelineResult(false, false, true, 0L, 0L, false));

            assertNull(jobProducer.getActiveExport(3L));
            verify(redis).delete("active_export:3");
        }

        @Test
        @DisplayName("задача processing → вернуть taskId (не очищать)")
        void whenTaskProcessing_returnsTaskId() {
            when(valueOps.get("active_export:4")).thenReturn("export_xyz");
            when(redis.executePipelined(any(SessionCallback.class)))
                    .thenReturn(pipelineResult(true, false, false, 0L, 0L, false));

            assertEquals("export_xyz", jobProducer.getActiveExport(4L));
            verify(redis, never()).delete(anyString());
        }

        @Test
        @DisplayName("задача в очереди + payload жив → вернуть taskId")
        void whenInQueueWithPayload_returnsTaskId() {
            when(valueOps.get("active_export:5")).thenReturn("export_pqr");
            when(redis.executePipelined(any(SessionCallback.class)))
                    .thenReturn(pipelineResult(false, false, false, 1L, 0L, true));

            assertEquals("export_pqr", jobProducer.getActiveExport(5L));
        }

        @Test
        @DisplayName("express-очередь непуста + payload → вернуть taskId")
        void whenInExpressQueueWithPayload_returnsTaskId() {
            when(valueOps.get("active_export:6")).thenReturn("export_exp");
            when(redis.executePipelined(any(SessionCallback.class)))
                    .thenReturn(pipelineResult(false, false, false, 0L, 2L, true));

            assertEquals("export_exp", jobProducer.getActiveExport(6L));
        }

        @Test
        @DisplayName("очередь пуста, payload удалён → очистить, null")
        void whenQueueEmptyAndPayloadGone_clearsAndReturnsNull() {
            when(valueOps.get("active_export:7")).thenReturn("export_gone");
            when(redis.executePipelined(any(SessionCallback.class)))
                    .thenReturn(pipelineResult(false, false, false, 0L, 0L, false));

            assertNull(jobProducer.getActiveExport(7L));
            verify(redis).delete("active_export:7");
        }

        @Test
        @DisplayName("очередь непуста, но payload удалён → очистить, null")
        void whenQueueNonEmptyButPayloadGone_clearsAndReturnsNull() {
            when(valueOps.get("active_export:8")).thenReturn("export_stale");
            when(redis.executePipelined(any(SessionCallback.class)))
                    .thenReturn(pipelineResult(false, false, false, 5L, 0L, false));

            assertNull(jobProducer.getActiveExport(8L));
            verify(redis).delete("active_export:8");
        }
    }

    @Nested
    @DisplayName("isLikelyCached — все ветки")
    class IsLikelyCachedTests {

        @Test
        @DisplayName("canonical найден + ranges непустые → true")
        void whenCanonicalAndRangesExist_returnsTrue() {
            when(valueOps.get("canonical:@chat")).thenReturn("123456");
            when(valueOps.get("cache:ranges:123456")).thenReturn("[1,100]");

            assertTrue(jobProducer.isLikelyCached("@chat"));
        }

        @Test
        @DisplayName("canonical отсутствует, прямой ranges непустой → true")
        void whenNoCanonicalButDirectRangesExist_returnsTrue() {
            when(valueOps.get("canonical:123")).thenReturn(null);
            when(valueOps.get("cache:ranges:123")).thenReturn("[50,200]");

            assertTrue(jobProducer.isLikelyCached("123"));
        }

        @Test
        @DisplayName("ranges = '[]' → false")
        void whenRangesEmpty_returnsFalse() {
            when(valueOps.get("canonical:@chan")).thenReturn(null);
            when(valueOps.get("cache:ranges:@chan")).thenReturn("[]");

            assertFalse(jobProducer.isLikelyCached("@chan"));
        }

        @Test
        @DisplayName("ranges = null → false")
        void whenRangesNull_returnsFalse() {
            when(valueOps.get("canonical:@xx")).thenReturn(null);
            when(valueOps.get("cache:ranges:@xx")).thenReturn(null);

            assertFalse(jobProducer.isLikelyCached("@xx"));
        }

        @Test
        @DisplayName("Redis exception → false, не бросает")
        void whenRedisThrows_returnsFalseWithoutThrowing() {
            when(valueOps.get(anyString())).thenThrow(new RuntimeException("connection refused"));

            assertFalse(jobProducer.isLikelyCached("@broken"));
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
                        } catch (Exception ex) {
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

    @Nested
    @DisplayName("Вспомогательные методы")
    class UtilityTests {

        @Test
        @DisplayName("getQueueLength: null sizes → 0")
        void getQueueLength_withNullSizes_returnsZero() {
            when(listOps.size("telegram_export")).thenReturn(null);
            when(listOps.size("telegram_export_express")).thenReturn(null);
            when(listOps.size("telegram_export_subscription")).thenReturn(null);

            assertEquals(0L, jobProducer.getQueueLength());
        }

        @Test
        @DisplayName("getQueueLength: суммирует все три очереди")
        void getQueueLength_sumsAllQueues() {
            when(listOps.size("telegram_export")).thenReturn(2L);
            when(listOps.size("telegram_export_express")).thenReturn(3L);
            when(listOps.size("telegram_export_subscription")).thenReturn(1L);

            assertEquals(6L, jobProducer.getQueueLength());
        }

        @Test
        @DisplayName("hasActiveProcessingJob: ключ есть → true")
        void hasActiveProcessingJob_whenKeyExists_returnsTrue() {
            when(redis.hasKey("active_processing_job")).thenReturn(Boolean.TRUE);

            assertTrue(jobProducer.hasActiveProcessingJob());
        }

        @Test
        @DisplayName("hasActiveProcessingJob: null от Redis → false")
        void hasActiveProcessingJob_whenKeyMissing_returnsFalse() {
            when(redis.hasKey("active_processing_job")).thenReturn(null);

            assertFalse(jobProducer.hasActiveProcessingJob());
        }

        @Test
        @DisplayName("storeQueueMsgId: сохраняет key=queue_msg:<taskId> value=chatId:msgId")
        void storeQueueMsgId_storesCorrectKeyAndValue() {
            jobProducer.storeQueueMsgId("export_abc", 99L, 42);

            verify(valueOps).set(
                    eq("queue_msg:export_abc"),
                    eq("99:42"),
                    eq(2L),
                    eq(TimeUnit.HOURS)
            );
        }
    }
}
