package com.tcleaner.dashboard.auth.telegram;

import com.tcleaner.dashboard.auth.DashboardUserService;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramLoginServiceTest {

    private static final long ADMIN_TG_ID = 999L;
    private static final long USER_TG_ID = 111L;

    @Mock
    private BotUserUpserter botUserUpserter;

    @Mock
    private DashboardUserService userService;

    @InjectMocks
    private TelegramLoginService service;

    @Test
    void adminLogin_skipsBotUserUpsert_andAssignsAdminRole() {
        TelegramMiniAppLoginData data = loginData(ADMIN_TG_ID, "admin", "Admin", 1700000000L);
        DashboardUser stored = mockUser(DashboardRole.ADMIN, null);
        when(userService.findOrCreate(ADMIN_TG_ID, "Admin", "admin", DashboardRole.ADMIN, null))
                .thenReturn(stored);

        TelegramLoginService.LoginResult result = service.loginOrCreate(data, ADMIN_TG_ID);

        assertThat(result.role()).isEqualTo(DashboardRole.ADMIN);
        assertThat(result.user()).isSameAs(stored);
        verify(botUserUpserter, never()).upsert(any(Long.class), any(), any(), any());
        verify(userService).findOrCreate(ADMIN_TG_ID, "Admin", "admin", DashboardRole.ADMIN, null);
    }

    @Test
    void userLogin_callsBotUserUpsert_andAssignsUserRole() {
        TelegramMiniAppLoginData data = loginData(USER_TG_ID, "alice", "Alice", 1700000000L);
        DashboardUser stored = mockUser(DashboardRole.USER, USER_TG_ID);
        when(userService.findOrCreate(USER_TG_ID, "Alice", "alice", DashboardRole.USER, USER_TG_ID))
                .thenReturn(stored);

        TelegramLoginService.LoginResult result = service.loginOrCreate(data, ADMIN_TG_ID);

        assertThat(result.role()).isEqualTo(DashboardRole.USER);
        assertThat(result.user()).isSameAs(stored);
        verify(botUserUpserter).upsert(eq(USER_TG_ID), eq("alice"), eq("Alice"),
                eq(Instant.ofEpochSecond(1700000000L)));
        verify(userService).findOrCreate(USER_TG_ID, "Alice", "alice", DashboardRole.USER, USER_TG_ID);
    }

    @Test
    void userLogin_propagatesUpsertException_andSkipsFindOrCreate() {
        TelegramMiniAppLoginData data = loginData(USER_TG_ID, "bob", "Bob", 1700000000L);
        doThrow(new RuntimeException("DB down"))
                .when(botUserUpserter).upsert(any(Long.class), any(), any(), any());

        assertThatThrownBy(() -> service.loginOrCreate(data, ADMIN_TG_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB down");

        verify(userService, never()).findOrCreate(any(Long.class), any(), any(), any(), any());
    }

    private static TelegramMiniAppLoginData loginData(long id, String username, String firstName, long authDate) {
        Map<String, String> params = Map.of("auth_date", Long.toString(authDate));
        Map<String, Object> user = Map.of(
                "id", id,
                "username", username,
                "first_name", firstName
        );
        return new TelegramMiniAppLoginData(params, user);
    }

    private static DashboardUser mockUser(DashboardRole role, Long botUserId) {
        return DashboardUser.builder()
                .role(role)
                .botUserId(botUserId)
                .build();
    }
}
