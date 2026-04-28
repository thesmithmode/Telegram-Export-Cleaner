package com.tcleaner.dashboard.auth;

import com.tcleaner.dashboard.domain.AuthProvider;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.repository.DashboardUserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardUserServiceTest {

    private final DashboardUserRepository repo = mock(DashboardUserRepository.class);
    private final DashboardUserService service = new DashboardUserService(repo);

    @Test
    void createsNewUserWhenMissing() {
        when(repo.findByTelegramId(111L)).thenReturn(Optional.empty());
        when(repo.save(any(DashboardUser.class))).thenAnswer(inv -> inv.getArgument(0));

        DashboardUser user = service.findOrCreate(111L, "John", "johnny", DashboardRole.USER, 111L);

        assertThat(user.getTelegramId()).isEqualTo(111L);
        assertThat(user.getUsername()).isEqualTo("johnny");
        assertThat(user.getRole()).isEqualTo(DashboardRole.USER);
        assertThat(user.getBotUserId()).isEqualTo(111L);
        assertThat(user.getProvider()).isEqualTo(AuthProvider.TELEGRAM);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPasswordHash()).isEmpty();
    }

    @Test
    void updatesExistingUsernameAndLastLogin() {
        DashboardUser existing = DashboardUser.builder()
                .id(1L).telegramId(111L).username("old_name").passwordHash("")
                .role(DashboardRole.USER).botUserId(111L)
                .provider(AuthProvider.TELEGRAM)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .enabled(true).build();
        when(repo.findByTelegramId(111L)).thenReturn(Optional.of(existing));
        when(repo.save(any(DashboardUser.class))).thenAnswer(inv -> inv.getArgument(0));

        DashboardUser result = service.findOrCreate(111L, "John", "new_name", DashboardRole.USER, 111L);

        assertThat(result.getUsername()).isEqualTo("new_name");
        assertThat(result.getLastLoginAt()).isNotNull();
        verify(repo).save(existing);
    }

    @Test
    void usernameFallsBackToFirstNamePlusTelegramIdWhenUsernameIsNull() {
        // firstName глобально не уникален → добавляем _telegramId суффикс
        // чтобы избежать UNIQUE-коллизии на username-столбце.
        when(repo.findByTelegramId(999L)).thenReturn(Optional.empty());
        when(repo.save(any(DashboardUser.class))).thenAnswer(inv -> inv.getArgument(0));

        DashboardUser user = service.findOrCreate(999L, "Admin", null, DashboardRole.ADMIN, null);

        assertThat(user.getUsername()).isEqualTo("Admin_999");
    }

    @Test
    void usernameFallsBackToTgIdWhenBothNull() {
        when(repo.findByTelegramId(777L)).thenReturn(Optional.empty());
        when(repo.save(any(DashboardUser.class))).thenAnswer(inv -> inv.getArgument(0));

        DashboardUser user = service.findOrCreate(777L, null, null, DashboardRole.USER, 777L);

        assertThat(user.getUsername()).isEqualTo("tg_777");
    }
}
