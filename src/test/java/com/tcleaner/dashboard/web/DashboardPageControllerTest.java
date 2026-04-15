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
 * наличие CSRF в login-форме.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("DashboardPageController")
class DashboardPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramExporter mockExporter;

    private static final DashboardUserDetails USER = DashboardTestUsers.user("alice", 1L);

    @Test
    @DisplayName("GET /dashboard/login — 200, рендерится форма с CSRF")
    void loginPageRenders() throws Exception {
        mockMvc.perform(get("/dashboard/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("_csrf")));
    }

    @Test
    @DisplayName("GET /dashboard/login?error — показывает сообщение об ошибке")
    void loginWithErrorFlag() throws Exception {
        mockMvc.perform(get("/dashboard/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Неверный логин")));
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
    @DisplayName("GET /dashboard/overview (auth) — 200, рендерится layout + nav")
    void overviewRendersForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/dashboard/overview").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Обзор")))
                .andExpect(content().string(containsString("alice")));
    }

    @Test
    @DisplayName("GET /dashboard/error — 200, generic error page")
    void errorPageRenders() throws Exception {
        mockMvc.perform(get("/dashboard/error").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("error-wrap")));
    }
}
