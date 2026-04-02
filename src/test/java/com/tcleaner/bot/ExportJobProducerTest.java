package com.tcleaner.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты для ExportJobProducer — проверяет добавление задач в Redis.
 */
@SpringBootTest
@Testcontainers
@DisplayName("ExportJobProducer — добавление задач в Redis")
class ExportJobProducerTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        registry.add("app.storage.import-path", () -> tmp.resolve("tcleaner-test/import").toString());
        registry.add("app.storage.export-path", () -> tmp.resolve("tcleaner-test/export").toString());
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.autoconfigure.exclude", () -> "");
    }

    @Autowired
    private ExportJobProducer producer;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String QUEUE = "telegram_export";

    @BeforeEach
    void clearRedis() {
        redisTemplate.delete(QUEUE);
        // Очищаем active_export ключи всех тестовых пользователей
        redisTemplate.keys("active_export:*").forEach(redisTemplate::delete);
    }

    @Test
    @DisplayName("enqueue возвращает непустой task_id")
    void enqueueReturnsTaskId() {
        String taskId = producer.enqueue(111L, 111L, -100123456L);
        assertThat(taskId).isNotBlank().startsWith("export_");
    }

    @Test
    @DisplayName("enqueue добавляет задачу в Redis-очередь")
    void enqueueAddsJobToRedis() throws Exception {
        long userId = 999L;
        long userChatId = 999L;
        long chatId = -1001234567890L;

        producer.enqueue(userId, userChatId, chatId);

        Long queueLen = redisTemplate.opsForList().size(QUEUE);
        assertThat(queueLen).isEqualTo(1L);

        String json = redisTemplate.opsForList().leftPop(QUEUE);
        assertThat(json).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> job = objectMapper.readValue(json, Map.class);
        assertThat(job.get("task_id")).asString().startsWith("export_");
        assertThat(((Number) job.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(((Number) job.get("user_chat_id")).longValue()).isEqualTo(userChatId);
        assertThat(((Number) job.get("chat_id")).longValue()).isEqualTo(chatId);
        assertThat(((Number) job.get("limit")).intValue()).isZero();
        assertThat(((Number) job.get("offset_id")).intValue()).isZero();
    }

    @Test
    @DisplayName("каждый enqueue создаёт уникальный task_id для разных пользователей")
    void enqueueGeneratesUniqueTaskIds() {
        String id1 = producer.enqueue(1L, 1L, -100L);
        String id2 = producer.enqueue(2L, 2L, -100L);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("enqueue бросает IllegalStateException если у пользователя уже есть активный экспорт")
    void enqueueThrowsWhenUserHasActiveExport() {
        producer.enqueue(777L, 777L, -100L);
        assertThatThrownBy(() -> producer.enqueue(777L, 777L, -200L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Экспорт уже активен");
    }

    @Test
    @DisplayName("несколько задач от разных пользователей добавляются в правильном порядке")
    void multipleJobsOrderedCorrectly() throws Exception {
        long chatId1 = -100111L;
        long chatId2 = -100222L;

        producer.enqueue(1L, 1L, chatId1);
        producer.enqueue(2L, 2L, chatId2);

        assertThat(redisTemplate.opsForList().size(QUEUE)).isEqualTo(2L);

        String first = redisTemplate.opsForList().leftPop(QUEUE);
        @SuppressWarnings("unchecked")
        Map<String, Object> job1 = objectMapper.readValue(first, Map.class);
        assertThat(((Number) job1.get("chat_id")).longValue()).isEqualTo(chatId1);

        String second = redisTemplate.opsForList().leftPop(QUEUE);
        @SuppressWarnings("unchecked")
        Map<String, Object> job2 = objectMapper.readValue(second, Map.class);
        assertThat(((Number) job2.get("chat_id")).longValue()).isEqualTo(chatId2);
    }

    @Test
    @DisplayName("storeQueueMsgId сохраняет ключ queue_msg:{taskId} в Redis")
    void storeQueueMsgIdSavesKey() {
        String taskId = "export_abc123";
        long userChatId = 987654L;
        int msgId = 42;

        producer.storeQueueMsgId(taskId, userChatId, msgId);

        String stored = redisTemplate.opsForValue().get("queue_msg:" + taskId);
        assertThat(stored).isEqualTo(userChatId + ":" + msgId);
    }

    @Test
    @DisplayName("getQueueLength возвращает текущую длину очереди")
    void getQueueLengthReturnsCorrectCount() {
        assertThat(producer.getQueueLength()).isZero();

        producer.enqueue(1L, 1L, -100L);
        producer.enqueue(2L, 2L, -200L);

        assertThat(producer.getQueueLength()).isEqualTo(2L);
    }

    @Test
    @DisplayName("cancelExport удаляет задачу из очереди через LREM если она ещё не взята воркером")
    void cancelExportRemovesJobFromQueue() {
        long userId = 42L;
        producer.enqueue(userId, userId, -100L);

        assertThat(redisTemplate.opsForList().size(QUEUE)).isEqualTo(1L);

        producer.cancelExport(userId);

        // Задача должна быть удалена из очереди
        assertThat(redisTemplate.opsForList().size(QUEUE)).isZero();
    }

    @Test
    @DisplayName("cancelExport устанавливает флаг cancel_export:<taskId> в Redis")
    void cancelExportSetsCancelFlag() {
        long userId = 43L;
        String taskId = producer.enqueue(userId, userId, -100L);

        producer.cancelExport(userId);

        String cancelFlag = redisTemplate.opsForValue().get("cancel_export:" + taskId);
        assertThat(cancelFlag).isEqualTo("1");
    }

    @Test
    @DisplayName("cancelExport удаляет ключ active_export:<userId>")
    void cancelExportClearsActiveExportKey() {
        long userId = 44L;
        producer.enqueue(userId, userId, -100L);

        assertThat(redisTemplate.opsForValue().get("active_export:" + userId)).isNotNull();

        producer.cancelExport(userId);

        assertThat(redisTemplate.opsForValue().get("active_export:" + userId)).isNull();
    }

    @Test
    @DisplayName("cancelExport не падает если нет активного экспорта для пользователя")
    void cancelExportHandlesMissingExport() {
        // Не должен бросать исключений
        producer.cancelExport(9999L);
    }

    @Test
    @DisplayName("hasActiveProcessingJob возвращает false когда воркер не занят")
    void hasActiveProcessingJobReturnsFalseWhenIdle() {
        assertThat(producer.hasActiveProcessingJob()).isFalse();
    }

    @Test
    @DisplayName("hasActiveProcessingJob возвращает true когда ключ active_processing_job установлен")
    void hasActiveProcessingJobReturnsTrueWhenSet() {
        redisTemplate.opsForValue().set("active_processing_job", "export_abc123");

        assertThat(producer.hasActiveProcessingJob()).isTrue();

        redisTemplate.delete("active_processing_job");
    }

    @Test
    @DisplayName("enqueue сохраняет JSON задачи в job_json:<taskId> для возможного LREM")
    void enqueueStoresJobJsonForLrem() {
        long userId = 45L;
        String taskId = producer.enqueue(userId, userId, -100L);

        String json = redisTemplate.opsForValue().get("job_json:" + taskId);
        assertThat(json).isNotNull().contains(taskId);
    }
}
