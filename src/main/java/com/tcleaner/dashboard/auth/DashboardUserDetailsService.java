package com.tcleaner.dashboard.auth;

import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.repository.DashboardUserRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Загружает {@link DashboardUser} из БД и оборачивает в {@link DashboardUserDetails}.
 * Проверяет блокировку через {@link LoginAttemptService} ещё до обращения к БД.
 */
@Service
public class DashboardUserDetailsService implements UserDetailsService {

    private final DashboardUserRepository repository;
    private final LoginAttemptService loginAttemptService;

    public DashboardUserDetailsService(DashboardUserRepository repository,
                                       LoginAttemptService loginAttemptService) {
        this.repository = repository;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (loginAttemptService.isBlocked(username)) {
            throw new LockedException(
                    "Аккаунт заблокирован из-за превышения числа неудачных попыток входа");
        }
        DashboardUser user = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Пользователь не найден: " + username));
        if (!user.isEnabled()) {
            throw new DisabledException("Пользователь отключён: " + username);
        }
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        return new DashboardUserDetails(
                user.getUsername(), user.getPasswordHash(),
                authorities, user.getRole(), user.getBotUserId());
    }
}
