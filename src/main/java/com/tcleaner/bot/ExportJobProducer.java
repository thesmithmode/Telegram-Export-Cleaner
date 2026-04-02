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
import java.util.concurrent.TimeUnit;

/**
 * Кладёт задачи на экспорт в Redis-очередь.
 *
 * <p>Python-воркер читает очередь через BLPOP и обрабатывает задачи по одной.</p>
 *
 * <h3>Защита от параллельных экспортов</h3>
 * <p>Каждый вызов {@code enqueue()} атомарно резервирует слот через Redis SET NX EX
 * (ключ {@code active_export:<userId>}). Если ключ уже существует —
 * метод бросает {@link IllegalStateException} вместо добавления дублирующей задачи.
 * Это устраняет race condition между {@link #getActiveExport(long)} и {@code enqueue()}.</p>
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
     * <p>Атомарно резервирует слот через Redis SET NX перед добавлением в очередь.
     * Если у пользователя уже есть активный экспорт — бросает {@link IllegalStateException}.</p>
     *
     * @param userId     Telegram user ID пользователя, сделавшего запрос
     * @param userChatId Telegram chat ID — куда вернуть результат (обычно равен userId)
     * @param chatId     ID чата, историю которого нужно экспортировать
     * @return task_id созданной задачи
     * @throws IllegalStateException если у пользователя уже есть активный экспорт
     * @throws RuntimeException      если не удалось сериализовать задачу или записать в Redis
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

    private static final String ACTIVE_EXPORT_PREFIX = "active_export:";
    private static final String CANCEL_EXPORT_PREFIX = "cancel_export:";
    private static final String JOB_JSON_PREFIX = "job_json:";
    private static final String ACTIVE_PROCESSING_JOB_KEY = "active_processing_job";
    private static final long ACTIVE_EXPORT_TTL_MINUTES = 60;

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
            // Атомарно помечаем экспорт активным (SET NX EX).
            // Если ключ уже существует — другой запрос уже прошёл, бросаем исключение.
            Boolean reserved = redis.opsForValue().setIfAbsent(
                    ACTIVE_EXPORT_PREFIX + userId, taskId,
                    ACTIVE_EXPORT_TTL_MINUTES, TimeUnit.MINUTES
            );
            if (!Boolean.TRUE.equals(reserved)) {
                String existing = redis.opsForValue().get(ACTIVE_EXPORT_PREFIX + userId);
                throw new IllegalStateException("Экспорт уже активен: " + existing);
            }
            redis.opsForList().rightPush(queueName, json);
            // Сохраняем JSON задачи для возможного LREM при отмене из очереди
            redis.opsForValue().set(JOB_JSON_PREFIX + taskId, json, ACTIVE_EXPORT_TTL_MINUTES, TimeUnit.MINUTES);
            log.info("Задача {} добавлена в очередь {} (chat_id={})", taskId, queueName, chatId);
            return taskId;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Не удалось добавить задачу в очередь: {}", e.getMessage(), e);
            throw new RuntimeException("Ошибка добавления задачи в очередь", e);
        }
    }

    /**
     * Возвращает текущую длину очереди (количество ожидающих задач).
     *
     * @return количество задач в очереди
     */
    public long getQueueLength() {
        Long size = redis.opsForList().size(queueName);
        return size != null ? size : 0L;
    }

    /**
     * Проверяет, обрабатывает ли воркер задачу прямо сейчас.
     * Python-воркер устанавливает ключ active_processing_job при старте задачи и удаляет при завершении.
     *
     * @return true если воркер занят
     */
    public boolean hasActiveProcessingJob() {
        return Boolean.TRUE.equals(redis.hasKey(ACTIVE_PROCESSING_JOB_KEY));
    }

    /**
     * Сохраняет message_id сообщения о позиции в очереди для последующего редактирования воркером.
     *
     * @param taskId     ID задачи
     * @param userChatId Telegram chat ID пользователя
     * @param msgId      ID отправленного сообщения с позицией
     */
    public void storeQueueMsgId(String taskId, long userChatId, int msgId) {
        redis.opsForValue().set(
                "queue_msg:" + taskId,
                userChatId + ":" + msgId,
                2, TimeUnit.HOURS
        );
    }

    /**
     * Проверяет, есть ли у пользователя активный экспорт.
     * Автоматически очищает протухшие ключи: если задача уже completed/failed
     * или отсутствует в очереди и не в processing — считается завершённой.
     *
     * @return task_id активного экспорта или null
     */
    public String getActiveExport(long userId) {
        String taskId = redis.opsForValue().get(ACTIVE_EXPORT_PREFIX + userId);
        if (taskId == null) {
            return null;
        }

        // Проверяем, реально ли задача ещё активна
        Boolean isProcessing = redis.hasKey("job:processing:" + taskId);
        Boolean isCompleted = redis.hasKey("job:completed:" + taskId);
        Boolean isFailed = redis.hasKey("job:failed:" + taskId);

        if (Boolean.TRUE.equals(isCompleted) || Boolean.TRUE.equals(isFailed)) {
            // Задача завершена, но ключ не был очищен — чистим
            log.info("Очищаю протухший active_export для user {} (task {} завершена)", userId, taskId);
            redis.delete(ACTIVE_EXPORT_PREFIX + userId);
            return null;
        }

        if (Boolean.TRUE.equals(isProcessing)) {
            // Задача в обработке — действительно активна
            return taskId;
        }

        // Задача не в processing, не completed, не failed — проверяем очередь
        Long queueSize = redis.opsForList().size(queueName);
        if (queueSize != null && queueSize > 0) {
            // В очереди есть задачи — возможно наша ждёт
            return taskId;
        }

        // Нигде не найдена — протухший ключ
        log.info("Очищаю протухший active_export для user {} (task {} не найдена)", userId, taskId);
        redis.delete(ACTIVE_EXPORT_PREFIX + userId);
        return null;
    }

    /**
     * Отправляет сигнал отмены экспорта.
     * Если задача ещё в очереди (не взята воркером) — удаляет её через LREM.
     * Если задача уже обрабатывается — устанавливает флаг cancel_export для воркера.
     */
    public void cancelExport(long userId) {
        String taskId = redis.opsForValue().get(ACTIVE_EXPORT_PREFIX + userId);
        if (taskId == null) {
            log.warn("Нет активного экспорта для пользователя {}", userId);
            return;
        }

        // Пытаемся удалить задачу из очереди (если ещё не взята воркером)
        String json = redis.opsForValue().get(JOB_JSON_PREFIX + taskId);
        if (json != null) {
            Long removed = redis.opsForList().remove(queueName, 1, json);
            if (removed != null && removed > 0) {
                log.info("Задача {} удалена из очереди (не успела начаться)", taskId);
            }
            redis.delete(JOB_JSON_PREFIX + taskId);
        }

        // Устанавливаем флаг отмены (на случай если задача уже в обработке)
        redis.opsForValue().set(
                CANCEL_EXPORT_PREFIX + taskId, "1",
                ACTIVE_EXPORT_TTL_MINUTES, TimeUnit.MINUTES
        );
        redis.delete(ACTIVE_EXPORT_PREFIX + userId);
        log.info("Запрошена отмена экспорта {} для пользователя {}", taskId, userId);
    }
}
