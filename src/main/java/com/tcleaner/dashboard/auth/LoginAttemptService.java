package com.tcleaner.dashboard.auth;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory счётчик неудачных попыток входа.
 * После {@value #MAX_ATTEMPTS} неудач аккаунт блокируется на {@value #LOCK_MINUTES} минут.
 * При рестарте JVM история сбрасывается — приемлемо для текущей нагрузки.
 */
@Service
public class LoginAttemptService {

    static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 5;

    private record Attempt(int count, Instant blockedUntil) {}

    private final ConcurrentHashMap<String, Attempt> cache = new ConcurrentHashMap<>();

    /** Вызывается при успешном входе — сбрасывает счётчик. */
    public void recordSuccess(String username) {
        cache.remove(username);
    }

    /** Вызывается при неудачном входе — инкрементирует и, при достижении лимита, блокирует. */
    public void recordFailure(String username) {
        cache.compute(username, (k, v) -> {
            int count = (v == null ? 0 : v.count()) + 1;
            Instant blockedUntil = count >= MAX_ATTEMPTS
                    ? Instant.now().plus(Duration.ofMinutes(LOCK_MINUTES))
                    : null;
            return new Attempt(count, blockedUntil);
        });
    }

    /**
     * Возвращает {@code true} если пользователь заблокирован.
     * Просроченная блокировка снимается автоматически.
     */
    public boolean isBlocked(String username) {
        Attempt a = cache.get(username);
        if (a == null || a.blockedUntil() == null) return false;
        if (Instant.now().isAfter(a.blockedUntil())) {
            cache.remove(username);
            return false;
        }
        return true;
    }

    /** Возвращает текущее число неудачных попыток (для тестов). */
    int failureCount(String username) {
        Attempt a = cache.get(username);
        return a == null ? 0 : a.count();
    }
}
