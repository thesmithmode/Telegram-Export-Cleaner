package com.tcleaner.dashboard.web;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /dashboard/api/me/settings/language — авторизация через DashboardUserDetails
 * с непустым botUserId, валидация через regex (@Pattern) + BotLanguage.fromCode (enum allow-list),
 * персист в BotUser.language через BotUserUpserter.setLanguage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("DashboardMeSettingsController")
class DashboardMeSettingsControllerTest {

    private static final String URL = "/dashboard/api/me/settings/language";

    @Autowired private MockMvc mvc;
    @Autowired private BotUserRepository botUsers;

    @MockitoBean private TelegramExporter mockExporter;

    private static final DashboardUserDetails USER_42 = DashboardTestUsers.user("alice", 42L);
    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();
    private static final DashboardUserDetails UNBOUND = DashboardTestUsers.unboundUser();

    @BeforeEach
    void seed() {
        botUsers.deleteAll();
        botUsers.save(BotUser.builder()
                .botUserId(42L)
                .firstSeen(Instant.now())
                .lastSeen(Instant.now())
                .totalExports(0)
                .totalMessages(0L)
                .totalBytes(0L)
                .build());
    }

    @Test
    @DisplayName("POST с валидным кодом → 204, BotUser.language обновлён")
    void validCodePersistsLanguage() throws Exception {
        mvc.perform(post(URL)
                        .with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"language\":\"fa\"}"))
                .andExpect(status().isNoContent());

        assertThat(botUsers.findById(42L).orElseThrow().getLanguage()).isEqualTo("fa");
    }

    @Test
    @DisplayName("POST с pt-BR → 204, canonical код из enum сохраняется")
    void ptBrPersisted() throws Exception {
        mvc.perform(post(URL)
                        .with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"language\":\"pt-BR\"}"))
                .andExpect(status().isNoContent());

        assertThat(botUsers.findById(42L).orElseThrow().getLanguage()).isEqualTo("pt-BR");
    }

    @Test
    @DisplayName("POST с невалидным regex → 400")
    void invalidRegexReturns400() throws Exception {
        mvc.perform(post(URL)
                        .with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"language\":\"123\"}"))
                .andExpect(status().isBadRequest());

        assertThat(botUsers.findById(42L).orElseThrow().getLanguage()).isNull();
    }

    @Test
    @DisplayName("POST с неподдерживаемым кодом (проходит regex, но нет в enum) → 400")
    void unsupportedCodeReturns400() throws Exception {
        mvc.perform(post(URL)
                        .with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"language\":\"xx\"}"))
                .andExpect(status().isBadRequest());

        assertThat(botUsers.findById(42L).orElseThrow().getLanguage()).isNull();
    }

    @Test
    @DisplayName("POST с blank language → 400 (@NotBlank)")
    void blankLanguageReturns400() throws Exception {
        mvc.perform(post(URL)
                        .with(user(USER_42)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"language\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST от ADMIN (botUserId == null) → 401")
    void adminUnauthorized() throws Exception {
        mvc.perform(post(URL)
                        .with(user(ADMIN)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"language\":\"ru\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST от unbound USER (botUserId == null) → 401")
    void unboundUserUnauthorized() throws Exception {
        mvc.perform(post(URL)
                        .with(user(UNBOUND)).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"language\":\"ru\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST без аутентификации → redirect на login")
    void anonymousRedirects() throws Exception {
        mvc.perform(post(URL).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"language\":\"ru\"}"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("POST без CSRF-токена → 403 (Spring Security CSRF enforcement)")
    void missingCsrfReturns403() throws Exception {
        mvc.perform(post(URL)
                        .with(user(USER_42))
                        .contentType(APPLICATION_JSON)
                        .content("{\"language\":\"ru\"}"))
                .andExpect(status().isForbidden());
    }
}
