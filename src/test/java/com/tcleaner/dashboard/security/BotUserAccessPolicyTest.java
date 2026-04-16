package com.tcleaner.dashboard.security;

import com.tcleaner.dashboard.domain.DashboardRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RBAC-матрица для {@link BotUserAccessPolicy}.
 */
@DisplayName("BotUserAccessPolicy")
class BotUserAccessPolicyTest {

    private final BotUserAccessPolicy policy = new BotUserAccessPolicy();

    // ─── effectiveUserId ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN + requestedUserId=null → 0 (все)")
    void adminNoFilterReturnsZero() {
        long result = policy.effectiveUserId(DashboardRole.ADMIN, null, null);
        assertThat(result).isEqualTo(0L);
    }

    @Test
    @DisplayName("ADMIN + requestedUserId=42 → 42")
    void adminWithSpecificUserReturnsThat() {
        long result = policy.effectiveUserId(DashboardRole.ADMIN, null, 42L);
        assertThat(result).isEqualTo(42L);
    }

    @Test
    @DisplayName("USER + own botUserId + requestedUserId=null → own id")
    void userNoRequestedIdReturnsOwn() {
        long result = policy.effectiveUserId(DashboardRole.USER, 7L, null);
        assertThat(result).isEqualTo(7L);
    }

    @Test
    @DisplayName("USER + requestedUserId совпадает с own → own id")
    void userRequestsOwnIdReturnsOwn() {
        long result = policy.effectiveUserId(DashboardRole.USER, 7L, 7L);
        assertThat(result).isEqualTo(7L);
    }

    @Test
    @DisplayName("USER + requestedUserId != own → AccessDeniedException")
    void userRequestsOtherIdThrows() {
        assertThatThrownBy(() -> policy.effectiveUserId(DashboardRole.USER, 7L, 99L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("USER + ownBotUserId=null → AccessDeniedException (не привязан)")
    void userNotLinkedToTelegramThrows() {
        assertThatThrownBy(() -> policy.effectiveUserId(DashboardRole.USER, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ─── canSeeUser ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN видит любого")
    void adminCanSeeAnyone() {
        assertThat(policy.canSeeUser(DashboardRole.ADMIN, null, 999L)).isTrue();
        assertThat(policy.canSeeUser(DashboardRole.ADMIN, 1L, 999L)).isTrue();
    }

    @Test
    @DisplayName("USER видит только себя")
    void userCanSeeOnlySelf() {
        assertThat(policy.canSeeUser(DashboardRole.USER, 7L, 7L)).isTrue();
        assertThat(policy.canSeeUser(DashboardRole.USER, 7L, 99L)).isFalse();
    }

    @Test
    @DisplayName("USER с null ownBotUserId → false")
    void userNullOwnCannotSeeAnyone() {
        assertThat(policy.canSeeUser(DashboardRole.USER, null, 7L)).isFalse();
    }
}
