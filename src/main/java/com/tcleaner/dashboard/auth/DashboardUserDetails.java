package com.tcleaner.dashboard.auth;

import com.tcleaner.dashboard.domain.DashboardRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * Расширяет стандартный {@link User}: добавляет {@link DashboardRole}
 * и {@code botUserId} — нужны API-контроллеру для вызова {@code BotUserAccessPolicy}.
 */
public class DashboardUserDetails extends User {

    private final DashboardRole dashboardRole;
    private final Long botUserId;

    public DashboardUserDetails(String username, String password,
                                Collection<? extends GrantedAuthority> authorities,
                                DashboardRole dashboardRole, Long botUserId) {
        super(username, password, authorities);
        this.dashboardRole = dashboardRole;
        this.botUserId = botUserId;
    }

    public DashboardRole getDashboardRole() {
        return dashboardRole;
    }

    public Long getBotUserId() {
        return botUserId;
    }
}
