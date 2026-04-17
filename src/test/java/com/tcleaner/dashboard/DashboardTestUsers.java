package com.tcleaner.dashboard;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.domain.DashboardRole;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Фабрика тестовых {@link DashboardUserDetails} для интеграционных тестов дашборда.
 * Используется вместо {@code @WithMockUser}, т.к. последний не умеет класть
 * {@code botUserId} в principal — а RBAC-политика на нём построена.
 */
public final class DashboardTestUsers {

    private DashboardTestUsers() {
    }

    /** Админ без привязки к bot_user_id (видит всех). */
    public static DashboardUserDetails admin() {
        return new DashboardUserDetails(
                "admin", "x",
                List.of(new SimpleGrantedAuthority(DashboardRole.ADMIN.authority())),
                DashboardRole.ADMIN, null);
    }

    /** Рядовой USER с привязкой к конкретному bot_user_id. */
    public static DashboardUserDetails user(String username, long botUserId) {
        return new DashboardUserDetails(
                username, "x",
                List.of(new SimpleGrantedAuthority(DashboardRole.USER.authority())),
                DashboardRole.USER, botUserId);
    }

    /** USER без привязки к bot_user_id — должен получать 403 на любой user-detail. */
    public static DashboardUserDetails unboundUser() {
        return new DashboardUserDetails(
                "unbound", "x",
                List.of(new SimpleGrantedAuthority(DashboardRole.USER.authority())),
                DashboardRole.USER, null);
    }
}
