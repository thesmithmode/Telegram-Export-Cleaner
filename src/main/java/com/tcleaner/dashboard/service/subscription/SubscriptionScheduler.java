package com.tcleaner.dashboard.service.subscription;

import com.tcleaner.bot.ExportJobProducer;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ChatSubscriptionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SubscriptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionScheduler.class);
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private static final Duration PREWINDOW = Duration.ofMinutes(30);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final ChatSubscriptionRepository repository;
    private final SubscriptionService subscriptionService;
    private final ExportJobProducer jobProducer;
    private final ChatRepository chatRepository;
    private final Counter cyclesCounter;
    private final Counter errorsCounter;

    public SubscriptionScheduler(ChatSubscriptionRepository repository,
                                  SubscriptionService subscriptionService,
                                  ExportJobProducer jobProducer,
                                  ChatRepository chatRepository,
                                  MeterRegistry meterRegistry) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.jobProducer = jobProducer;
        this.chatRepository = chatRepository;
        this.cyclesCounter = Counter.builder("subscription.scheduler.cycles").register(meterRegistry);
        this.errorsCounter = Counter.builder("subscription.scheduler.errors").register(meterRegistry);
    }

    @Scheduled(cron = "${subscription.scheduler.cron:0 */5 * * * *}")
    public void runDueSubscriptions() {
        String cycleId = UUID.randomUUID().toString();
        MDC.put("cycle_id", cycleId);
        MDC.put("scheduler", "subscription");
        cyclesCounter.increment();
        try {
            if (jobProducer.hasActiveProcessingJob() || jobProducer.getQueueLength() > 0) {
                log.debug("Worker busy — subscription scheduler skipped");
                return;
            }

            Instant now = Instant.now();
            List<ChatSubscription> candidates = repository.findDueForRun(now);
            if (candidates.isEmpty()) {
                return;
            }

            Set<Long> chatRefIds = candidates.stream()
                    .map(ChatSubscription::getChatRefId)
                    .collect(Collectors.toSet());

            Map<Long, Chat> chatsById;
            try {
                chatsById = chatRepository.findAllById(chatRefIds).stream()
                        .collect(Collectors.toMap(Chat::getId, c -> c));
            } catch (Exception e) {
                errorsCounter.increment();
                log.error("Failed to fetch chats for subscriptions: {}", e.getMessage());
                return;
            }

            for (ChatSubscription sub : candidates) {
                processCandidate(sub, chatsById.get(sub.getChatRefId()), now);
            }
        } catch (Exception e) {
            errorsCounter.increment();
            log.error("Critical error in SubscriptionScheduler: {}", e.getMessage(), e);
        } finally {
            MDC.remove("cycle_id");
            MDC.remove("scheduler");
        }
    }

    private void processCandidate(ChatSubscription sub, Chat chat, Instant now) {
        MDC.put("subscription_id", String.valueOf(sub.getId()));
        try {
            if (!isInDesiredWindow(sub, now)) {
                return;
            }
            if (!isPeriodElapsed(sub, now)) {
                return;
            }

            if (chat == null) {
                errorsCounter.increment();
                log.error("Chat not found for sub {}: chatRefId={}", sub.getId(), sub.getChatRefId());
                subscriptionService.recordFailure(sub.getId());
                return;
            }
            enqueueOne(sub, chat, now);
        } catch (Exception e) {
            errorsCounter.increment();
            log.error("Subscription {} processing failed: {}", sub.getId(), e.getMessage());
            try {
                subscriptionService.recordFailure(sub.getId());
            } catch (Exception ex) {
                log.error("Failed to record failure for sub {}: {}", sub.getId(), ex.getMessage());
            }
        } finally {
            MDC.remove("subscription_id");
        }
    }

    boolean isInDesiredWindow(ChatSubscription sub, Instant now) {
        LocalTime desired = LocalTime.parse(sub.getDesiredTimeMsk(), HHMM);
        LocalTime currentMsk = LocalTime.from(now.atZone(MSK));
        LocalTime windowStart = desired.minus(PREWINDOW);

        // Midnight crossover: windowStart (e.g. 23:40) is numerically "after" desired (e.g. 00:10)
        if (windowStart.isAfter(desired)) {
            LocalTime windowEnd = desired.plusHours(6);
            return currentMsk.isAfter(windowStart) || currentMsk.isBefore(windowEnd);
        }

        // Normal case: open-ended — fires any time from windowStart onward.
        // isPeriodElapsed prevents double-fire after successful run.
        return currentMsk.isAfter(windowStart);
    }

    boolean isPeriodElapsed(ChatSubscription sub, Instant now) {
        // Якорь — последнее "событие" по подписке: успех, попытка или провал.
        // Без учёта lastRunAt scheduler зацикливался каждые 5 мин, пока worker не завершится
        // и ingestion не проставит lastSuccessAt. lastFailureAt аналогично блокирует повтор
        // до следующего окна периода (consecutive_failures=2 → PAUSED обрабатывается отдельно).
        Instant anchor = latest(sub.getLastSuccessAt(), sub.getLastRunAt(), sub.getLastFailureAt());
        if (anchor == null) {
            return now.isAfter(sub.getSinceDate());
        }
        Instant nextRunMin = anchor.plus(Duration.ofHours(sub.getPeriodHours())).minus(PREWINDOW);
        return now.isAfter(nextRunMin);
    }

    private static Instant latest(Instant... instants) {
        Instant max = null;
        for (Instant i : instants) {
            if (i != null && (max == null || i.isAfter(max))) {
                max = i;
            }
        }
        return max;
    }

    private void enqueueOne(ChatSubscription sub, Chat chat, Instant now) {
        String chatIdentifier = chat.getCanonicalChatId();
        
        // Усекаем до секунд: Python worker валидирует формат до HH:MM:SS,
        // наносекунды из Instant.now() отправляют job в DLQ.
        // Диапазон строим в UTC: worker (ensure_utc) трактует naive datetime как UTC.
        // Если отправлять МСК без offset, последние 3 часа (МСК) выпадают из выборки.
        LocalDateTime ldtUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        String fromIso = ldtUtc.minusHours(sub.getPeriodHours()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String toIso = ldtUtc.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        subscriptionService.recordRunStarted(sub.getId());
        String taskId = jobProducer.enqueueSubscription(sub.getBotUserId(), sub.getBotUserId(),
                chatIdentifier, fromIso, toIso, sub.getId());
        log.info("Subscription {} enqueued as {} for chat {}", sub.getId(), taskId, chatIdentifier);
    }
}
