package com.tcleaner.dashboard.domain;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.repository.DashboardUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRUD для {@link DashboardUser} + поиск по username (нужен UserDetailsService).
 */
@SpringBootTest
@Transactional
@DisplayName("DashboardUserRepository")
class DashboardUserRepositoryTest {

    @Autowired
    private DashboardUserRepository repository;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("save + findByUsername + enum-mapping ROLE/PROVIDER")
    void saveAndFindByUsername() {
        repository.save(DashboardUser.builder()
                .username("admin")
                .passwordHash("$2a$10$hash")
                .role(DashboardRole.ADMIN)
                .provider(AuthProvider.LOCAL)
                .createdAt(Instant.parse("2026-04-15T12:00:00Z"))
                .enabled(true)
                .build());

        DashboardUser found = repository.findByUsername("admin").orElseThrow();

        assertThat(found.getRole()).isEqualTo(DashboardRole.ADMIN);
        assertThat(found.getProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(found.isEnabled()).isTrue();
        assertThat(repository.findByUsername("unknown")).isEmpty();
    }
}
