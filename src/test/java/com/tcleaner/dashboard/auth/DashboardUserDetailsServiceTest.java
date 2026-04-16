package com.tcleaner.dashboard.auth;

import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.repository.DashboardUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DashboardUserDetailsService")
class DashboardUserDetailsServiceTest {

    private DashboardUserRepository repository;
    private LoginAttemptService loginAttemptService;
    private DashboardUserDetailsService service;

    @BeforeEach
    void setUp() {
        repository = mock(DashboardUserRepository.class);
        loginAttemptService = new LoginAttemptService();
        service = new DashboardUserDetailsService(repository, loginAttemptService);
    }

    @Test
    @DisplayName("загружает пользователя и возвращает корректный UserDetails")
    void loadsExistingUser() {
        DashboardUser user = activeUser("alice", DashboardRole.ADMIN);
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("несуществующий пользователь → UsernameNotFoundException")
    void unknownUserThrows() {
        when(repository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("заблокированный пользователь → LockedException до обращения к БД")
    void blockedUserThrowsBeforeDbLookup() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            loginAttemptService.recordFailure("alice");
        }

        assertThatThrownBy(() -> service.loadUserByUsername("alice"))
                .isInstanceOf(LockedException.class);
    }

    @Test
    @DisplayName("отключённый пользователь → DisabledException")
    void disabledUserThrows() {
        DashboardUser user = disabledUser("alice");
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.loadUserByUsername("alice"))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    @DisplayName("USER role — authority ROLE_USER")
    void userRoleMapping() {
        DashboardUser user = activeUser("bob", DashboardRole.USER);
        when(repository.findByUsername("bob")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("bob");

        assertThat(details.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_USER");
    }

    private DashboardUser activeUser(String username, DashboardRole role) {
        DashboardUser u = new DashboardUser();
        u.setUsername(username);
        u.setPasswordHash("hash");
        u.setRole(role);
        u.setEnabled(true);
        return u;
    }

    private DashboardUser disabledUser(String username) {
        DashboardUser u = new DashboardUser();
        u.setUsername(username);
        u.setPasswordHash("hash");
        u.setRole(DashboardRole.USER);
        u.setEnabled(false);
        return u;
    }
}
