package com.tcleaner.dashboard.service;

import com.tcleaner.bot.BotMessenger;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.repository.BotUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.Objects;

import static com.tcleaner.dashboard.config.CacheConfig.FEEDBACK_RATE_LIMIT;

/**
 * Доставка feedback админу через бот. Rate-limit 1/5min per botUserId:
 * дешевле полноценной антиспам-системы и достаточно при низкой частоте жалоб.
 * ADMIN → {@link Result#FORBIDDEN} чтобы не было петли self-feedback.
 * TG API down → {@link Result#SEND_FAILED} + eviction слота: юзер retry без штрафа.
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    public enum Result { SENT, RATE_LIMITED, SEND_FAILED, FORBIDDEN, BLOCKED }

    public static final String BLOCK_PREFIX = "feedback:block:";
    public static final String UNBLOCK_PREFIX = "feedback:unblock:";

    @Nullable
    private final BotMessenger messenger;
    private final BotUserRepository botUsers;
    private final Cache rateLimit;
    private final long adminTelegramId;

    // Optional BotMessenger: без токена бота сервис живёт и отдаёт 503,
    // вместо отказа всего application context.
    public FeedbackService(@Autowired(required = false) @Nullable BotMessenger messenger,
                           BotUserRepository botUsers,
                           CacheManager cacheManager,
                           @Value("${dashboard.auth.admin.telegram-id}") long adminTelegramId) {
        this.messenger = messenger;
        this.botUsers = botUsers;
        this.rateLimit = Objects.requireNonNull(
                cacheManager.getCache(FEEDBACK_RATE_LIMIT),
                "cache " + FEEDBACK_RATE_LIMIT + " is not configured (see CacheConfig)");
        this.adminTelegramId = adminTelegramId;
    }

    public Result submit(long botUserId, String rawMessage) {
        Objects.requireNonNull(rawMessage, "rawMessage");
        if (messenger == null) {
            log.warn("Feedback rejected (messenger unavailable, bot token missing) botUserId={}", botUserId);
            return Result.SEND_FAILED;
        }
        if (botUserId == adminTelegramId) {
            log.info("Feedback FORBIDDEN: admin self-feedback blocked botUserId={}", botUserId);
            return Result.FORBIDDEN;
        }

        BotUser user = botUsers.findById(botUserId).orElse(null);
        if (user != null && user.isFeedbackBlocked()) {
            return Result.BLOCKED;
        }

        // putIfAbsent — атомарный CAS claim слота, без гонки check-then-act.
        if (rateLimit.putIfAbsent(botUserId, Boolean.TRUE) != null) {
            log.info("Feedback RATE_LIMITED botUserId={}", botUserId);
            return Result.RATE_LIMITED;
        }

        String username = user != null ? user.getUsername() : null;
        String displayName = user != null ? user.getDisplayName() : null;

        String text = format(botUserId, username, displayName, rawMessage.trim());
        if (!messenger.trySendWithKeyboard(adminTelegramId, text, moderationKeyboard(botUserId))) {
            // Освобождаем слот: ошибка доставки не расходует лимит пользователя.
            rateLimit.evict(botUserId);
            log.warn("Feedback SEND_FAILED: TG API failure для botUserId={}", botUserId);
            return Result.SEND_FAILED;
        }

        log.info("Feedback SENT админу: botUserId={} chars={}", botUserId, text.length());
        return Result.SENT;
    }

    /** Both actor and destination must be the configured admin's private chat. */
    public boolean moderate(long actorId, long chatId, long targetId, boolean blocked) {
        if (adminTelegramId <= 0 || actorId != adminTelegramId || chatId != adminTelegramId
                || targetId <= 0 || targetId == adminTelegramId) {
            return false;
        }
        return botUsers.setFeedbackBlocked(targetId, blocked) == 1;
    }

    public static InlineKeyboardMarkup moderationKeyboard(long userId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("🚫 Запретить обратную связь")
                        .callbackData(BLOCK_PREFIX + userId).build()))
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("✅ Разрешить обратную связь")
                        .callbackData(UNBLOCK_PREFIX + userId).build()))
                .build();
    }

    // Plain-text: отправка идёт без parse_mode, поэтому Markdown/HTML спецсимволы
    // в message не интерпретируются — injection в TG невозможен.
    private static String format(long botUserId, String username, String displayName, String message) {
        String handle = (username == null || username.isBlank()) ? "(no username)" : "@" + username;
        String name = (displayName == null || displayName.isBlank()) ? "—" : displayName;
        return "📨 Обратная связь\n"
                + "От: " + handle + " (id: " + botUserId + ")\n"
                + "Имя: " + name + "\n\n"
                + message;
    }
}
