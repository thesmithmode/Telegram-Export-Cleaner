package com.tcleaner.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для ExportJobProducer.
 *
 * Проверяет корректность управления Redis очередью, защиты от дубликатов (SET NX),
 * и функции отмены экспорта.
 */
@SpringBootTest
@DisplayName("ExportJobProducer")
class ExportJobProducerTest {

    @Autowired
    private ExportJobProducer jobProducer;

    @Autowired
    private StringRedisTemplate redis;

    @Nested
    @DisplayName("Защита от дубликатов (SET NX)")
    class DuplicateProtectionTests {

        @Test
        @DisplayName("должен добавить первый экспорт успешно")
        void shouldEnqueueFirstExport() {
            long userId = 12345L;
            String taskId = jobProducer.enqueue(userId, userId, 123456789L);

            assertNotNull(taskId);
            assertTrue(taskId.startsWith("export_"));

            // Проверить что SET NX ключ установлен
            String activeExport = redis.opsForValue().get("active_export:" + userId);
            assertNotNull(activeExport);
            assertEquals(taskId, activeExport);

            // Cleanup
            redis.delete("active_export:" + userId);
            redis.delete("job_queue:" + taskId);
        }

        @Test
        @DisplayName("должен отклонить второй экспорт при активном первом (SET NX)")
        void shouldRejectDuplicateExport() {
            long userId = 54321L;

            // Первый экспорт
            String taskId1 = jobProducer.enqueue(userId, userId, 123456789L);
            assertNotNull(taskId1);

            // Второй экспорт — должен быть отклонён
            assertThrows(
                    IllegalStateException.class,
                    () -> jobProducer.enqueue(userId, userId, 987654321L),
                    "Должен выбросить исключение при дублирующемся экспорте"
            );

            // Cleanup
            redis.delete("active_export:" + userId);
            redis.delete("job_queue:" + taskId1);
        }
    }

    @Nested
    @DisplayName("Функция отмены (cancel)")
    class CancelTests {

        @Test
        @DisplayName("должен установить флаг отмены в Redis")
        void shouldSetCancelFlag() {
            long userId = 11111L;
            String taskId = jobProducer.enqueue(userId, userId, 123456789L);

            // Отмена
            jobProducer.cancelExport(userId);

            // Проверить что флаг установлен
            String cancelFlag = redis.opsForValue().get("cancel_export:" + taskId);
            assertNotNull(cancelFlag);
            assertEquals("1", cancelFlag);

            // Cleanup
            redis.delete("active_export:" + userId);
            redis.delete("cancel_export:" + taskId);
            redis.delete("job_queue:" + taskId);
        }

        @Test
        @DisplayName("должен обработать отмену несуществующего экспорта без ошибки")
        void shouldHandleCancelOfNonexistentExport() {
            long userId = 22222L;

            // Отмена несуществующего экспорта — должен завершиться без ошибки
            assertDoesNotThrow(() -> jobProducer.cancelExport(userId));
        }
    }
}
