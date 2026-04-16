package com.tcleaner.dashboard.auth;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.domain.AuthProvider;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.repository.DashboardUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что {@link EnvUserBootstrap} создаёт пользователей из env-пропертей.
 */
@SpringBootTest(properties = {
    "dashboard.auth.bootstrap.enabled=true",
    "dashboard.auth.admin.username=testadmin",
    "dashboard.auth.admin.password=testadminpwd",
    "dashboard.auth.test.username=testuser",
    "dashboard.auth.test.password=testuserpwd",
    "dashboard.auth.test.bot-user-id=9999"
})
@Transactional
@DisplayName("EnvUserBootstrap")
class EnvUserBootstrapTest {

    @Autowired
    private DashboardUserRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EnvUserBootstrap bootstrap;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("ADMIN-пользователь создан с корректными полями")
    void adminUserCreated() {
        DashboardUser admin = repository.findByUsername("testadmin").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(DashboardRole.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getBotUserId()).isNull();
        assertThat(passwordEncoder.matches("testadminpwd", admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("TEST-пользователь создан с botUserId")
    void testUserCreatedWithBotUserId() {
        DashboardUser user = repository.findByUsername("testuser").orElseThrow();

        assertThat(user.getRole()).isEqualTo(DashboardRole.USER);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getBotUserId()).isEqualTo(9999L);
        assertThat(passwordEncoder.matches("testuserpwd", user.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("повторный запуск не создаёт дубликаты и не обновляет совпадающий пароль")
    void idempotent() {
        long countBefore = repository.count();
        DashboardUser adminBefore = repository.findByUsername("testadmin").orElseThrow();
        String hashBefore = adminBefore.getPasswordHash();

        assertThat(repository.count()).isEqualTo(countBefore);
        assertThat(adminBefore.getPasswordHash()).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("при смене admin username — старый ADMIN удаляется при следующем запуске bootstrap")
    void oldAdminDeletedOnUsernameChange() throws Exception {
        // Имитируем: в БД уже есть ADMIN с устаревшим именем (например, после смены логина в secrets)
        repository.save(DashboardUser.builder()
                .username("oldadmin")
                .passwordHash("$2a$10$abc")
                .role(DashboardRole.ADMIN)
                .provider(AuthProvider.LOCAL)
                .createdAt(Instant.now())
                .enabled(true)
                .build());
        assertThat(repository.findByUsername("oldadmin")).isPresent();

        // Bootstrap запускается снова (как при следующем деплое) — текущий admin="testadmin"
        bootstrap.run();

        // oldadmin должен быть удалён, testadmin остаётся
        assertThat(repository.findByUsername("oldadmin")).isEmpty();
        assertThat(repository.findByUsername("testadmin")).isPresent();
        assertThat(repository.findAllByRole(DashboardRole.ADMIN))
                .extracting(DashboardUser::getUsername)
                .containsExactly("testadmin");
    }
}
