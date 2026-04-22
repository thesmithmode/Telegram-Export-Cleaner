package com.tcleaner.dashboard.web;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.DashboardTestUsers;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "dashboard.donate.ton=EQCtest-ton-address-1234567890")
@DisplayName("DashboardAboutPageController")
class DashboardAboutPageControllerTest {

    private static final DashboardUserDetails USER_42 = DashboardTestUsers.user("alice", 42L);
    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();

    @Autowired private MockMvc mvc;
    @MockitoBean private TelegramExporter mockExporter;

    @Test
    @DisplayName("USER GET /dashboard/about → 200, шаблон about, donateTon в модели")
    void userGetsPage() throws Exception {
        mvc.perform(get("/dashboard/about").with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/about"))
                .andExpect(model().attribute("donateTon", "EQCtest-ton-address-1234567890"));
    }

    @Test
    @DisplayName("ADMIN тоже может открыть /dashboard/about (для просмотра TON)")
    void adminGetsPage() throws Exception {
        mvc.perform(get("/dashboard/about").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/about"));
    }

    @Test
    @DisplayName("анонимный → redirect на /dashboard/login")
    void anonymousRedirects() throws Exception {
        mvc.perform(get("/dashboard/about"))
                .andExpect(status().is3xxRedirection());
    }
}
