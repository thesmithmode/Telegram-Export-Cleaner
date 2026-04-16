package com.tcleaner.dashboard;

import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционные тесты безопасности: два SecurityFilterChain работают независимо.
 * Dashboard-цепочка требует аутентификацию; API-цепочка остаётся STATELESS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Dashboard Security Integration")
class DashboardSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramExporter mockExporter;

    // ─── Dashboard chain: неаутентифицированный доступ ───────────────────────

    @Test
    @DisplayName("GET /dashboard/overview без аутентификации → 302 redirect на login")
    void unauthenticatedDashboardRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/login без аутентификации → не 401/403 (permitAll)")
    void loginPageIsPermitted() throws Exception {
        int status = mockMvc.perform(get("/dashboard/login"))
                .andReturn().getResponse().getStatus();
        // Может быть 200 (если контроллер есть) или 404 (нет контроллера ещё).
        // Главное — не 401 и не 403.
        org.assertj.core.api.Assertions.assertThat(status)
                .isNotIn(401, 403);
    }

    // ─── Dashboard RBAC ───────────────────────────────────────────────────────

    @Test
    @DisplayName("USER не может открыть /dashboard/api/stats/users → 403")
    @WithMockUser(username = "bob", roles = {"USER"})
    void userForbiddenFromUsersEndpoint() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN может открыть /dashboard/api/stats/users → не 403")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void adminCanAccessUsersEndpoint() throws Exception {
        int status = mockMvc.perform(get("/dashboard/api/stats/users"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(403);
    }

    // ─── API chain: прежнее поведение не сломано ─────────────────────────────

    @Test
    @DisplayName("GET /api/health → 200 (API chain работает)")
    void apiHealthStillAvailable() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/health → без JSESSIONID (API chain STATELESS)")
    void apiIsStateless() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    @DisplayName("GET /api/health → Content-Security-Policy сохранён")
    void apiCspPreserved() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'none'")));
    }
}
