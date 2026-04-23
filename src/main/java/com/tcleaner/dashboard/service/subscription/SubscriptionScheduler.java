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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Планировщик периодического запуска подписок на экспорт чатов.
 *
 * <p><b>Расписание:</b> запускается каждые 5 минут (по умолчанию).
 * Cron-выражение конфигурируется через {@code subscription.scheduler.cron}
 * (default — каждые 5 минут).
 *
 * <p><b>Idle-check (low-priority):</b> перед постановкой задачи проверяется
 * состояние воркера. Если воркер занят ({@code hasActiveProcessingJob = true})
 * или очередь непуста ({@code getQueueLength > 0}), тик пропускается целиком.
 * Это гарантирует, что подписки уступают приоритет ручным экспортам.
 *
 * <p><b>Окно desired_time МСК:</b> подписка считается готовой к запуску,
 * если текущее время МСК ≥ ({@code desired_time_msk} − 30 мин). Верхней
 * границы нет: если воркер был занят, планировщик продолжает ждать
 * освобождения в течение текущего дня. При переходе на следующий день
 * {@code target} автоматически сдвигается вперёд, и подписка тихо ждёт
 * следующего окна.
 *
 * <p><b>Обработка ошибок:</b> любое исключение при постановке одной подписки
 * перехватывается, вызывается {@link SubscriptionService#recordFailure(long)},
 * что при двух подряд неудачах переводит подписку в статус PAUSED.
 */
@Service
public class SubscriptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionScheduler.class);
    private static final ZoneId MSK;

    static {
        ZoneId zone;
        try {
            zone = ZoneId.of("Europe/Moscow");
        } catch (Exception e) {
            log.warn("Europe/Moscow time zone not found, falling back to UTC+3 offset");
            zone = ZoneId.of("UTC+3");
        }
        MSK = zone;
    }

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
     * Основной метод планировщика: находит ACTIVE-подписки, готовые к запуску,
     * и ставит каждую в low-priority очередь {@code telegram_export_subscription}.
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
            if (candidates.isEmpty()) return;

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
            if (!isInDesiredWindow(sub, now)) return;
            if (!isPeriodElapsed(sub, now)) return;
            
            if (chat == null) {
                log.error("Chat not found for subscription {}: chatRefId={}", sub.getId(), sub.getChatRefId());
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
     *
     * <p>Окно открывается за 30 минут до желаемого времени {@code desired_time_msk}
     * (по МСК) и не имеет верхней границы. Если сейчас раньше окна (например,
     * desired_time_msk="23:00", а сейчас 10:00 МСК) — возвращает {@code false}.
     *
     * @param sub подписка с заполненным {@code desiredTimeMsk}
     * @param now текущий момент
     * @return {@code true}, если текущий момент ≥ (target − 30 мин)
     */
    boolean isInDesiredWindow(ChatSubscription sub, Instant now) {
        LocalTime desired = LocalTime.parse(sub.getDesiredTimeMsk(), HHMM);
        ZonedDateTime nowMsk = now.atZone(MSK);
        ZonedDateTime target = nowMsk
                .withHour(desired.getHour())
                .withMinute(desired.getMinute())
                .withSecond(0)
                .withNano(0);
        // Если сегодняшнее desired_time уже прошло — сдвигаем на следующий день,
        // чтобы не открывать окно повторно в тот же день (midnight edge case).
        if (target.toInstant().isBefore(now)) {
            target = target.plusDays(1);
        }
        Instant windowStart = target.toInstant().minus(PREWINDOW);
        // Окно не имеет верхней границы — ждём пока освободится воркер; при смене
        // календарного дня target относится к завтра, windowStart = next day - 30min,
        // поэтому подписка тихо пропускается до следующего дня автоматически.
        return !now.isBefore(windowStart);
    }

    /**
     * Проверяет, что с момента последнего прогона (успех или неудача) прошёл
     * полный период подписки.
     *
     * <p>Учитываем {@code lastSuccessAt}, {@code lastFailureAt} и {@code sinceDate}
     * — берём самое свежее. Это реализует требование ТЗ «retry один раз в
     * следующее окно»: после failure scheduler не должен бросать задачу повторно
     * каждые 5 минут — он ждёт целый период от момента неудачи.
     *
     * @param sub подписка с заполненными {@code periodHours} и {@code sinceDate}
     * @param now текущий момент
     * @return {@code true}, если elapsed ≥ periodHours от последнего прогона
     */
    boolean isPeriodElapsed(ChatSubscription sub, Instant now) {
        Duration period = Duration.ofHours(sub.getPeriodHours());
        Instant last = sub.getSinceDate();
        if (sub.getLastSuccessAt() != null && sub.getLastSuccessAt().isAfter(last)) {
            last = sub.getLastSuccessAt();
        }
        if (sub.getLastFailureAt() != null && sub.getLastFailureAt().isAfter(last)) {
            last = sub.getLastFailureAt();
        }
        return !now.isBefore(last.plus(period));
    }

    /**
     * Ставит одну подписку в low-priority очередь экспорта.
     *
     * <p>Окно экспорта: {@code [lastSuccessAt или sinceDate, now]}.
     * После успешной постановки вызывается {@link SubscriptionService#recordRunStarted(long)}.
     *
     * @param sub  подписка
     * @param chat чат, загруженный batch-запросом в {@link #runDueSubscriptions()}
     * @param now  текущий момент (конец окна экспорта)
     */
    private void enqueueOne(ChatSubscription sub, Chat chat, Instant now) {
        String chatIdentifier = chat.getChatIdRaw();

        // Окно экспорта: [lastSuccessAt или sinceDate, now]
        Instant from = sub.getLastSuccessAt() != null ? sub.getLastSuccessAt() : sub.getSinceDate();
        String fromIso = from.toString();   // ISO-8601
        String toIso = now.toString();

        String taskId = jobProducer.enqueueSubscription(
                sub.getBotUserId(), sub.getBotUserId(), chatIdentifier,
                fromIso, toIso, sub.getId());
        subscriptionService.recordRunStarted(sub.getId());
        log.info("Subscription {} enqueued as {} for chat {}", sub.getId(), taskId, chatIdentifier);
    }
}
