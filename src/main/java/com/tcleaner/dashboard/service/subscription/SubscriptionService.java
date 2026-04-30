package com.tcleaner.dashboard.service.subscription;

import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.SubscriptionStatus;
import com.tcleaner.dashboard.repository.ChatSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Set<Integer> ALLOWED_PERIODS = Set.of(24, 48, 72, 168);

    private final ChatSubscriptionRepository repository;

    public SubscriptionService(ChatSubscriptionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ChatSubscription create(long botUserId, long chatRefId, int periodHours,
                                   String desiredTimeMsk, Instant sinceDate) {
        if (!ALLOWED_PERIODS.contains(periodHours)) {
            throw new IllegalArgumentException("period_hours must be 24/48/72/168");
        }
        if (desiredTimeMsk == null || !TIME_PATTERN.matcher(desiredTimeMsk).matches()) {
            throw new IllegalArgumentException("desired_time_msk must be HH:MM");
        }
        Instant now = Instant.now();
        if (sinceDate == null) {
            sinceDate = now;
        }
        if (sinceDate.isAfter(now)) {
            throw new IllegalArgumentException("since_date must not be in the future");
        }
        if (sinceDate.isBefore(now.minusSeconds((long) periodHours * 3600L))) {
            throw new IllegalArgumentException("since_date cannot be earlier than period window");
        }
        if (repository.findByBotUserIdAndStatus(botUserId, SubscriptionStatus.ACTIVE).isPresent()) {
            throw new IllegalStateException("user already has an active subscription");
        }
        // PAUSED-подписка auto-архивируется при создании новой: пользователь хочет
        // сменить чат, не удаляя паузу вручную. ACTIVE не архивируем — это деструктивно.
        repository.findByBotUserIdAndStatus(botUserId, SubscriptionStatus.PAUSED).ifPresent(paused -> {
            paused.setStatus(SubscriptionStatus.ARCHIVED);
            paused.setUpdatedAt(Instant.now());
            repository.save(paused);
            log.info("Auto-archived paused subscription {} for botUserId={} on new create", paused.getId(), botUserId);
        });

        ChatSubscription subscription = ChatSubscription.builder()
                .botUserId(botUserId)
                .chatRefId(chatRefId)
                .periodHours(periodHours)
                .desiredTimeMsk(desiredTimeMsk)
                .sinceDate(sinceDate)
                .status(SubscriptionStatus.ACTIVE)
                .consecutiveFailures(0)
                .lastConfirmAt(now)
                .confirmSentAt(null)
                .lastRunAt(null)
                .lastSuccessAt(null)
                .lastFailureAt(null)
                .createdAt(now)
                .updatedAt(now)
                .build();

        ChatSubscription saved = repository.save(subscription);
        log.info("Subscription created: id={} botUserId={} chatRefId={} period={}",
                saved.getId(), botUserId, chatRefId, periodHours);
        return saved;
    }

    @Transactional
    public ChatSubscription pause(long id) {
        ChatSubscription subscription = findExistingById(id);
        subscription.setStatus(SubscriptionStatus.PAUSED);
        subscription.setUpdatedAt(Instant.now());
        return repository.save(subscription);
    }

    // Idempotent: если уже ACTIVE — no-op. ARCHIVED не резюмировать.
    @Transactional
    public ChatSubscription resume(long id) {
        ChatSubscription subscription = findExistingById(id);
        if (subscription.getStatus() == SubscriptionStatus.ARCHIVED) {
            throw new IllegalStateException("archived subscription cannot be resumed");
        }
        if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
            return subscription;
        }
        Optional<ChatSubscription> existingActive =
                repository.findByBotUserIdAndStatus(subscription.getBotUserId(), SubscriptionStatus.ACTIVE);
        if (existingActive.isPresent()) {
            throw new IllegalStateException("user already has an active subscription");
        }
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setUpdatedAt(Instant.now());
        return repository.save(subscription);
    }

    @Transactional
    public void delete(long id) {
        findExistingById(id);
        repository.deleteById(id);
    }

    @Transactional
    public ChatSubscription archive(long id) {
        ChatSubscription subscription = findExistingById(id);
        subscription.setStatus(SubscriptionStatus.ARCHIVED);
        subscription.setUpdatedAt(Instant.now());
        ChatSubscription saved = repository.save(subscription);
        log.info("Subscription archived: id={}", id);
        return saved;
    }

    @Transactional
    public ChatSubscription recordRunStarted(long id) {
        ChatSubscription subscription = findExistingById(id);
        Instant now = Instant.now();
        subscription.setLastRunAt(now);
        subscription.setUpdatedAt(now);
        return repository.save(subscription);
    }

    // Не бросает NoSuchElementException если подписка удалена между enqueue и завершением —
    // вызывается из Redis Stream ingestion, где RuntimeException → rollback → infinite-retry.
    @Transactional
    public ChatSubscription recordSuccess(long id) {
        ChatSubscription subscription = repository.findById(id).orElse(null);
        if (subscription == null) {
            log.warn("recordSuccess: subscription not found: id={}", id);
            return null;
        }
        Instant now = Instant.now();
        subscription.setLastSuccessAt(now);
        subscription.setConsecutiveFailures(0);
        subscription.setUpdatedAt(now);
        return repository.save(subscription);
    }

    // Не бросает если подписка удалена — см. recordSuccess.
    @Transactional
    public ChatSubscription recordFailure(long id) {
        ChatSubscription subscription = repository.findById(id).orElse(null);
        if (subscription == null) {
            log.warn("recordFailure: subscription not found: id={}", id);
            return null;
        }
        Instant now = Instant.now();
        int failures = subscription.getConsecutiveFailures() + 1;
        subscription.setConsecutiveFailures(failures);
        subscription.setLastFailureAt(now);
        subscription.setUpdatedAt(now);
        if (failures >= 2) {
            subscription.setStatus(SubscriptionStatus.PAUSED);
            log.warn("Subscription paused after consecutive failures: id={} failures={}", id, failures);
        }
        return repository.save(subscription);
    }

    @Transactional
    public ChatSubscription markConfirmSent(long id) {
        ChatSubscription subscription = findExistingById(id);
        Instant now = Instant.now();
        subscription.setConfirmSentAt(now);
        subscription.setUpdatedAt(now);
        return repository.save(subscription);
    }

    @Transactional
    public ChatSubscription confirmReceived(long id) {
        ChatSubscription subscription = findExistingById(id);
        Instant now = Instant.now();
        subscription.setLastConfirmAt(now);
        subscription.setConfirmSentAt(null);
        subscription.setUpdatedAt(now);
        return repository.save(subscription);
    }

    @Transactional(readOnly = true)
    public List<ChatSubscription> listForUser(long botUserId) {
        return repository.findAllByBotUserId(botUserId);
    }

    /**
     * @deprecated Прежний admin-endpoint грузил всю таблицу в память.
     * Новый код должен использовать {@link #listAll(Pageable)}.
     * Текущая реализация ограничена 1000 строк во избежание OOM.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public List<ChatSubscription> listAll() {
        return repository.findAll(PageRequest.of(0, 1000)).getContent();
    }

    @Transactional(readOnly = true)
    public Page<ChatSubscription> listAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Optional<ChatSubscription> findById(long id) {
        return repository.findById(id);
    }

    private ChatSubscription findExistingById(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ChatSubscription not found: id=" + id));
    }
}
