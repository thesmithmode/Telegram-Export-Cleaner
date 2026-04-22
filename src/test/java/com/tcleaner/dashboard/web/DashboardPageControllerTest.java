package com.tcleaner.dashboard.web;

import java.util.Locale;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.DashboardTestUsers;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration-тесты HTML-страниц дашборда (Thymeleaf + Spring Security).
 * Проверяют: рендер login/error, root-redirect, защищённость overview,
 * наличие CSRF в login-форме, RBAC для users/user-detail страниц.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("DashboardPageController")
class DashboardPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramExporter mockExporter;

    private static final DashboardUserDetails USER  = DashboardTestUsers.user("alice", 1L);
    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();

    @Test
    @DisplayName("GET /dashboard/login — 200, показывает инструкцию открыть Telegram")
    void loginPageRenders() throws Exception {
        mockMvc.perform(get("/dashboard/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Открыть в Telegram")));
    }

    @Test
    @DisplayName("GET /dashboard/login?error=invalid — показывает сообщение об ошибке")
    void loginWithErrorFlag() throws Exception {
        mockMvc.perform(get("/dashboard/login").param("error", "invalid"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign-in verification failed")));
    }

    @Test
    @DisplayName("GET /dashboard/login?logout — показывает уведомление о выходе")
    void loginWithLogoutFlag() throws Exception {
        mockMvc.perform(get("/dashboard/login").param("logout", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("signed out")));
    }

    @Test
    @DisplayName("GET /dashboard/ (anon) — 302 на login")
    void rootRedirectsAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/dashboard/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/ (ADMIN) — 302 на /dashboard/overview")
    void rootRedirectsAdminToOverview() throws Exception {
        mockMvc.perform(get("/dashboard/").with(user(ADMIN)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/overview"));
    }

    @Test
    @DisplayName("GET /dashboard/ (USER) — 302 на /dashboard/me")
    void rootRedirectsUserToMe() throws Exception {
        mockMvc.perform(get("/dashboard/").with(user(USER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));
    }

    @Test
    @DisplayName("GET /dashboard/login (ADMIN) — 302 на /dashboard/overview")
    void loginRedirectsAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/login").with(user(ADMIN)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/overview"));
    }

    @Test
    @DisplayName("GET /dashboard/login (USER) — 302 на /dashboard/me")
    void loginRedirectsUser() throws Exception {
        mockMvc.perform(get("/dashboard/login").with(user(USER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));
    }

    @Test
    @DisplayName("GET /dashboard/overview (anon) — 302 на login")
    void overviewRequiresAuth() throws Exception {
        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/overview (ADMIN) — 200, KPI + period-filter + chart canvases")
    void overviewRendersForAdmin() throws Exception {
        // Явный Accept-Language — admin без botUserId резолвится через request.getLocale();
        // без locale() тест зависит от JVM default и нестабилен между CI/dev-машинами.
        mockMvc.perform(get("/dashboard/overview").with(user(ADMIN)).locale(new Locale("ru")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Обзор")))
                // KPI-grid со всеми четырьмя метриками
                .andExpect(content().string(containsString("kpi-grid")))
                .andExpect(content().string(containsString("kpi-exports")))
                .andExpect(content().string(containsString("kpi-messages")))
                .andExpect(content().string(containsString("kpi-bytes")))
                .andExpect(content().string(containsString("kpi-users")))
                // Период-фильтр (5 кнопок)
                .andExpect(content().string(containsString("period-filter")))
                .andExpect(content().string(containsString("data-period=\"all\"")))
                .andExpect(content().string(containsString("data-period=\"month\"")))
                .andExpect(content().string(containsString("data-period=\"day\"")))
                // Chart.js canvas'ы
                .andExpect(content().string(containsString("id=\"chart-timeseries\"")))
                .andExpect(content().string(containsString("id=\"chart-status\"")))
                .andExpect(content().string(containsString("id=\"chart-top-users\"")))
                .andExpect(content().string(containsString("id=\"chart-top-chats\"")))
                // Встроенный скрипт страницы
                .andExpect(content().string(containsString("dashboard/js/pages/overview.js")));
    }

    @Test
    @DisplayName("GET /dashboard/overview (USER) — 302 на /dashboard/me (silent redirect, admin-only)")
    void overviewRedirectsUserToMe() throws Exception {
        mockMvc.perform(get("/dashboard/overview").with(user(USER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));
    }

    @Test
    @DisplayName("GET /dashboard/error — 200, generic error page")
    void errorPageRenders() throws Exception {
        mockMvc.perform(get("/dashboard/error").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("error-wrap")));
    }

    // ─── PR-11: users / user-detail ──────────────────────────────────────────

    @Test
    @DisplayName("GET /dashboard/users (anon) — 302 на login")
    void usersRequiresAuth() throws Exception {
        mockMvc.perform(get("/dashboard/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/users (USER) — 302 silent redirect на /dashboard/me")
    void usersBlockedForUser() throws Exception {
        mockMvc.perform(get("/dashboard/users").with(user(USER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));
    }

    @Test
    @DisplayName("GET /dashboard/users (ADMIN) — 200, рендерится таблица пользователей")
    void usersRendersForAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/users").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("data-table")))
                .andExpect(content().string(containsString("dashboard/js/pages/users.js")));
    }

    @Test
    @DisplayName("GET /dashboard/user/1 (ADMIN) — 200, рендерится карточка пользователя")
    void userDetailRendersForAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/user/1").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("kpi-grid")))
                .andExpect(content().string(containsString("dashboard/js/pages/user-detail.js")));
    }

    @Test
    @DisplayName("GET /dashboard/user/999 (USER) — 302 silent redirect (admin-only URL)")
    void userDetailBlockedForUser() throws Exception {
        mockMvc.perform(get("/dashboard/user/999").with(user(USER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));
    }

    @Test
    @DisplayName("GET /dashboard/user/1 (USER с тем же botUserId) — 302 (admin-only URL, не даём IDOR)")
    void userDetailBlockedEvenForOwnIdForUser() throws Exception {
        // Даже «свой» botUserId через admin-URL не отдаём — у USER есть только /me.
        mockMvc.perform(get("/dashboard/user/1").with(user(USER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));
    }

    @Test
    @DisplayName("GET /dashboard/user/999 (ADMIN) — 200, ADMIN видит любого пользователя")
    void userDetailAccessibleForAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/user/999").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    @DisplayName("GET /dashboard/user/1 (USER без botUserId) — 302 silent redirect")
    void userDetailBlockedForUnboundUser() throws Exception {
        mockMvc.perform(get("/dashboard/user/1").with(user(DashboardTestUsers.unboundUser())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));
    }

    // ─── PR-12: chats / events pages ─────────────────────────────────────────

    @Test
    @DisplayName("GET /dashboard/chats (anon) — 302 на login")
    void chatsRequiresAuth() throws Exception {
        mockMvc.perform(get("/dashboard/chats"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/chats (ADMIN) — 200, рендерится таблица чатов + chart")
    void chatsRendersForAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/chats").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("data-table")))
                .andExpect(content().string(containsString("id=\"chart-top-chats\"")))
                .andExpect(content().string(containsString("dashboard/js/pages/chats.js")));
    }

    @Test
    @DisplayName("GET /dashboard/chats (USER) — 302 silent redirect")
    void chatsBlockedForUser() throws Exception {
        mockMvc.perform(get("/dashboard/chats").with(user(USER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));
    }

    @Test
    @DisplayName("GET /dashboard/events (anon) — 302 на login")
    void eventsRequiresAuth() throws Exception {
        mockMvc.perform(get("/dashboard/events"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/events (ADMIN) — 200, рендерится таблица событий")
    void eventsRendersForAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/events").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("data-table")))
                .andExpect(content().string(containsString("dashboard/js/pages/events.js")));
    }

    @Test
    @DisplayName("GET /dashboard/events (USER) — 302 silent redirect")
    void eventsBlockedForUser() throws Exception {
        mockMvc.perform(get("/dashboard/events").with(user(USER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));
    }
}
