package com.tcleaner.bot;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BotSecurityGate {

    private static final Logger log = LoggerFactory.getLogger(BotSecurityGate.class);

    private static final String BLOCKED_KEY_PREFIX = "bot:blocked:";
    private static final int FLOOD_LIMIT = 3;
    private static final Duration FLOOD_WINDOW = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;

    private final Cache<Long, AtomicInteger> floodCounters = Caffeine.newBuilder()
            .expireAfterWrite(FLOOD_WINDOW)
            .build();

    public BotSecurityGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isBlocked(long userId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(BLOCKED_KEY_PREFIX + userId));
        } catch (Exception e) {
            log.warn("Redis unavailable for blacklist check userId={}: fail-open", userId);
            return false;
        }
    }

    public boolean isFlooded(long userId) {
        AtomicInteger counter = floodCounters.get(userId, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        if (count == FLOOD_LIMIT + 1) {
            log.warn("Flood from userId={}: {} messages in {}s window", userId, count, FLOOD_WINDOW.getSeconds());
        }
        return count > FLOOD_LIMIT;
    }
}
