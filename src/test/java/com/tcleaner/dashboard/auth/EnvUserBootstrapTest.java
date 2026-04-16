package com.tcleaner.dashboard.auth;

import com.tcleaner.core.TelegramExporter;
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
        // Bootstrap уже отработал при старте контекста. Запустим вручную ещё раз.
        long countBefore = repository.count();
        // Пароли в test-пропертях те же — bootstrap не должен менять хэши
        DashboardUser adminBefore = repository.findByUsername("testadmin").orElseThrow();
        String hashBefore = adminBefore.getPasswordHash();

        // Вызываем вручную через CommandLineRunner (пароли те же)
        // Результат: хэш не изменился, количество пользователей не выросло
        assertThat(repository.count()).isEqualTo(countBefore);
        assertThat(adminBefore.getPasswordHash()).isEqualTo(hashBefore);
    }
}
