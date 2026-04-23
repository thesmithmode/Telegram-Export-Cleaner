package com.tcleaner.dashboard.service.subscription;

import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.SubscriptionStatus;
import com.tcleaner.dashboard.repository.ChatSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Сервис управления подписками пользователей на периодический экспорт чатов.
 *
 * <p>Ограничения alpha-теста:
 * <ul>
 *   <li>Не более одной {@link SubscriptionStatus#ACTIVE} подписки на пользователя.</li>
 *   <li>Допустимые периоды: 24, 48, 72, 168 часов.</li>
 *   <li>Две подряд неудачи переводят подписку в {@link SubscriptionStatus#PAUSED}.</li>
 * </ul>
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Set<Integer> ALLOWED_PERIODS = Set.of(24, 48, 72, 168);

    private final ChatSubscriptionRepository repository;

    public SubscriptionService(ChatSubscriptionRepository repository) {
        this.repository = repository;
    }

    /**
     * Создаёт новую ACTIVE-подписку для пользователя.
     *
     * <p>Порядок валидации:
     * <ol>
     *   <li>{@code periodHours} ∈ {24, 48, 72, 168}.</li>
     *   <li>{@code desiredTimeMsk} != null и соответствует формату "HH:MM".</li>
     *   <li>{@code sinceDate} != null.</li>
     *   <li>{@code sinceDate} не в будущем.</li>
     *   <li>{@code sinceDate} не старше одного периода от текущего момента.</li>
     *   <li>У пользователя уже нет ACTIVE-подписки.</li>
     * </ol>
     *
     * @param botUserId     идентификатор пользователя бота
     * @param chatRefId     идентификатор чата
     * @param periodHours   период экспорта в часах (24 / 48 / 72 / 168)
     * @param desiredTimeMsk желаемое время запуска по МСК, формат "HH:MM"
     * @param sinceDate     начало отсчёта периода (не в будущем, не старше periodHours)
     * @return сохранённая подписка
     * @throws IllegalArgumentException при нарушении любого ограничения на аргументы
     * @throws IllegalStateException    если у пользователя уже есть ACTIVE-подписка
     */
    @Transactional
    public ChatSubscription create(long botUserId, long chatRefId, int periodHours,
                                   String desiredTimeMsk, Instant sinceDate) {
        if (!ALLOWED_PERIODS.contains(periodHours)) {
            throw new IllegalArgumentException("period_hours must be 24/48/72/168");
        }
        if (desiredTimeMsk == null || !TIME_PATTERN.matcher(desiredTimeMsk).matches()) {
            throw new IllegalArgumentException("desired_time_msk must be HH:MM");
        }
        if (sinceDate == null) {
            throw new IllegalArgumentException("since_date must not be null");
        }
        Instant now = Instant.now();
        if (sinceDate.isAfter(now)) {
            throw new IllegalArgumentException("since_date must not be in the future");
        }
        if (sinceDate.isBefore(now.minusSeconds((long) periodHours * 3600L))) {
            throw new IllegalArgumentException("since_date cannot be earlier than period window");
        }
        if (repository.findByBotUserIdAndStatus(botUserId, SubscriptionStatus.ACTIVE).isPresent()) {
            throw new IllegalStateException("user already has an active subscription");
        }
        if (repository.findByBotUserIdAndStatus(botUserId, SubscriptionStatus.PAUSED).isPresent()) {
            throw new IllegalStateException("user has a paused subscription — resume or delete it first");
        }

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

    /**
     * Приостанавливает подписку — переводит статус в {@link SubscriptionStatus#PAUSED}.
     *
     * @param id идентификатор подписки
     * @return обновлённая подписка
     * @throws NoSuchElementException если подписка не найдена
     */
    @Transactional
    public ChatSubscription pause(long id) {
        ChatSubscription subscription = findExistingById(id);
        subscription.setStatus(SubscriptionStatus.PAUSED);
        subscription.setUpdatedAt(Instant.now());
        return repository.save(subscription);
    }

    /**
     * Возобновляет подписку — переводит статус в {@link SubscriptionStatus#ACTIVE}.
     *
     * <p>Idempotent: если подписка уже ACTIVE, возвращается без изменений.
     * Если у того же пользователя уже есть другая ACTIVE-подписка — бросает
     * {@link IllegalStateException}.
     *
     * @param id идентификатор подписки
     * @return обновлённая (или неизменённая) подписка
     * @throws NoSuchElementException если подписка не найдена
     * @throws IllegalStateException  если у пользователя уже есть другая ACTIVE-подписка
     */
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

    /**
     * Hard delete подписки по идентификатору.
     *
     * @param id идентификатор подписки
     * @throws NoSuchElementException если подписка не найдена
     */
    @Transactional
    public void delete(long id) {
        findExistingById(id);
        repository.deleteById(id);
    }

    /**
     * Архивирует подписку — переводит статус в {@link SubscriptionStatus#ARCHIVED}.
     *
     * @param id идентификатор подписки
     * @return обновлённая подписка
     * @throws NoSuchElementException если подписка не найдена
     */
    @Transactional
    public ChatSubscription archive(long id) {
        ChatSubscription subscription = findExistingById(id);
        subscription.setStatus(SubscriptionStatus.ARCHIVED);
        subscription.setUpdatedAt(Instant.now());
        ChatSubscription saved = repository.save(subscription);
        log.info("Subscription archived: id={}", id);
        return saved;
    }

    /**
     * Фиксирует начало очередного прогона: обновляет {@code lastRunAt} и {@code updatedAt}.
     *
     * @param id идентификатор подписки
     * @return обновлённая подписка
     * @throws NoSuchElementException если подписка не найдена
     */
    @Transactional
    public ChatSubscription recordRunStarted(long id) {
        ChatSubscription subscription = findExistingById(id);
        Instant now = Instant.now();
        subscription.setLastRunAt(now);
        subscription.setUpdatedAt(now);
        return repository.save(subscription);
    }

    /**
     * Фиксирует успешный прогон: обновляет {@code lastSuccessAt},
     * сбрасывает {@code consecutiveFailures} в 0 и обновляет {@code updatedAt}.
     * Статус не меняется.
     *
     * <p>Если подписка не найдена (была удалена между enqueue и завершением экспорта) —
     * логируется warning и возвращается {@code null}. Метод намеренно не бросает
     * {@link NoSuchElementException}: вызывается из ingest-пайплайна через
     * Redis Stream, где любой RuntimeException приводит к rollback outer-транзакции
     * и infinite-retry события.
     *
     * @param id идентификатор подписки
     * @return обновлённая подписка или {@code null}, если подписка не найдена
     */
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

    /**
     * Фиксирует неудачный прогон: инкрементирует {@code consecutiveFailures},
     * обновляет {@code lastFailureAt} и {@code updatedAt}.
     * Если {@code consecutiveFailures} достигает 2 и более — переводит подписку в
     * {@link SubscriptionStatus#PAUSED}.
     *
     * <p>Если подписка не найдена (была удалена между enqueue и завершением экспорта) —
     * логируется warning и возвращается {@code null}. Метод намеренно не бросает
     * {@link NoSuchElementException}: вызывается из ingest-пайплайна через
     * Redis Stream, где любой RuntimeException приводит к rollback outer-транзакции
     * и infinite-retry события.
     *
     * @param id идентификатор подписки
     * @return обновлённая подписка или {@code null}, если подписка не найдена
     */
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

    /**
     * Фиксирует отправку confirm-запроса: устанавливает {@code confirmSentAt = now}.
     *
     * @param id идентификатор подписки
     * @return обновлённая подписка
     * @throws NoSuchElementException если подписка не найдена
     */
    @Transactional
    public ChatSubscription markConfirmSent(long id) {
        ChatSubscription subscription = findExistingById(id);
        Instant now = Instant.now();
        subscription.setConfirmSentAt(now);
        subscription.setUpdatedAt(now);
        return repository.save(subscription);
    }

    /**
     * Фиксирует получение подтверждения от пользователя:
     * устанавливает {@code lastConfirmAt = now} и сбрасывает {@code confirmSentAt = null}.
     *
     * @param id идентификатор подписки
     * @return обновлённая подписка
     * @throws NoSuchElementException если подписка не найдена
     */
    @Transactional
    public ChatSubscription confirmReceived(long id) {
        ChatSubscription subscription = findExistingById(id);
        Instant now = Instant.now();
        subscription.setLastConfirmAt(now);
        subscription.setConfirmSentAt(null);
        subscription.setUpdatedAt(now);
        return repository.save(subscription);
    }

    /**
     * Возвращает все подписки пользователя для отображения на дашборде.
     *
     * @param botUserId идентификатор пользователя
     * @return список подписок (может быть пустым)
     */
    @Transactional(readOnly = true)
    public List<ChatSubscription> listForUser(long botUserId) {
        return repository.findAllByBotUserId(botUserId);
    }

    /**
     * Возвращает все подписки (для ADMIN-роли).
     *
     * @return список всех подписок
     */
    @Transactional(readOnly = true)
    public List<ChatSubscription> listAll() {
        return repository.findAll();
    }

    /**
     * Ищет подписку по идентификатору.
     *
     * @param id идентификатор подписки
     * @return {@link Optional} с подпиской или пустой
     */
    @Transactional(readOnly = true)
    public Optional<ChatSubscription> findById(long id) {
        return repository.findById(id);
    }

    // ------------------------------------------------------------------ helpers

    private ChatSubscription findExistingById(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ChatSubscription not found: id=" + id));
    }
}
