package com.tcleaner.dashboard.service.ingestion;

import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.repository.BotUserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Idempotent upsert для {@link BotUser} по {@code botUserId}.
 * Первое появление выставляет {@code firstSeen} и инициализирует счётчики нулями;
 * повторное — обновляет {@code username}/{@code displayName}/{@code lastSeen}, не трогая
 * {@code firstSeen} и денормализованные {@code total*}-поля (их правит ingestion-сервис
 * при обработке {@code export.*}-событий).
 */
@Component
public class BotUserUpserter {

    private final BotUserRepository repository;

    public BotUserUpserter(BotUserRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public BotUser upsert(long botUserId, String username, String displayName, Instant seenAt) {
        Instant ts = seenAt != null ? seenAt : Instant.now();
        BotUser user = repository.findById(botUserId).orElseGet(() -> BotUser.builder()
                .botUserId(botUserId)
                .firstSeen(ts)
                .lastSeen(ts)
                .totalExports(0)
                .totalMessages(0L)
                .totalBytes(0L)
                .build());
        if (username != null && !username.isBlank()) {
            user.setUsername(username);
        }
        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName);
        }
        if (user.getLastSeen() == null || ts.isAfter(user.getLastSeen())) {
            user.setLastSeen(ts);
        }
        return repository.save(user);
    }
}
