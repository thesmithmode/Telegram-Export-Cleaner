package com.tcleaner.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.events.StatsStreamPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;

@Service
public class ExportJobProducer {

    private static final Logger log = LoggerFactory.getLogger(ExportJobProducer.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String queueName;
    private final ObjectProvider<StatsStreamPublisher> statsPublisherProvider;

    public ExportJobProducer(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${telegram.queue.name}") String queueName,
            ObjectProvider<StatsStreamPublisher> statsPublisherProvider
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
        this.statsPublisherProvider = statsPublisherProvider;
    }

    public String enqueue(long userId, long userChatId, long chatId) {
        return enqueue(userId, userChatId, (Object) chatId, null, null, null, null, null);
    }

    public String enqueue(long userId, long userChatId, long chatId, String fromDate, String toDate) {
        return enqueue(userId, userChatId, (Object) chatId, null, fromDate, toDate, null, null);
    }

    public String enqueue(long userId, long userChatId, String chatIdentifier) {
        return enqueue(userId, userChatId, (Object) chatIdentifier, null, null, null, null, null);
    }

    public String enqueue(long userId, long userChatId, String chatIdentifier,
                          String fromDate, String toDate) {
        return enqueue(userId, userChatId, (Object) chatIdentifier, null, fromDate, toDate, null, null);
    }

    public String enqueue(long userId, long userChatId, String chatIdentifier,
                          Integer topicId, String fromDate, String toDate) {
        return enqueue(userId, userChatId, (Object) chatIdentifier, topicId, fromDate, toDate, null, null);
    }

    private static final String ACTIVE_EXPORT_PREFIX = "active_export:";
    private static final String CANCEL_EXPORT_PREFIX = "cancel_export:";
    private static final String JOB_JSON_PREFIX = "job_json:";
    private static final String ACTIVE_PROCESSING_JOB_KEY = "active_processing_job";
    private static final long ACTIVE_EXPORT_TTL_MINUTES = 60;
    private static final long QUEUE_MSG_TTL_HOURS = 2;
    private static final int TASK_ID_LENGTH = 16;
    private static final String EXPRESS_QUEUE_SUFFIX = "_express";

    private String enqueue(long userId, long userChatId, Object chatId, Integer topicId,
                           String fromDate, String toDate, String keywords, String excludeKeywords) {
        String taskId = "export_" + UUID.randomUUID().toString().replace("-", "").substring(0, TASK_ID_LENGTH);

        Map<String, Object> job = new HashMap<>();
        job.put("task_id", taskId);
        job.put("user_id", userId);
        job.put("user_chat_id", userChatId);
        job.put("chat_id", chatId);
        if (topicId != null) {
            job.put("topic_id", topicId);
        }
        job.put("limit", 0);
        job.put("offset_id", 0);
        if (fromDate != null) {
            job.put("from_date", fromDate);
        }
        if (toDate != null) {
            job.put("to_date", toDate);
        }
        if (keywords != null) {
            job.put("keywords", keywords);
        }
        if (excludeKeywords != null) {
            job.put("exclude_keywords", excludeKeywords);
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(job);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Ошибка сериализации задачи", e);
        }

        // Атомарно помечаем экспорт активным (SET NX EX). Дубликат → IllegalStateException БЕЗ cleanup.
        Boolean reserved = redis.opsForValue().setIfAbsent(
                ACTIVE_EXPORT_PREFIX + userId, taskId,
                ACTIVE_EXPORT_TTL_MINUTES, TimeUnit.MINUTES
        );
        if (!Boolean.TRUE.equals(reserved)) {
            String existing = redis.opsForValue().get(ACTIVE_EXPORT_PREFIX + userId);
            throw new IllegalStateException("Экспорт уже активен: " + existing);
        }

        try {
            // Если данные чата уже в кэше — задача идёт в приоритетную очередь
            boolean cached = isLikelyCached(chatId);
            String targetQueue = cached ? queueName + EXPRESS_QUEUE_SUFFIX : queueName;
            // Сохраняем метаданные ДО LPUSH: если LPUSH упадёт — откат удалит бронь,
            // а эти ключи протухнут по TTL без последствий.
            // Если же SET упадёт до LPUSH — задача не попадёт в очередь (безопасно).
            redis.opsForValue().set(JOB_JSON_PREFIX + taskId, json, ACTIVE_EXPORT_TTL_MINUTES, TimeUnit.MINUTES);
            redis.opsForValue().set("job_queue:" + taskId, targetQueue, ACTIVE_EXPORT_TTL_MINUTES, TimeUnit.MINUTES);
            redis.opsForList().rightPush(targetQueue, json);
            log.info("Задача {} добавлена в очередь {} (chat_id={}, cached={})", taskId, targetQueue, chatId, cached);
            publishExportStarted(taskId, userId, chatId, topicId, fromDate, toDate, keywords, excludeKeywords);
            return taskId;
        } catch (Exception e) {
            log.error("Не удалось добавить задачу в очередь: {}", e.getMessage(), e);
            try {
                redis.delete(ACTIVE_EXPORT_PREFIX + userId);
                log.info("Бронь экспорта для пользователя {} отозвана из-за ошибки", userId);
            } catch (Exception ex) {
                log.error("Критическая ошибка: не удалось отозвать бронь для {}: {}", userId, ex.getMessage());
            }
            throw new RuntimeException("Ошибка добавления задачи в очередь", e);
        }
    }

    public boolean isLikelyCached(Object chatId) {
        try {
            String input = String.valueOf(chatId);
            // Сначала пробуем через canonical маппинг (username → numeric ID)
            String canonical = redis.opsForValue().get("canonical:" + input);
            if (canonical == null) {
                // Числовой ID — пробуем напрямую
                canonical = input;
            }
            String ranges = redis.opsForValue().get("cache:ranges:" + canonical);
            return ranges != null && !ranges.equals("[]");
        } catch (Exception e) {
            log.debug("Не удалось проверить кэш для {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    public long getQueueLength() {
        Long main = redis.opsForList().size(queueName);
        Long express = redis.opsForList().size(queueName + EXPRESS_QUEUE_SUFFIX);
        return (main != null ? main : 0L) + (express != null ? express : 0L);
    }

    public boolean hasActiveProcessingJob() {
        return Boolean.TRUE.equals(redis.hasKey(ACTIVE_PROCESSING_JOB_KEY));
    }

    public void storeQueueMsgId(String taskId, long userChatId, int msgId) {
        redis.opsForValue().set(
                "queue_msg:" + taskId,
                userChatId + ":" + msgId,
                QUEUE_MSG_TTL_HOURS, TimeUnit.HOURS
        );
    }

    public String getActiveExport(long userId) {
        String taskId = redis.opsForValue().get(ACTIVE_EXPORT_PREFIX + userId);
        if (taskId == null) {
            return null;
        }

        // Проверяем статус задачи одним pipeline-запросом (3 RTT → 1 RTT)
        @SuppressWarnings("unchecked")
        List<Object> statusResults = redis.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations ops) {
                ops.hasKey("job:processing:" + taskId);
                ops.hasKey("job:completed:" + taskId);
                ops.hasKey("job:failed:" + taskId);
                return null;
            }
        });
        boolean isProcessing = Boolean.TRUE.equals(statusResults.get(0));
        boolean isCompleted = Boolean.TRUE.equals(statusResults.get(1));
        boolean isFailed = Boolean.TRUE.equals(statusResults.get(2));

        if (isCompleted || isFailed) {
            // Задача завершена, но ключ не был очищен — чистим
            log.info("Очищаю протухший active_export для user {} (task {} завершена)", userId, taskId);
            redis.delete(ACTIVE_EXPORT_PREFIX + userId);
            return null;
        }

        if (isProcessing) {
            // Задача в обработке — действительно активна
            return taskId;
        }

        // Задача не в processing, не completed, не failed — проверяем обе очереди
        Long queueSize = redis.opsForList().size(queueName);
        Long expressSize = redis.opsForList().size(queueName + EXPRESS_QUEUE_SUFFIX);
        long totalSize = (queueSize != null ? queueSize : 0L) + (expressSize != null ? expressSize : 0L);
        if (totalSize > 0) {
            // В очереди есть задачи — возможно наша ждёт
            return taskId;
        }

        // Нигде не найдена — протухший ключ
        log.info("Очищаю протухший active_export для user {} (task {} не найдена)", userId, taskId);
        redis.delete(ACTIVE_EXPORT_PREFIX + userId);
        return null;
    }

