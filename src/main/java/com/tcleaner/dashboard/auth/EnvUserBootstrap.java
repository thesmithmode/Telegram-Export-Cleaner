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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Создаёт начальных пользователей дашборда при старте приложения.
 * Активируется только при {@code dashboard.auth.bootstrap.enabled=true}.
 * Пароль обновляется, только если текущий хэш не совпадает — избегаем лишних write.
 */
@Component
@ConditionalOnProperty(name = "dashboard.auth.bootstrap.enabled", havingValue = "true")
public class EnvUserBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvUserBootstrap.class);

    @Value("${dashboard.auth.admin.username}")
    private String adminUsername;

    @Value("${dashboard.auth.admin.password}")
    private String adminPassword;

    @Value("${dashboard.auth.test.username:}")
    private String testUsername;

    @Value("${dashboard.auth.test.password:}")
    private String testPassword;

    @Value("${dashboard.auth.test.bot-user-id:0}")
    private long testBotUserId;

    private final DashboardUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public EnvUserBootstrap(DashboardUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // Удаляем устаревших ADMIN-пользователей с другим username (смена логина через env)
        repository.findAllByRole(DashboardRole.ADMIN).stream()
                .filter(u -> !u.getUsername().equals(adminUsername))
                .forEach(u -> {
                    repository.delete(u);
                    log.info("Dashboard: удалён устаревший admin '{}'", u.getUsername());
                });
        upsert(adminUsername, adminPassword, DashboardRole.ADMIN, null);
        if (!testUsername.isBlank() && !testPassword.isBlank()) {
            Long botId = testBotUserId > 0 ? testBotUserId : null;
            upsert(testUsername, testPassword, DashboardRole.USER, botId);
        }
    }

    private void upsert(String username, String rawPassword, DashboardRole role, Long botUserId) {
        Instant now = Instant.now();
        DashboardUser existing = repository.findByUsername(username).orElse(null);
        if (existing == null) {
            repository.save(DashboardUser.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode(rawPassword))
                    .role(role)
                    .botUserId(botUserId)
                    .provider(AuthProvider.LOCAL)
                    .createdAt(now)
                    .enabled(true)
                    .build());
            log.info("Dashboard: создан пользователь '{}' (role={})", username, role);
        } else {
            if (!passwordEncoder.matches(rawPassword, existing.getPasswordHash())) {
                existing.setPasswordHash(passwordEncoder.encode(rawPassword));
                repository.save(existing);
                log.info("Dashboard: обновлён пароль пользователя '{}'", username);
            }
        }
    }
}
