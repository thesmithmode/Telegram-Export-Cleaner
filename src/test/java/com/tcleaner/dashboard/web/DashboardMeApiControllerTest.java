package com.tcleaner.dashboard.web;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.DashboardTestUsers;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ExportEvent;
import com.tcleaner.dashboard.domain.ExportSource;
import com.tcleaner.dashboard.domain.ExportStatus;
import com.tcleaner.dashboard.repository.BotUserRepository;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ExportEventRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты личного API {@code /dashboard/api/me/**}.
 * Ключевой инвариант: скоуп ВСЕГДА = {@code principal.botUserId}.
 * Любой {@code ?userId=...} в query-string контроллером не принимается и ни
 * на что не влияет — защита от IDOR.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("DashboardMeApiController")
class DashboardMeApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BotUserRepository botUserRepo;
    @Autowired private ChatRepository chatRepo;
    @Autowired private ExportEventRepository eventRepo;

    @MockitoBean private TelegramExporter mockExporter;

    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();
    private static final DashboardUserDetails USER_42 = DashboardTestUsers.user("alice", 42L);
    private static final DashboardUserDetails UNBOUND = DashboardTestUsers.unboundUser();

    @BeforeEach
    void seed() {
        botUserRepo.save(BotUser.builder()
                .botUserId(42L).username("alice").displayName("Alice")
                .firstSeen(Instant.parse("2026-01-01T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T12:00:00Z"))
                .totalExports(2).totalMessages(300L).totalBytes(3000L).build());
        botUserRepo.save(BotUser.builder()
                .botUserId(999L).username("mallory").displayName("Mallory")
                .firstSeen(Instant.parse("2026-02-01T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T15:00:00Z"))
                .totalExports(5).totalMessages(5000L).totalBytes(50000L).build());

        Chat chat = chatRepo.save(Chat.builder()
                .canonicalChatId("-100chat1").chatIdRaw("@chat1")
                .chatTitle("Test Chat")
                .firstSeen(Instant.parse("2026-04-10T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T12:00:00Z")).build());

        eventRepo.save(ev("t1", 42L, chat.getId(),
                Instant.parse("2026-04-10T12:00:00Z"), ExportStatus.COMPLETED, 100L, 1000L));
        eventRepo.save(ev("t2", 42L, chat.getId(),
                Instant.parse("2026-04-12T12:00:00Z"), ExportStatus.COMPLETED, 200L, 2000L));
        eventRepo.save(ev("m1", 999L, chat.getId(),
                Instant.parse("2026-04-14T12:00:00Z"), ExportStatus.COMPLETED, 5000L, 50000L));
    }

    // ─── /overview ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("USER botUserId=42 → /overview возвращает только свои счётчики")
    void overviewScopedToPrincipal() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/overview")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExports").value(2))
                .andExpect(jsonPath("$.totalMessages").value(300));
    }

    @Test
    @DisplayName("IDOR: USER botUserId=42 + ?userId=999 — параметр игнорируется, отвечаем по 42")
    void overviewIgnoresUserIdParam() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/overview")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .param("userId", "999")
                        .with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExports").value(2))
                .andExpect(jsonPath("$.totalMessages").value(300));
    }

    @Test
    @DisplayName("USER без botUserId → пустой OverviewDto, HTTP 200")
    void overviewEmptyForUnboundUser() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/overview").with(user(UNBOUND)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExports").value(0))
                .andExpect(jsonPath("$.totalMessages").value(0))
                .andExpect(jsonPath("$.totalBytes").value(0));
    }

    @Test
    @DisplayName("ADMIN без bot-привязки → пустой DTO (ADMIN пользуется /api/stats/*)")
    void overviewAdminWithoutBotUserId() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/overview").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExports").value(0));
    }

    @Test
    @DisplayName("Анонимный → 302 на /dashboard/login")
    void overviewAnonymous() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/overview"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    // ─── /chats ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("USER → /chats возвращает чаты только по своему botUserId")
    void chatsScoped() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/chats")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exportCount").value(2));
    }

    @Test
    @DisplayName("USER botUserId=null → /chats отдаёт []")
    void chatsEmptyForUnbound() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/chats").with(user(UNBOUND)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ─── /events ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("USER → /events только свои 2 записи, не видит события чужого botUserId")
    void eventsScoped() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/events").with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    @DisplayName("USER botUserId=null → /events отдаёт []")
    void eventsEmptyForUnbound() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/events").with(user(UNBOUND)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ─── /timeseries ────────────────────────────────────────────────────────

    @Test
    @DisplayName("USER → /timeseries только свои точки")
    void timeseriesScoped() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/timeseries")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .param("metric", "exports")
                        .with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
    }

    // ─── /status-breakdown ──────────────────────────────────────────────────

    @Test
    @DisplayName("USER → /status-breakdown только по своим событиям")
    void statusBreakdownScoped() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/status-breakdown")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.COMPLETED").value(2));
    }

    private ExportEvent ev(String taskId, long botUserId, long chatRefId,
                           Instant startedAt, ExportStatus status,
                           Long messages, Long bytes) {
        return ExportEvent.builder()
                .taskId(taskId).botUserId(botUserId).chatRefId(chatRefId)
                .startedAt(startedAt).status(status)
                .messagesCount(messages).bytesCount(bytes)
                .source(ExportSource.BOT)
                .createdAt(startedAt).updatedAt(startedAt)
                .build();
    }
}
