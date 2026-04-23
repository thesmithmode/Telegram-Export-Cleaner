package com.tcleaner.dashboard.service.subscription;

import com.tcleaner.bot.ExportJobProducer;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ChatSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Планировщик периодических подписок.
 * Находит активные подписки, у которых истек период, и ставит их в очередь.
 */
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

    public SubscriptionScheduler(ChatSubscriptionRepository repository,
                                  SubscriptionService subscriptionService,
                                  ExportJobProducer jobProducer,
                                  ChatRepository chatRepository) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.jobProducer = jobProducer;
        this.chatRepository = chatRepository;
    }

    /**
     * Основной метод планировщика: находит ACTIVE-подписки, готовые к запуску.
     */
    @Scheduled(cron = "${subscription.scheduler.cron:0 */5 * * * *}")
    public void runDueSubscriptions() {
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
                log.error("Failed to fetch chats for subscriptions: {}", e.getMessage());
                return;
            }

            for (ChatSubscription sub : candidates) {
                processCandidate(sub, chatsById.get(sub.getChatRefId()), now);
            }
        } catch (Exception e) {
            log.error("Critical error in SubscriptionScheduler: {}", e.getMessage(), e);
        }
    }

    private void processCandidate(ChatSubscription sub, Chat chat, Instant now) {
        try {
            if (!isInDesiredWindow(sub, now)) {
                return;
            }
            if (!isPeriodElapsed(sub, now)) {
                return;
            }
            
            if (chat == null) {
                log.error("Chat not found for sub {}: chatRefId={}", sub.getId(), sub.getChatRefId());
                subscriptionService.recordFailure(sub.getId());
                return;
            }
            enqueueOne(sub, chat, now);
        } catch (Exception e) {
            log.error("Subscription {} processing failed: {}", sub.getId(), e.getMessage());
            try {
                subscriptionService.recordFailure(sub.getId());
            } catch (Exception ex) {
                log.error("Failed to record failure for sub {}: {}", sub.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Проверяет, попадает ли текущий момент в допустимое окно запуска подписки.
     */
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
        Instant lastSuccess = sub.getLastSuccessAt();
        if (lastSuccess == null) {
            return now.isAfter(sub.getSinceDate());
        }
        Instant nextRunMin = lastSuccess.plus(Duration.ofHours(sub.getPeriodHours())).minus(PREWINDOW);
        return now.isAfter(nextRunMin);
    }

    private void enqueueOne(ChatSubscription sub, Chat chat, Instant now) {
        String chatIdentifier = chat.getCanonicalChatId();
        
        // Усекаем до секунд: Python worker валидирует формат до HH:MM:SS,
        // наносекунды из Instant.now() отправляют job в DLQ.
        LocalDateTime ldt = LocalDateTime.ofInstant(now, MSK).truncatedTo(ChronoUnit.SECONDS);
        String fromIso = ldt.minusHours(sub.getPeriodHours()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String toIso = ldt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        String taskId = jobProducer.enqueueSubscription(sub.getBotUserId(), sub.getBotUserId(),
                chatIdentifier, fromIso, toIso, sub.getId());
        subscriptionService.recordRunStarted(sub.getId());
        log.info("Subscription {} enqueued as {} for chat {}", sub.getId(), taskId, chatIdentifier);
    }
}
