package com.tcleaner.dashboard.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine in-memory cache для dashboard stats.
 *
 * Три тира по freshness:
 *   stats-live       (30s)  — overview, recentEvents: юзер видит свежий результат после экспорта
 *   stats-historical (300s) — timeSeries, topChats, topUsers, statusBreakdown: агрегаты меняются медленно
 *   stats-profile    (600s) — userDetail: профиль почти статичен
 *
 * Память при 1000 юзерах: ~35 MB суммарно (безопасно для 3.3 GB сервера).
 *
 * Безопасность: ключ кеша включает botUserId — данные юзеров физически изолированы.
 * ADMIN-запросы (botUserId=null) кешируются отдельно от USER-запросов.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String LIVE       = "stats-live";
    public static final String HISTORICAL = "stats-historical";
    public static final String PROFILE    = "stats-profile";
    /** Rate-limit для feedback-формы: 1 сообщение / 60s на botUserId. */
    public static final String FEEDBACK_RATE_LIMIT = "feedback-rate-limit";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        manager.registerCustomCache(LIVE,
                Caffeine.newBuilder()
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .maximumSize(500)
                        .build());

        manager.registerCustomCache(HISTORICAL,
                Caffeine.newBuilder()
                        .expireAfterWrite(300, TimeUnit.SECONDS)
                        .maximumSize(1000)
                        .build());

        manager.registerCustomCache(PROFILE,
                Caffeine.newBuilder()
                        .expireAfterWrite(600, TimeUnit.SECONDS)
                        .maximumSize(200)
                        .build());

        // Rate-limit присутствия: ключ = botUserId, значение — dummy. TTL=60s
        // автоматически сбрасывает окно; maximumSize защищает от memory pressure
        // при возможной массовой атаке.
        manager.registerCustomCache(FEEDBACK_RATE_LIMIT,
                Caffeine.newBuilder()
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .maximumSize(10_000)
                        .build());

        return manager;
    }
}