    public void cancelExport(long userId) {
        String taskId = redis.opsForValue().get(ACTIVE_EXPORT_PREFIX + userId);
        if (taskId == null) {
            log.warn("Нет активного экспорта для пользователя {}", userId);
            return;
        }

        // 1. Сначала устанавлием флаг отмены — воркер проверит его при следующей итерации.
        //    Это гарантирует, что даже если задача уже в обработке, она будет остановлена.
        redis.opsForValue().set(
                CANCEL_EXPORT_PREFIX + taskId, "1",
                ACTIVE_EXPORT_TTL_MINUTES, TimeUnit.MINUTES
        );

        // 2. Затем удаляем из очереди (если ещё не взята воркером).
        String json = redis.opsForValue().get(JOB_JSON_PREFIX + taskId);
        if (json != null) {
            String targetQueue = redis.opsForValue().get("job_queue:" + taskId);
            if (targetQueue != null) {
                Long removed = redis.opsForList().remove(targetQueue, 1, json);
                if (removed != null && removed > 0) {
                    log.info("Задача {} удалена из очереди {} (не успела начаться)", taskId, targetQueue);
                }
            } else {
                // Fallback: пробуем обе очереди
                redis.opsForList().remove(queueName, 1, json);
                redis.opsForList().remove(queueName + EXPRESS_QUEUE_SUFFIX, 1, json);
            }
            redis.delete(JOB_JSON_PREFIX + taskId);
            redis.delete("job_queue:" + taskId);
        }

        redis.delete(ACTIVE_EXPORT_PREFIX + userId);
        log.info("Запрошена отмена экспорта {} для пользователя {}", taskId, userId);
        publishStats(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_CANCELLED)
                .taskId(taskId)
                .botUserId(userId)
                .status("cancelled")
                .source("bot")
                .ts(Instant.now())
                .build());
    }

    private void publishExportStarted(String taskId, long userId, Object chatId, Integer topicId,
                                      String fromDate, String toDate,
                                      String keywords, String excludeKeywords) {
        publishStats(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId(taskId)
                .botUserId(userId)
                .chatIdRaw(String.valueOf(chatId))
                .topicId(topicId)
                .fromDate(fromDate)
                .toDate(toDate)
                .keywords(keywords)
                .excludeKeywords(excludeKeywords)
                .status("queued")
                .source("bot")
                .ts(Instant.now())
                .build());
    }

    private void publishStats(StatsEventPayload payload) {
        StatsStreamPublisher publisher = statsPublisherProvider.getIfAvailable();
        if (publisher == null) {
            return;
        }
        try {
            publisher.publish(payload);
        } catch (Exception ex) {
            log.debug("Публикация события статистики не удалась: {}", ex.getMessage());
        }
    }
}
