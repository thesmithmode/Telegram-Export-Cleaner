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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "dashboard.auth.bootstrap.enabled=true",
    "dashboard.auth.admin.telegram-id=555"
})
@DirtiesContext
@Transactional
@DisplayName("EnvUserBootstrap")
class EnvUserBootstrapTest {

    @Autowired
    private DashboardUserRepository repository;

    @Autowired
    private EnvUserBootstrap bootstrap;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("ADMIN-запись создаётся по telegram_id без пароля")
    void adminCreatedByTelegramId() {
        DashboardUser admin = repository.findByTelegramId(555L).orElseThrow();

        assertThat(admin.getRole()).isEqualTo(DashboardRole.ADMIN);
        assertThat(admin.getProvider()).isEqualTo(AuthProvider.TELEGRAM);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getPasswordHash()).isEmpty();
        assertThat(admin.getBotUserId()).isNull();
    }

    @Test
    @DisplayName("повторный запуск bootstrap идемпотентен — не дублирует ADMIN")
    void idempotent() throws Exception {
        long countBefore = repository.count();
        bootstrap.run();
        assertThat(repository.count()).isEqualTo(countBefore);
        assertThat(repository.findAllByRole(DashboardRole.ADMIN)).hasSize(1);
    }

    @Test
    @DisplayName("старый ADMIN с другим telegram_id удаляется при bootstrap")
    void oldAdminDeletedOnTelegramIdChange() throws Exception {
        repository.save(DashboardUser.builder()
                .telegramId(999L)
                .username("stale_admin")
                .passwordHash("")
                .role(DashboardRole.ADMIN)
                .provider(AuthProvider.TELEGRAM)
                .createdAt(Instant.now())
                .enabled(true)
                .build());
        assertThat(repository.findByTelegramId(999L)).isPresent();

        bootstrap.run();

        assertThat(repository.findByTelegramId(999L)).isEmpty();
        assertThat(repository.findByTelegramId(555L)).isPresent();
        assertThat(repository.findAllByRole(DashboardRole.ADMIN)).hasSize(1);
    }
}
