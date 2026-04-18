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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        TelegramAuthVerifier testTelegramAuthVerifier() {
            return new TelegramAuthVerifier(TOKEN, FIXED_CLOCK);
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

    private String sign(String dataCheckString) throws Exception {
        byte[] secret = MessageDigest.getInstance("SHA-256")
                .digest(TOKEN.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("admin login создаёт сессию и редиректит на /dashboard/overview")
    void adminLoginSucceeds() throws Exception {
        String data = "auth_date=1000000\nfirst_name=Admin\nid=999";
        String hash = sign(data);

        mvc.perform(get("/dashboard/login/telegram")
                        .param("id", "999")
                        .param("first_name", "Admin")
                        .param("auth_date", "1000000")
                        .param("hash", hash))
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

        String data = "auth_date=1000000\nfirst_name=John\nid=111\nusername=johnny";
        String hash = sign(data);

        mvc.perform(get("/dashboard/login/telegram")
                        .param("id", "111")
                        .param("first_name", "John")
                        .param("username", "johnny")
                        .param("auth_date", "1000000")
                        .param("hash", hash))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));

        assertThat(dashboardUsers.findByTelegramId(111L))
                .isPresent()
                .hasValueSatisfying(u -> {
                    assertThat(u.getRole()).isEqualTo(
                            com.tcleaner.dashboard.domain.DashboardRole.USER);
                    assertThat(u.getBotUserId()).isEqualTo(111L);
                });
        // last_seen обновился после логина
        assertThat(botUsers.findById(111L))
                .isPresent()
                .hasValueSatisfying(b -> assertThat(b.getLastSeen())
                        .isAfter(Instant.ofEpochSecond(500_000L)));
    }

    @Test
    @DisplayName("новый TG-аккаунт (ни разу не писал боту) получает пустой личный кабинет")
    void newUserGetsEmptyDashboard() throws Exception {
        String data = "auth_date=1000000\nfirst_name=Stranger\nid=222";
        String hash = sign(data);

        mvc.perform(get("/dashboard/login/telegram")
                        .param("id", "222")
                        .param("first_name", "Stranger")
                        .param("auth_date", "1000000")
                        .param("hash", hash))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/me"));

        // Создана запись в dashboard_users (роль USER)
        assertThat(dashboardUsers.findByTelegramId(222L))
                .isPresent()
                .hasValueSatisfying(u -> {
                    assertThat(u.getRole()).isEqualTo(
                            com.tcleaner.dashboard.domain.DashboardRole.USER);
                    assertThat(u.getBotUserId()).isEqualTo(222L);
                });
        // Создана пустая запись в bot_users (нулевые счётчики)
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
        mvc.perform(get("/dashboard/login/telegram")
                        .param("id", "999")
                        .param("first_name", "Admin")
                        .param("auth_date", "1000000")
                        .param("hash", "deadbeef"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/login?error=invalid"));
    }

    @Test
    @DisplayName("повторный вход обновляет username")
    void secondLoginUpdatesUsername() throws Exception {
        adminLoginSucceeds();

        String data2 = "auth_date=1000050\nfirst_name=Admin\nid=999\nusername=newname";
        String hash2 = sign(data2);
        mvc.perform(get("/dashboard/login/telegram")
                        .param("id", "999")
                        .param("first_name", "Admin")
                        .param("username", "newname")
                        .param("auth_date", "1000050")
                        .param("hash", hash2))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard/overview"));

        assertThat(dashboardUsers.findByTelegramId(999L))
                .isPresent()
                .hasValueSatisfying(u -> assertThat(u.getUsername()).isEqualTo("newname"));
        assertThat(dashboardUsers.findAllByRole(
                com.tcleaner.dashboard.domain.DashboardRole.ADMIN)).hasSize(1);
    }
}
