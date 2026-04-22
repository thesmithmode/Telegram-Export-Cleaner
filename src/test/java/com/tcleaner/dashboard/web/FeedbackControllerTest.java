package com.tcleaner.dashboard.web;

import com.tcleaner.bot.BotMessenger;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.DashboardTestUsers;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.repository.BotUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static com.tcleaner.dashboard.config.CacheConfig.FEEDBACK_RATE_LIMIT;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /dashboard/api/me/feedback — форма обратной связи со вкладки "О проекте".
 * Проверяем: happy path, валидация (@NotBlank/@Size), CSRF, анонимность,
 * unbound USER, ADMIN-самообращение, rate-limit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("FeedbackController")
class FeedbackControllerTest {

    private static final String URL = "/dashboard/api/me/feedback";

    @Autowired private MockMvc mvc;
    @Autowired private BotUserRepository botUsers;
    @Autowired private CacheManager cacheManager;

    @MockitoBean private TelegramExporter mockExporter;
    @MockitoBean private BotMessenger botMessenger;

    private static final DashboardUserDetails USER_42 = DashboardTestUsers.user("alice", 42L);
    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();
    private static final DashboardUserDetails UNBOUND = DashboardTestUsers.unboundUser();

    @BeforeEach
    void seed() {
        botUsers.deleteAll();
        botUsers.save(BotUser.builder()
                .botUserId(42L).username("alice").displayName("Alice Doe")
                .firstSeen(Instant.now()).lastSeen(Instant.now())
                .totalExports(0).totalMessages(0L).totalBytes(0L).build());
        cacheManager.getCache(FEEDBACK_RATE_LIMIT).clear();
    }

    @Test
    @DisplayName("USER с валидным сообщением → 204, BotMessenger вызван с метаданными")
    void happyPath() throws Exception {
        when(botMessenger.trySend(anyLong(), anyString())).thenReturn(true);

        mvc.perform(post(URL)
                        .with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"Привет, всё работает!\"}"))
                .andExpect(status().isNoContent());

        verify(botMessenger).trySend(anyLong(), contains("@alice"));
        verify(botMessenger).trySend(anyLong(), contains("Привет, всё работает!"));
    }

    @Test
    @DisplayName("пустое сообщение → 400 (@NotBlank)")
    void blankMessageReturns400() throws Exception {
        mvc.perform(post(URL)
                        .with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(botMessenger, never()).trySend(anyLong(), anyString());
    }

    @Test
    @DisplayName("сообщение > 2000 символов → 400 (@Size)")
    void tooLongReturns400() throws Exception {
        String tooLong = "a".repeat(2001);
        mvc.perform(post(URL)
                        .with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
        verify(botMessenger, never()).trySend(anyLong(), anyString());
    }

    @Test
    @DisplayName("unbound USER (botUserId=null) → 401")
    void unboundReturns401() throws Exception {
        mvc.perform(post(URL)
                        .with(user(UNBOUND)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADMIN (botUserId=null) → 401 — нет привязки")
    void adminReturns401() throws Exception {
        mvc.perform(post(URL)
                        .with(user(ADMIN)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("анонимный → redirect на login")
    void anonymousRedirects() throws Exception {
        mvc.perform(post(URL).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("без CSRF → 403")
    void missingCsrfReturns403() throws Exception {
        mvc.perform(post(URL)
                        .with(user(USER_42))
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("rate limit: второй запрос → 429")
    void rateLimited() throws Exception {
        when(botMessenger.trySend(anyLong(), anyString())).thenReturn(true);

        mvc.perform(post(URL).with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON).content("{\"message\":\"m1\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post(URL).with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON).content("{\"message\":\"m2\"}"))
                .andExpect(status().isTooManyRequests());

        verify(botMessenger, times(1)).trySend(anyLong(), anyString());
    }

    @Test
    @DisplayName("TG API недоступен → 503, лимит не расходуется")
    void sendFailureReturns503() throws Exception {
        when(botMessenger.trySend(anyLong(), anyString())).thenReturn(false);

        mvc.perform(post(URL).with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON).content("{\"message\":\"m\"}"))
                .andExpect(status().isServiceUnavailable());

        when(botMessenger.trySend(anyLong(), anyString())).thenReturn(true);
        mvc.perform(post(URL).with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON).content("{\"message\":\"m\"}"))
                .andExpect(status().isNoContent());
    }
}
