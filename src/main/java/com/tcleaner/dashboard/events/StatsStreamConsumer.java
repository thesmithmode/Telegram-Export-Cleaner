package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * Consumer для {@code stats:events}. Десериализует payload в {@link StatsEventPayload}
 * и делегирует обработку {@link ExportEventIngestionService}.
 * <p>
 * Ошибка при обработке одной записи не роняет listener: логируем + XACK
 * (at-least-once на уровне стрима, идемпотентность — на уровне upsert по {@code task_id}).
 * {@code ObjectProvider} — чтобы в тестах, где ingestion bean нет (@DataJpaTest,
 * unit-тесты без Spring-контекста), consumer создавался без NPE.
 */
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
     * Делегирует обработку в {@link ExportEventIngestionService}. Если бин ingestion-а
     * отсутствует (тесты с отключённым stream'ом) — только логируем.
     */
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
