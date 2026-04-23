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

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Интеграционные тесты страницы {@code /dashboard/subscriptions}.
 * Проверяют: USER и ADMIN получают 200 с правильным шаблоном,
 * анонимный — редирект на логин, модель содержит role/userBotId.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SubscriptionPageController")
class SubscriptionPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramExporter mockExporter;

    private static final DashboardUserDetails USER  = DashboardTestUsers.user("alice", 1L);
    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();

    @Test
    @DisplayName("GET /dashboard/subscriptions (USER) → 200, title содержит Subscriptions")
    void userGetsSubscriptionsPage() throws Exception {
        mockMvc.perform(get("/dashboard/subscriptions").with(user(USER)).locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(view().name("dashboard/subscriptions"))
                .andExpect(content().string(containsString("Subscriptions")));
    }

    @Test
    @DisplayName("GET /dashboard/subscriptions (USER) → модель содержит role=USER")
    void modelContainsRoleForUser() throws Exception {
        mockMvc.perform(get("/dashboard/subscriptions").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("role", "USER"));
    }

    @Test
    @DisplayName("GET /dashboard/subscriptions (ADMIN) → 200, модель role=ADMIN")
    void adminGetsSubscriptionsPage() throws Exception {
        mockMvc.perform(get("/dashboard/subscriptions").with(user(ADMIN)).locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/subscriptions"))
                .andExpect(model().attribute("role", "ADMIN"));
    }

    @Test
    @DisplayName("GET /dashboard/subscriptions (anon) → 302 redirect на login")
    void anonymousRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/dashboard/subscriptions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("GET /dashboard/subscriptions (USER) → страница содержит форму создания")
    void userSeesCreateForm() throws Exception {
        mockMvc.perform(get("/dashboard/subscriptions").with(user(USER)).locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("create-subscription-form")));
    }

    @Test
    @DisplayName("GET /dashboard/subscriptions (USER) → страница содержит таблицу подписок")
    void userSeesTable() throws Exception {
        mockMvc.perform(get("/dashboard/subscriptions").with(user(USER)).locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("subscriptions-table")))
                .andExpect(content().string(containsString("subscriptions.js")));
    }
}
