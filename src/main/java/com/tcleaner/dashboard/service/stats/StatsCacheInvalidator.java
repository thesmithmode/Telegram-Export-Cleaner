package com.tcleaner.dashboard.service.stats;

import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.tcleaner.dashboard.config.CacheConfig.HISTORICAL;

/** Invalidate only affected charts, after their source rows become visible. */
@Component
public class StatsCacheInvalidator {

    private final CacheManager caches;

    public StatsCacheInvalidator(CacheManager caches) {
        this.caches = caches;
    }

    public void afterExportWrite(Long botUserId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateTimeSeries(botUserId);
            }
        });
    }

    private void invalidateTimeSeries(Long botUserId) {
        if (caches.getCache(HISTORICAL) instanceof CaffeineCache cache) {
            String userSuffix = ":" + botUserId;
            // At most 1000 in-memory keys. No Redis/SQL, no unrelated users or aggregates.
            cache.getNativeCache().asMap().keySet().removeIf(key -> key instanceof String s
                    && s.startsWith("timeSeries:")
                    && (s.endsWith(":null") || s.endsWith(":0") || s.endsWith(userSuffix)));
        }
    }
}
