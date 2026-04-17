package com.tcleaner.dashboard.auth;

import com.tcleaner.dashboard.domain.AuthProvider;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.repository.DashboardUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * При старте гарантирует существование ADMIN-записи в dashboard_users,
 * связанной с {@code dashboard.auth.admin.telegram-id}.
 * Пароли не используются — вход через Telegram Login Widget.
 */
@Component
@ConditionalOnProperty(name = "dashboard.auth.bootstrap.enabled", havingValue = "true")
public class EnvUserBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvUserBootstrap.class);
    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    @Value("${dashboard.auth.admin.telegram-id}")
    private long adminTelegramId;

    private final DashboardUserRepository repository;

    public EnvUserBootstrap(DashboardUserRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (adminTelegramId <= 0) {
            throw new IllegalStateException(
                    "Некорректный DASHBOARD_ADMIN_TG_ID=" + adminTelegramId +
                    " — должен быть положительным Telegram User ID");
        }
        repository.findAllByRole(DashboardRole.ADMIN).stream()
                .filter(u -> u.getTelegramId() == null || u.getTelegramId() != adminTelegramId)
                .forEach(u -> {
                    repository.delete(u);
                    log.info("Dashboard: удалён устаревший admin (id={})", u.getId());
                });

        Instant now = Instant.now();
        DashboardUser admin = repository.findByTelegramId(adminTelegramId).orElse(null);
        if (admin == null) {
            repository.save(DashboardUser.builder()
                    .telegramId(adminTelegramId)
                    .username(DEFAULT_ADMIN_USERNAME)
                    .passwordHash("")
                    .role(DashboardRole.ADMIN)
                    .botUserId(null)
                    .provider(AuthProvider.TELEGRAM)
                    .createdAt(now)
                    .enabled(true)
                    .build());
            log.info("Dashboard: создан admin по telegram_id={}", adminTelegramId);
        } else if (admin.getRole() != DashboardRole.ADMIN) {
            admin.setRole(DashboardRole.ADMIN);
            admin.setProvider(AuthProvider.TELEGRAM);
            admin.setEnabled(true);
            repository.save(admin);
            log.info("Dashboard: роль admin восстановлена для telegram_id={}", adminTelegramId);
        }
    }
}
