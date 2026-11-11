package com.tcleaner.dashboard.service.stats;

import com.tcleaner.dashboard.config.CacheConfig;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

class StatsCacheInvalidatorTest {

    @Test
    void evictsOnlyAffectedChartsAfterCommit() {
        CacheManager manager = new CacheConfig().cacheManager();
        Cache cache = manager.getCache(CacheConfig.HISTORICAL);
        String[] removed = {"timeSeries:period:exports:42", "timeSeries:other:bytes:42",
                "timeSeries:period:exports:null", "timeSeries:period:exports:0"};
        String[] retained = {"timeSeries:period:exports:142", "topUsers:20:42", "topChats:period:42"};
        for (String key : removed) { cache.put(key, "cached"); }
        for (String key : retained) { cache.put(key, "cached"); }
        TransactionSynchronizationManager.initSynchronization();
        try {
            new StatsCacheInvalidator(manager).afterExportWrite(42L);
            for (String key : removed) { assertThat(cache.get(key)).isNotNull(); }
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            for (String key : removed) { assertThat(cache.get(key)).isNull(); }
            for (String key : retained) { assertThat(cache.get(key)).isNotNull(); }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackKeepsCachedChart() {
        CacheManager manager = new CacheConfig().cacheManager();
        Cache cache = manager.getCache(CacheConfig.HISTORICAL);
        cache.put("timeSeries:period:exports:42", "cached");
        TransactionSynchronizationManager.initSynchronization();
        try {
            new StatsCacheInvalidator(manager).afterExportWrite(42L);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(cache.get("timeSeries:period:exports:42").get()).isEqualTo("cached");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
