package com.tcleaner.dashboard.auth.telegram;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.repository.BotUserRepository;
import com.tcleaner.dashboard.repository.DashboardUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dashboard.auth.admin.telegram-id=999",
        "dashboard.auth.bootstrap.enabled=false"
})
@DirtiesContext
@DisplayName("TelegramAuthController")
class TelegramAuthControllerTest {

    private static final String TOKEN = "123456:TEST_BOT_TOKEN";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        Clock testClock() {
            return FIXED_CLOCK;
        }

        @Bean
        @Primary
        TelegramMiniAppAuthVerifier testTelegramMiniAppAuthVerifier() {
            return new TelegramMiniAppAuthVerifier(TOKEN, FIXED_CLOCK);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    BotUserRepository botUsers;
    @Autowired
    DashboardUserRepository dashboardUsers;
    @MockitoBean
    TelegramExporter exporter;

    @BeforeEach
    void cleanDb() {
        dashboardUsers.deleteAll();
        botUsers.deleteAll();
    }

    private String buildInitData(long id, String firstName, String username, long authDate) throws Exception {
        return TelegramAuthTestUtils.buildMiniAppInitData(TOKEN, id, firstName, username, authDate);
    }

    @Test
    @DisplayName("admin login создаёт сессию и редиректит на /dashboard/overview")
    void adminLoginSucceeds() throws Exception {
        String initData = buildInitData(999L, "Admin", null, 1_000_000L);

        mvc.perform(post("/dashboard/login/telegram")
                        .param("initData", initData))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/overview"));

        assertThat(dashboardUsers.findByTelegramId(999L)).isPresent();
    }

    @Test
    @DisplayName("известный бот-пользователь логинится как USER и попадает на /dashboard/me")
    void knownBotUserLoginSucceeds() throws Exception {
        botUsers.save(BotUser.builder().botUserId(111L).username("johnny")
                .displayName("John").firstSeen(Instant.ofEpochSecond(500_000L))
                .lastSeen(Instant.ofEpochSecond(500_000L))
                .totalExports(0).totalMessages(0L).totalBytes(0L).build());

        String initData = buildInitData(111L, "John", "johnny", 1_000_000L);

        mvc.perform(post("/dashboard/login/telegram")
                        .param("initData", initData))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));

        assertThat(dashboardUsers.findByTelegramId(111L))
                .isPresent()
                .hasValueSatisfying(u -> {
                    assertThat(u.getRole()).isEqualTo(
                            com.tcleaner.dashboard.domain.DashboardRole.USER);
                    assertThat(u.getBotUserId()).isEqualTo(111L);
                });
        assertThat(botUsers.findById(111L))
                .isPresent()
                .hasValueSatisfying(b -> assertThat(b.getLastSeen())
                        .isAfter(Instant.ofEpochSecond(500_000L)));
    }

    @Test
    @DisplayName("новый TG-аккаунт (ни разу не писал боту) получает пустой личный кабинет")
    void newUserGetsEmptyDashboard() throws Exception {
        String initData = buildInitData(222L, "Stranger", null, 1_000_000L);

        mvc.perform(post("/dashboard/login/telegram")
                        .param("initData", initData))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));

        assertThat(dashboardUsers.findByTelegramId(222L))
                .isPresent()
                .hasValueSatisfying(u -> {
                    assertThat(u.getRole()).isEqualTo(
                            com.tcleaner.dashboard.domain.DashboardRole.USER);
                    assertThat(u.getBotUserId()).isEqualTo(222L);
                });
        assertThat(botUsers.findById(222L))
                .isPresent()
                .hasValueSatisfying(b -> {
                    assertThat(b.getTotalExports()).isZero();
                    assertThat(b.getTotalMessages()).isZero();
                    assertThat(b.getTotalBytes()).isZero();
                });
    }

    @Test
    @DisplayName("невалидный hash отклоняется")
    void invalidHashRejected() throws Exception {
        mvc.perform(post("/dashboard/login/telegram")
                        .param("initData", "auth_date=1000000&id=999&first_name=Admin&hash=deadbeef"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/login?error=invalid"));
    }

    @Test
    @DisplayName("повторный вход обновляет username")
    void secondLoginUpdatesUsername() throws Exception {
        adminLoginSucceeds();

        String initData2 = buildInitData(999L, "Admin", "newname", 1_000_050L);
        mvc.perform(post("/dashboard/login/telegram")
                        .param("initData", initData2))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/overview"));

        assertThat(dashboardUsers.findByTelegramId(999L))
                .isPresent()
                .hasValueSatisfying(u -> assertThat(u.getUsername()).isEqualTo("newname"));
        assertThat(dashboardUsers.findAllByRole(
                com.tcleaner.dashboard.domain.DashboardRole.ADMIN)).hasSize(1);
    }
}
