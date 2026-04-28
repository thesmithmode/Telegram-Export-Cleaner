package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

// ACK-стратегия: poison (JsonProcessingException, пустой payload) → ACK, иначе PEL блокируется навсегда.
// Transient (DB/Redis/downstream) → no ACK → at-least-once retry (idempotent по task_id).
// ObjectProvider: ingestion bean может отсутствовать в unit-тестах без Spring-контекста.
@Component
public class StatsStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(StatsStreamConsumer.class);
    private static final String PAYLOAD_FIELD = "payload";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final StatsStreamProperties props;
    private final ObjectProvider<ExportEventIngestionService> ingestionServiceProvider;

    public StatsStreamConsumer(
            ObjectMapper objectMapper,
            StringRedisTemplate redis,
            StatsStreamProperties props,
            ObjectProvider<ExportEventIngestionService> ingestionServiceProvider
    ) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.props = props;
        this.ingestionServiceProvider = ingestionServiceProvider;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String id = message.getId().getValue();
        boolean ack = false;
        try {
            String json = message.getValue().get(PAYLOAD_FIELD);
            if (json == null || json.isBlank()) {
                log.warn("Пустой payload в {}: {} — ACK для выхода из PEL", props.key(), id);
                ack = true; // poison: повтор не вернёт payload
            } else {
                StatsEventPayload payload = objectMapper.readValue(json, StatsEventPayload.class);
                handle(payload);
                ack = true;
            }
        } catch (JsonProcessingException ex) {
            // Poison: парсинг никогда не пройдёт → ACK, иначе событие блокирует PEL навсегда.
            log.error("Битый JSON в {} id={}: {} — ACK (poison)", props.key(), id, ex.getMessage());
            ack = true;
        } catch (Exception ex) {
            // Transient (Redis/DB/downstream): не ACK → повтор. Ingestion идемпотентен по task_id.
            log.error("Ошибка обработки события {} в {}: {} — XACK пропущен, будет retry",
                    id, props.key(), ex.getMessage());
        }
        if (ack) {
            try {
                redis.opsForStream().acknowledge(props.key(), props.group(), id);
            } catch (Exception ex) {
                log.warn("Не удалось XACK {}:{}:{}: {}", props.key(), props.group(), id, ex.getMessage());
            }
        }
    }

    void handle(StatsEventPayload payload) {
        ExportEventIngestionService service = ingestionServiceProvider.getIfAvailable();
        if (service != null) {
            service.ingest(payload);
            return;
        }
        log.debug("Ingestion service отсутствует — событие {} (task_id={}) проигнорировано",
                payload.getType(), payload.getTaskId());
    }
}
