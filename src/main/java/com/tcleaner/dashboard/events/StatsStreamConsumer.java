package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// ACK-стратегия: poison (JsonProcessingException, пустой payload) → ACK, иначе PEL блокируется навсегда.
// Transient (DB/Redis/downstream) → no ACK; retryPending() повторно забирает stale PEL через XCLAIM.
// ObjectProvider: ingestion bean может отсутствовать в unit-тестах без Spring-контекста.
@Component
public class StatsStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(StatsStreamConsumer.class);
    private static final String PAYLOAD_FIELD = "payload";
    private static final int PENDING_RETRY_BATCH_SIZE = 100;
    private static final Duration PENDING_RETRY_MIN_IDLE = Duration.ofSeconds(30);

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
            // Transient (Redis/DB/downstream): не ACK → запись остаётся в PEL и будет retry.
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

    /**
     * Повторно обрабатывает stale pending-события consumer group.
     *
     * <p>Обычный listener читает {@code lastConsumed()} и поэтому не возвращается к PEL.
     * XCLAIM с minimum idle time защищает от параллельной повторной обработки события,
     * которое прямо сейчас ещё находится в исходном {@link #onMessage(MapRecord)}.</p>
     */
    @Scheduled(fixedDelayString = "${dashboard.stats.stream.pending-retry-delay-ms:30000}")
    void retryPending() {
        if (!props.enabled()) {
            return;
        }

        try {
            StreamOperations<String, String, String> stream = redis.opsForStream();
            PendingMessages pending = stream.pending(
                    props.key(), props.group(), Range.unbounded(), PENDING_RETRY_BATCH_SIZE);
            if (pending == null || pending.isEmpty()) {
                return;
            }

            List<RecordId> staleIds = new ArrayList<>();
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().compareTo(PENDING_RETRY_MIN_IDLE) >= 0) {
                    staleIds.add(message.getId());
                }
            }
            if (staleIds.isEmpty()) {
                return;
            }

            List<MapRecord<String, String, String>> claimed = stream.claim(
                    props.key(),
                    props.group(),
                    props.consumer(),
                    PENDING_RETRY_MIN_IDLE,
                    staleIds.toArray(RecordId[]::new));
            if (claimed == null || claimed.isEmpty()) {
                return;
            }

            log.info("Повторная обработка {} pending-событий {}:{}",
                    claimed.size(), props.key(), props.group());
            claimed.forEach(this::onMessage);
        } catch (Exception ex) {
            log.warn("Не удалось повторно обработать pending-события {}:{}: {}",
                    props.key(), props.group(), ex.getMessage());
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
