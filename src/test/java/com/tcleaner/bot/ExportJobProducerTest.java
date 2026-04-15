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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
        ObjectProvider<StatsStreamPublisher> noPublisher =
                org.mockito.Mockito.mock(ObjectProvider.class);
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
}
