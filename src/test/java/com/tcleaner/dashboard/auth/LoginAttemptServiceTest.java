package com.tcleaner.dashboard.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юниты для {@link LoginAttemptService} — без Spring-контекста.
 */
@DisplayName("LoginAttemptService")
class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService();

    @Test
    @DisplayName("свежий пользователь не заблокирован")
    void freshUserNotBlocked() {
        assertThat(service.isBlocked("alice")).isFalse();
    }

    @Test
    @DisplayName("после MAX-1 попыток — не заблокирован")
    void belowThresholdNotBlocked() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS - 1; i++) {
            service.recordFailure("alice");
        }
        assertThat(service.isBlocked("alice")).isFalse();
    }

    @Test
    @DisplayName("ровно MAX попыток → заблокирован")
    void atThresholdBlocked() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("alice");
        }
        assertThat(service.isBlocked("alice")).isTrue();
    }

    @Test
    @DisplayName("после успешного входа — счётчик сброшен")
    void successClearsCounter() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("alice");
        }
        service.recordSuccess("alice");
        assertThat(service.isBlocked("alice")).isFalse();
        assertThat(service.failureCount("alice")).isZero();
    }

    @Test
    @DisplayName("разные пользователи не влияют друг на друга")
    void usersAreIndependent() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("alice");
        }
        assertThat(service.isBlocked("alice")).isTrue();
        assertThat(service.isBlocked("bob")).isFalse();
    }
}
