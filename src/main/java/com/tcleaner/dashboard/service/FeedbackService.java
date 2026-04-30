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

import java.util.Objects;

import static com.tcleaner.dashboard.config.CacheConfig.FEEDBACK_RATE_LIMIT;

/**
 * Доставка feedback админу через бот. Rate-limit 1/60s per botUserId:
 * дешевле полноценной антиспам-системы и достаточно при низкой частоте жалоб.
 * ADMIN → {@link Result#FORBIDDEN} чтобы не было петли self-feedback.
 * TG API down → {@link Result#SEND_FAILED} + eviction слота: юзер retry без штрафа.
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    public enum Result { SENT, RATE_LIMITED, SEND_FAILED, FORBIDDEN }

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

        // putIfAbsent — атомарный CAS claim слота, без гонки check-then-act.
        if (rateLimit.putIfAbsent(botUserId, Boolean.TRUE) != null) {
            log.info("Feedback RATE_LIMITED botUserId={}", botUserId);
            return Result.RATE_LIMITED;
        }

        BotUser user = botUsers.findById(botUserId).orElse(null);
        String username = user != null ? user.getUsername() : null;
        String displayName = user != null ? user.getDisplayName() : null;

        String text = format(botUserId, username, displayName, rawMessage.trim());
        if (!messenger.trySend(adminTelegramId, text)) {
            // Освобождаем слот: юзер должен иметь возможность повторить без 60-секундной кары.
            rateLimit.evict(botUserId);
            log.warn("Feedback SEND_FAILED: TG API failure для botUserId={}", botUserId);
            return Result.SEND_FAILED;
        }

        log.info("Feedback SENT админу: botUserId={} chars={}", botUserId, text.length());
        return Result.SENT;
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
