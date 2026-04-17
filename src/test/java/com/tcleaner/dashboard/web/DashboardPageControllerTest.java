package com.tcleaner.dashboard.web;

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
    @DisplayName("GET /dashboard/login — 200, рендерится Telegram Widget")
    void loginPageRenders() throws Exception {
        mockMvc.perform(get("/dashboard/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("telegram-widget.js")));
    }

    @Test
    @DisplayName("GET /dashboard/login?error=invalid — показывает сообщение об ошибке")
    void loginWithErrorFlag() throws Exception {
        mockMvc.perform(get("/dashboard/login").param("error", "invalid"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Не удалось подтвердить вход")));
    }

    @Test
    @DisplayName("GET /dashboard/login?logout — показывает уведомление о выходе")
    void loginWithLogoutFlag() throws Exception {
        mockMvc.perform(get("/dashboard/login").param("logout", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("вышли")));
    }

    @Test
    @DisplayName("GET /dashboard/ (anon) — 302 на login")
    void rootRedirectsAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/dashboard/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/ (auth) — 302 на /dashboard/overview")
    void rootRedirectsAuthenticatedToOverview() throws Exception {
        mockMvc.perform(get("/dashboard/").with(user(USER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/overview"));
    }

    @Test
    @DisplayName("GET /dashboard/overview (anon) — 302 на login")
    void overviewRequiresAuth() throws Exception {
        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/overview (auth) — 200, KPI + period-filter + chart canvases")
    void overviewRendersForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/dashboard/overview").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Обзор")))
                .andExpect(content().string(containsString("alice")))
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
    @DisplayName("GET /dashboard/users (USER) — 403")
    void usersBlockedForUser() throws Exception {
        mockMvc.perform(get("/dashboard/users").with(user(USER)))
                .andExpect(status().isForbidden());
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
    @DisplayName("GET /dashboard/user/1 (auth USER) — 200, рендерится карточка пользователя")
    void userDetailRendersForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/dashboard/user/1").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("kpi-grid")))
                .andExpect(content().string(containsString("dashboard/js/pages/user-detail.js")));
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
    @DisplayName("GET /dashboard/chats (auth) — 200, рендерится таблица чатов + chart")
    void chatsRendersForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/dashboard/chats").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("data-table")))
                .andExpect(content().string(containsString("id=\"chart-top-chats\"")))
                .andExpect(content().string(containsString("dashboard/js/pages/chats.js")));
    }

    @Test
    @DisplayName("GET /dashboard/events (anon) — 302 на login")
    void eventsRequiresAuth() throws Exception {
        mockMvc.perform(get("/dashboard/events"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/events (auth) — 200, рендерится таблица событий")
    void eventsRendersForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/dashboard/events").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("data-table")))
                .andExpect(content().string(containsString("dashboard/js/pages/events.js")));
    }
}
