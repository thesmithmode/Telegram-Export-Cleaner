package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр {@link UserSession} с автоэвикцией. Выделено из ExportBot
 * (God class). ConcurrentHashMap безопасен для concurrent {@code consume}
 * и {@code @Scheduled} eviction.
 */
@Component
public class BotSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(BotSessionRegistry.class);

    private static final long SESSION_EVICT_DELAY_MS = 30 * 60 * 1000L;
    private static final Duration STALE_AFTER = Duration.ofHours(2);

    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();

    /**
     * Возвращает сессию пользователя, создавая при отсутствии. Обновляет
     * {@code lastAccess} для антиэвикции.
     */
    public UserSession get(long userId) {
        UserSession session = sessions.computeIfAbsent(userId, k -> new UserSession());
        session.touch();
        return session;
    }

    @Scheduled(fixedDelay = SESSION_EVICT_DELAY_MS)
    public void evictStaleSessions() {
        if (sessions.isEmpty()) return;
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        int beforeSize = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().getLastAccess().isBefore(cutoff));
        int removed = beforeSize - sessions.size();
        if (removed > 0) {
            log.info("Evicted {} stale sessions. Current count: {}", removed, sessions.size());
        }
    }
}
