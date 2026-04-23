package com.tcleaner.dashboard.service.subscription;

import com.tcleaner.bot.BotI18n;
import com.tcleaner.bot.BotKeyboards;
import com.tcleaner.bot.BotMessenger;
import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.repository.ChatSubscriptionRepository;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Планировщик confirmation-flow для подписок.
 *
 * <p>Ежедневно в 10:00 МСК (07:00 UTC) выполняет два прохода:
 * <ol>
 *   <li><b>sendConfirmationPrompts</b> — находит ACTIVE-подписки, у которых
 *       {@code lastConfirmAt} старше 7 дней и {@code confirmSentAt == NULL},
 *       отправляет пользователю сообщение с inline-кнопкой подтверждения
 *       и вызывает {@link SubscriptionService#markConfirmSent(long)}.</li>
 *   <li><b>archiveUnconfirmed</b> — находит ACTIVE-подписки, у которых
 *       {@code confirmSentAt} старше 48 часов (пользователь не ответил),
 *       переводит их в статус ARCHIVED через {@link SubscriptionService#archive(long)}
 *       и уведомляет пользователя об архивировании.</li>
 * </ol>
 *
 * <p>Ошибка при обработке одной подписки не прерывает обработку остальных —
 * исключение логируется и итерация продолжается.
 */
@Service
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ConfirmationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationScheduler.class);

    private static final Duration CONFIRM_THRESHOLD = Duration.ofDays(7);
    private static final Duration ARCHIVE_THRESHOLD = Duration.ofHours(48);

    private final ChatSubscriptionRepository repository;
    private final SubscriptionService subscriptionService;
    private final BotMessenger messenger;
    private final BotI18n i18n;
    private final BotKeyboards keyboards;
    private final BotUserUpserter userUpserter;

    public ConfirmationScheduler(ChatSubscriptionRepository repository,
                                 SubscriptionService subscriptionService,
                                 BotMessenger messenger,
                                 BotI18n i18n,
                                 BotKeyboards keyboards,
                                 BotUserUpserter userUpserter) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.messenger = messenger;
        this.i18n = i18n;
        this.keyboards = keyboards;
        this.userUpserter = userUpserter;
    }

    /**
     * Точка входа планировщика. Запускается ежедневно в 10:00 МСК (07:00 UTC).
     * Cron-выражение конфигурируется через {@code subscription.confirmation.cron},
     * дефолт — {@code 0 0 7 * * *}.
     */
    @Scheduled(cron = "${subscription.confirmation.cron:0 0 7 * * *}")
    public void tick() {
        Instant now = Instant.now();
        sendConfirmationPrompts(now);
        archiveUnconfirmed(now);
    }

    /**
     * Первый проход: отправляет confirmation-запрос для подписок,
     * у которых {@code lastConfirmAt} старше 7 дней и запрос ещё не отправлялся.
     *
     * <p>Для каждой подходящей подписки:
     * <ol>
     *   <li>Резолвит язык пользователя.</li>
     *   <li>Отправляет сообщение с inline-кнопкой подтверждения.</li>
     *   <li>Вызывает {@link SubscriptionService#markConfirmSent(long)}.</li>
     * </ol>
     *
     * @param now текущий момент времени (передаётся из {@link #tick()})
     */
    void sendConfirmationPrompts(Instant now) {
        List<ChatSubscription> due = repository.findDueForConfirmation(now.minus(CONFIRM_THRESHOLD));
        for (ChatSubscription sub : due) {
            // markConfirmSent вызываем ДО отправки: если пользователь заблокировал бота,
            // мы всё равно выходим из due-списка и через 48 часов подписка архивируется
            // естественным timeout'ом. Без этого подписка висела бы в выборке вечно.
            try {
                subscriptionService.markConfirmSent(sub.getId());
            } catch (Exception e) {
                log.error("Failed to markConfirmSent for subscription {}: {}", sub.getId(), e.getMessage(), e);
                continue;
            }
            try {
                BotLanguage lang = resolveLang(sub.getBotUserId());
                InlineKeyboardMarkup kb = keyboards.subConfirmKeyboard(lang, sub.getId());
                messenger.sendWithKeyboard(sub.getBotUserId(),
                        i18n.msg(lang, "bot.sub.confirm.request"), kb);
                log.info("Confirmation prompt sent for subscription {}", sub.getId());
            } catch (Exception e) {
                log.error("Failed to send confirmation for subscription {}: {}", sub.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Второй проход: архивирует подписки, в которых пользователь не ответил
     * на запрос подтверждения в течение 48 часов.
     *
     * <p>Для каждой подходящей подписки:
     * <ol>
     *   <li>Вызывает {@link SubscriptionService#archive(long)}.</li>
     *   <li>Резолвит язык пользователя.</li>
     *   <li>Отправляет уведомление об архивировании через {@link BotMessenger#trySend}.</li>
     * </ol>
     *
     * @param now текущий момент времени (передаётся из {@link #tick()})
     */
    void archiveUnconfirmed(Instant now) {
        List<ChatSubscription> due = repository.findDueForArchive(now.minus(ARCHIVE_THRESHOLD));
        for (ChatSubscription sub : due) {
            try {
                subscriptionService.archive(sub.getId());
                BotLanguage lang = resolveLang(sub.getBotUserId());
                messenger.trySend(sub.getBotUserId(), i18n.msg(lang, "bot.sub.archived"));
                log.info("Subscription {} archived after 48h without confirmation", sub.getId());
            } catch (Exception e) {
                log.error("Failed to archive subscription {}: {}", sub.getId(), e.getMessage(), e);
            }
        }
    }

    private BotLanguage resolveLang(long botUserId) {
        return userUpserter.resolveLanguage(botUserId);
    }
}
