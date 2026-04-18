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
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты страницы {@code /dashboard/me} — персональный дашборд.
 * Ключевое: USER и ADMIN оба получают 200, анонимный — редирект на логин,
 * в HTML нет ссылок на admin-страницы.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("DashboardMePageController")
class DashboardMePageControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TelegramExporter mockExporter;

    private static final DashboardUserDetails USER = DashboardTestUsers.user("alice", 42L);
    private static final DashboardUserDetails UNBOUND = DashboardTestUsers.unboundUser();
    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();

    @Test
    @DisplayName("USER → 200, заголовок My Dashboard, нет ссылок на admin-страницы")
    void userSeesMyDashboard() throws Exception {
        mockMvc.perform(get("/dashboard/me").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("My Dashboard")))
                .andExpect(content().string(containsString("/dashboard/js/pages/me.js")))
                .andExpect(content().string(not(containsString("/dashboard/users"))));
    }

    @Test
    @DisplayName("USER без botUserId → 200 + empty-state баннер")
    void unboundUserSeesEmptyState() throws Exception {
        mockMvc.perform(get("/dashboard/me").with(user(UNBOUND)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No exports yet")));
    }

    @Test
    @DisplayName("ADMIN → 200, та же страница (единый UI)")
    void adminCanSeeMePage() throws Exception {
        mockMvc.perform(get("/dashboard/me").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("My Dashboard")));
    }

    @Test
    @DisplayName("Анонимный → 302 на /dashboard/login")
    void anonymousRedirected() throws Exception {
        mockMvc.perform(get("/dashboard/me"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }
}
