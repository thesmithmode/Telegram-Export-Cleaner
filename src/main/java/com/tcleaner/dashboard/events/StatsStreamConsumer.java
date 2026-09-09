package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// ACK-стратегия: poison (JsonProcessingException, пустой payload) → ACK, иначе PEL блокируется навсегда.
// Transient (DB/Redis/downstream) → no ACK; runtime retry забирает конкретный RecordId без фонового XPENDING.
// XPENDING используется только для recovery хвоста после старта процесса и редкого race-check после пустого XCLAIM.
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
    private final TaskScheduler retryScheduler;
    private final AtomicBoolean recoveryScheduled = new AtomicBoolean(false);
    private final Set<String> scheduledRecordRetries = ConcurrentHashMap.newKeySet();

    public StatsStreamConsumer(
            ObjectMapper objectMapper,
            StringRedisTemplate redis,
            StatsStreamProperties props,
            ObjectProvider<ExportEventIngestionService> ingestionServiceProvider,
            TaskScheduler retryScheduler
    ) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.props = props;
        this.ingestionServiceProvider = ingestionServiceProvider;
        this.retryScheduler = retryScheduler;
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
            // Transient: запись уже известна, поэтому retry будет адресным XCLAIM именно этого RecordId.
            log.error("Ошибка обработки события {} в {}: {} — XACK пропущен, будет адресный retry",
                    id, props.key(), ex.getMessage());
            scheduleRecordRetry(id, PENDING_RETRY_MIN_IDLE);
        }
        if (ack) {
            try {
                redis.opsForStream().acknowledge(props.key(), props.group(), id);
            } catch (Exception ex) {
                log.warn("Не удалось XACK {}:{}:{}: {}", props.key(), props.group(), id, ex.getMessage());
                scheduleRecordRetry(id, PENDING_RETRY_MIN_IDLE);
            }
        }
    }

    /**
     * Один recovery-проход после старта подбирает PEL, оставшийся после прошлого падения процесса.
     * Если хвоста нет, дальнейших XPENDING в штатном режиме не будет.
     */
    @EventListener(ApplicationReadyEvent.class)
    void recoverPendingOnStartup() {
        scheduleRecovery(Duration.ZERO);
    }

    /**
     * Startup/backlog recovery. XPENDING здесь нужен только потому, что после рестарта RecordId заранее неизвестны.
     */
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

            int observed = 0;
            List<RecordId> staleIds = new ArrayList<>();
            for (PendingMessage message : pending) {
                observed++;
                if (message.getElapsedTimeSinceLastDelivery().compareTo(PENDING_RETRY_MIN_IDLE) >= 0) {
                    staleIds.add(message.getId());
                }
            }

            if (staleIds.isEmpty()) {
                // Recovery увидел только ещё свежий хвост: повтор нужен лишь пока такой хвост реально существует.
                scheduleRecovery(PENDING_RETRY_MIN_IDLE);
                return;
            }

            List<MapRecord<String, String, String>> claimed = stream.claim(
                    props.key(),
                    props.group(),
                    props.consumer(),
                    PENDING_RETRY_MIN_IDLE,
                    staleIds.toArray(RecordId[]::new));
            if (claimed == null || claimed.isEmpty()) {
                scheduleRecovery(PENDING_RETRY_MIN_IDLE);
                return;
            }

            log.info("Recovery: повторная обработка {} pending-событий {}:{}",
                    claimed.size(), props.key(), props.group());
            claimed.forEach(this::onMessage);

            // Продолжаем recovery только если batch мог быть неполным или в нём были ещё свежие записи.
            if (observed >= PENDING_RETRY_BATCH_SIZE
                    || staleIds.size() < observed
                    || claimed.size() < staleIds.size()) {
                scheduleRecovery(PENDING_RETRY_MIN_IDLE);
            }
        } catch (Exception ex) {
            log.warn("Не удалось выполнить recovery pending-событий {}:{}: {}",
                    props.key(), props.group(), ex.getMessage());
            scheduleRecovery(PENDING_RETRY_MIN_IDLE);
        }
    }

    /**
     * Runtime retry известного сообщения: сначала XCLAIM конкретного id, без полного XPENDING-сканирования.
     */
    void retryPendingRecord(String id) {
        if (!props.enabled() || id == null || id.isBlank()) {
            return;
        }

        try {
            StreamOperations<String, String, String> stream = redis.opsForStream();
            List<MapRecord<String, String, String>> claimed = stream.claim(
                    props.key(),
                    props.group(),
                    props.consumer(),
                    PENDING_RETRY_MIN_IDLE,
                    RecordId.of(id));

            if (claimed != null && !claimed.isEmpty()) {
                log.info("Адресный retry pending-события {} в {}:{}", id, props.key(), props.group());
                claimed.forEach(this::onMessage);
                return;
            }

            // Обычно сюда не попадём. Проверяем только этот id, чтобы отличить race по minimum-idle от уже ACK записи.
            PendingMessages pending = stream.pending(
                    props.key(), props.group(), Range.closed(id, id), 1);
            if (pending == null || pending.isEmpty()) {
                return;
            }

            Duration elapsed = Duration.ZERO;
            for (PendingMessage message : pending) {
                elapsed = message.getElapsedTimeSinceLastDelivery();
                break;
            }
            Duration remaining = PENDING_RETRY_MIN_IDLE.minus(elapsed);
            if (remaining.isNegative() || remaining.isZero()) {
                remaining = Duration.ofSeconds(1);
            }
            scheduleRecordRetry(id, remaining);
        } catch (Exception ex) {
            log.warn("Не удалось адресно повторить pending-событие {} в {}:{}: {}",
                    id, props.key(), props.group(), ex.getMessage());
            scheduleRecordRetry(id, PENDING_RETRY_MIN_IDLE);
        }
    }

    private void scheduleRecordRetry(String id, Duration delay) {
        if (!props.enabled() || id == null || id.isBlank() || !scheduledRecordRetries.add(id)) {
            return;
        }
        try {
            retryScheduler.schedule(() -> {
                scheduledRecordRetries.remove(id);
                retryPendingRecord(id);
            }, Instant.now().plus(delay));
        } catch (RuntimeException ex) {
            scheduledRecordRetries.remove(id);
            log.warn("Не удалось запланировать адресный retry {}:{}:{}: {}",
                    props.key(), props.group(), id, ex.getMessage());
        }
    }

    private void scheduleRecovery(Duration delay) {
        if (!props.enabled() || !recoveryScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            retryScheduler.schedule(() -> {
                recoveryScheduled.set(false);
                retryPending();
            }, Instant.now().plus(delay));
        } catch (RuntimeException ex) {
            recoveryScheduled.set(false);
            log.warn("Не удалось запланировать recovery pending-событий {}:{}: {}",
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
