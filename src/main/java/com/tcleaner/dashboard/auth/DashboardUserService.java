package com.tcleaner.dashboard.auth;

import com.tcleaner.dashboard.domain.AuthProvider;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.repository.DashboardUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class DashboardUserService {

    private final DashboardUserRepository repository;

    public DashboardUserService(DashboardUserRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DashboardUser findOrCreate(long telegramId,
                                      String firstName,
                                      String username,
                                      DashboardRole role,
                                      Long botUserId) {
        String displayName = pickDisplayName(username, firstName, telegramId);
        Instant now = Instant.now();
        return repository.findByTelegramId(telegramId)
                .map(existing -> {
                    existing.setUsername(displayName);
                    existing.setRole(role);
                    existing.setBotUserId(botUserId);
                    existing.setLastLoginAt(now);
                    existing.setEnabled(true);
                    return repository.save(existing);
                })
                .orElseGet(() -> repository.save(DashboardUser.builder()
                        .telegramId(telegramId)
                        .username(displayName)
                        .passwordHash("")
                        .role(role)
                        .botUserId(botUserId)
                        .provider(AuthProvider.TELEGRAM)
                        .createdAt(now)
                        .lastLoginAt(now)
                        .enabled(true)
                        .build()));
    }

    private static String pickDisplayName(String username, String firstName, long telegramId) {
        if (username != null && !username.isBlank()) return username;
        // Telegram usernames уникальны глобально, но firstName — нет.
        // Добавляем суффикс telegramId чтобы избежать UNIQUE-коллизии на username-столбце.
        if (firstName != null && !firstName.isBlank()) return firstName + "_" + telegramId;
        return "tg_" + telegramId;
    }
}
