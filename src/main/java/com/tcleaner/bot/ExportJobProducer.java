package com.tcleaner.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Кладёт задачи на экспорт в Redis-очередь.
 *
 * <p>Python-воркер читает очередь через BLPOP и обрабатывает задачи по одной.</p>
 *
 * <p>Формат JSON-задачи:</p>
 * <pre>
 * {
 *   "task_id":     "uuid",
 *   "user_id":     12345,
 *   "user_chat_id": 12345,
 *   "chat_id":     -100123456789 или "username",
 *   "limit":       0,
 *   "offset_id":   0
 * }
 * </pre>
 */
@Service
public class ExportJobProducer {

    private static final Logger log = LoggerFactory.getLogger(ExportJobProducer.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String queueName;

    /**
     * Конструктор.
     *
     * @param redis        клиент Redis
     * @param objectMapper Jackson ObjectMapper
     * @param queueName    имя очереди (из application.properties)
     */
    public ExportJobProducer(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${telegram.queue.name}") String queueName
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
    }

    /**
     * Добавляет задачу на экспорт в конец Redis-очереди (RPUSH).
     *
     * @param userId     Telegram user ID пользователя, сделавшего запрос
     * @param userChatId Telegram chat ID — куда вернуть результат (обычно равен userId)
     * @param chatId     ID чата, историю которого нужно экспортировать
     * @return task_id созданной задачи
     * @throws RuntimeException если не удалось сериализовать задачу или записать в Redis
     */
    public String enqueue(long userId, long userChatId, long chatId) {
        return enqueue(userId, userChatId, (Object) chatId, null, null);
    }

    /**
     * Добавляет задачу на экспорт с фильтрацией по датам.
     *
     * @param userId     Telegram user ID
     * @param userChatId Telegram chat ID — куда вернуть результат
     * @param chatId     ID чата для экспорта
     * @param fromDate   начальная дата (ISO, nullable)
     * @param toDate     конечная дата (ISO, nullable)
     * @return task_id созданной задачи
     */
    public String enqueue(long userId, long userChatId, long chatId, String fromDate, String toDate) {
        return enqueue(userId, userChatId, (Object) chatId, fromDate, toDate);
    }

    /**
     * Добавляет задачу на экспорт по username чата.
     *
     * @param userId         Telegram user ID пользователя, сделавшего запрос
     * @param userChatId     Telegram chat ID — куда вернуть результат
     * @param chatIdentifier username чата (без @)
     * @return task_id созданной задачи
     * @throws RuntimeException если не удалось сериализовать задачу или записать в Redis
     */
    public String enqueue(long userId, long userChatId, String chatIdentifier) {
        return enqueue(userId, userChatId, (Object) chatIdentifier, null, null);
    }

    /**
     * Добавляет задачу на экспорт по username с фильтрацией по датам.
     *
     * @param userId         Telegram user ID
     * @param userChatId     Telegram chat ID — куда вернуть результат
     * @param chatIdentifier username чата (без @)
     * @param fromDate       начальная дата (ISO, nullable)
     * @param toDate         конечная дата (ISO, nullable)
     * @return task_id созданной задачи
     */
    public String enqueue(long userId, long userChatId, String chatIdentifier, String fromDate, String toDate) {
        return enqueue(userId, userChatId, (Object) chatIdentifier, fromDate, toDate);
    }

    private String enqueue(long userId, long userChatId, Object chatId, String fromDate, String toDate) {
        String taskId = "export_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        Map<String, Object> job = new HashMap<>();
        job.put("task_id", taskId);
        job.put("user_id", userId);
        job.put("user_chat_id", userChatId);
        job.put("chat_id", chatId);
        job.put("limit", 0);
        job.put("offset_id", 0);
        if (fromDate != null) {
            job.put("from_date", fromDate);
        }
        if (toDate != null) {
            job.put("to_date", toDate);
        }

        try {
            String json = objectMapper.writeValueAsString(job);
            redis.opsForList().rightPush(queueName, json);
            log.info("Задача {} добавлена в очередь {} (chat_id={})", taskId, queueName, chatId);
            return taskId;
        } catch (Exception e) {
            log.error("Не удалось добавить задачу в очередь: {}", e.getMessage(), e);
            throw new RuntimeException("Ошибка добавления задачи в очередь", e);
        }
    }
}
