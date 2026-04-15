package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * Consumer для {@code stats:events}. PR-3 — только приём + XACK;
 * реальная запись в БД подключается в PR-4 через {@code ExportEventIngestionService}.
 * <p>
 * Ошибка при обработке одной записи не роняет listener: логируем + XACK
 * (at-least-once на уровне стрима, идемпотентность — на уровне upsert по {@code task_id}).
 */
@Component
public class StatsStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(StatsStreamConsumer.class);
    private static final String PAYLOAD_FIELD = "payload";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final StatsStreamProperties props;

    public StatsStreamConsumer(
            ObjectMapper objectMapper,
            StringRedisTemplate redis,
            StatsStreamProperties props
    ) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.props = props;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String id = message.getId().getValue();
        try {
            String json = message.getValue().get(PAYLOAD_FIELD);
            if (json == null || json.isBlank()) {
                log.warn("Пустой payload в {}: {}", props.key(), id);
            } else {
                StatsEventPayload payload = objectMapper.readValue(json, StatsEventPayload.class);
                handle(payload);
            }
        } catch (Exception ex) {
            log.error("Ошибка обработки события {} в {}: {}", id, props.key(), ex.getMessage());
        } finally {
            try {
                redis.opsForStream().acknowledge(props.key(), props.group(), id);
            } catch (Exception ex) {
                log.warn("Не удалось XACK {}:{}:{}: {}", props.key(), props.group(), id, ex.getMessage());
            }
        }
    }

    /**
     * Временный stub-обработчик: просто логирует событие.
     * PR-4 заменит тело на вызов ingestion-сервиса.
     */
    void handle(StatsEventPayload payload) {
        log.debug("Получено событие {} (task_id={}, botUserId={})",
                payload.getType(), payload.getTaskId(), payload.getBotUserId());
    }
}
