package com.tcleaner.dashboard.web;

import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("MiniAppController")
class MiniAppControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("GET /dashboard/mini-app — 200, доступно без аутентификации")
    void miniAppPageLoads() throws Exception {
        mockMvc.perform(get("/dashboard/mini-app"))
                .andExpect(status().isOk())
                .andExpect(view().name("mini-app"))
                .andExpect(content().string(containsString("telegram-web-app.js")));
    }
}
