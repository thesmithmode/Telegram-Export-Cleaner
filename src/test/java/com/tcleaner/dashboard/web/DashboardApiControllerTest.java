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
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC-matrix интеграционные тесты {@code /dashboard/api/**}.
 * Использует {@code SecurityMockMvcRequestPostProcessors.user(...)} для подстановки
 * {@link DashboardUserDetails} в SecurityContext (нельзя обычным {@code @WithMockUser},
 * т.к. он создаёт generic-principal без {@code botUserId}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("DashboardApiController")
class DashboardApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BotUserRepository botUserRepo;
    @Autowired private ChatRepository chatRepo;
    @Autowired private ExportEventRepository eventRepo;
    @Autowired private EntityManager em;
    @Autowired private CacheManager cacheManager;

    @MockitoBean private TelegramExporter mockExporter;

    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();
    private static final DashboardUserDetails USER_1 = DashboardTestUsers.user("alice", 1L);

    @BeforeEach
    void seed() {
        botUserRepo.save(BotUser.builder()
                .botUserId(1L).username("alice").displayName("Alice")
                .firstSeen(Instant.parse("2026-01-01T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T12:00:00Z"))
                .totalExports(2).totalMessages(300L).totalBytes(3000L).build());
        botUserRepo.save(BotUser.builder()
                .botUserId(2L).username("bob").displayName("Bob")
                .firstSeen(Instant.parse("2026-02-01T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T15:00:00Z"))
                .totalExports(1).totalMessages(50L).totalBytes(500L).build());

        Chat chat = chatRepo.save(Chat.builder()
                .canonicalChatId("-100chat1").chatIdRaw("@chat1")
                .chatTitle("Test Chat")
                .firstSeen(Instant.parse("2026-04-10T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T12:00:00Z")).build());

        eventRepo.save(event("t1", 1L, chat.getId(),
                Instant.parse("2026-04-10T12:00:00Z"), ExportStatus.COMPLETED, 100L, 1000L));
        eventRepo.save(event("t2", 1L, chat.getId(),
                Instant.parse("2026-04-12T12:00:00Z"), ExportStatus.COMPLETED, 200L, 2000L));
        eventRepo.save(event("t3", 2L, chat.getId(),
                Instant.parse("2026-04-14T12:00:00Z"), ExportStatus.FAILED, 50L, 500L));
        em.flush();
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    // ─── /me ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("/me для ADMIN: role=ADMIN, botUserId=null")
    void meAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/api/me").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.botUserId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("/me для USER: role=USER, botUserId=1")
    void meUser() throws Exception {
        mockMvc.perform(get("/dashboard/api/me").with(user(USER_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.botUserId").value(1));
    }

    // ─── /stats/overview ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: /overview без userId — суммирует всех")
    void overviewAdminNoFilter() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/overview")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExports").value(3))
                .andExpect(jsonPath("$.totalMessages").value(350));
    }

    @Test
    @DisplayName("ADMIN: /overview?userId=2 — только bob")
    void overviewAdminFilteredByUser() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/overview")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .param("userId", "2")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExports").value(1));
    }

    @Test
    @DisplayName("USER: /overview — всегда своя статистика")
    void overviewUserOnlyOwn() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/overview")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .with(user(USER_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExports").value(2));
    }

    @Test
    @DisplayName("USER: /overview?userId=2 — 403 (чужой id)")
    void overviewUserForbiddenOnOtherId() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/overview")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .param("userId", "2")
                        .with(user(USER_1)))
                .andExpect(status().isForbidden());
    }

    // ─── /stats/users (ADMIN-only) ───────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: /stats/users — список юзеров")
    void usersListAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/users").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2))));
    }

    @Test
    @DisplayName("USER: /stats/users — 403 (URL-guard в security config)")
    void usersListUserForbidden() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/users").with(user(USER_1)))
                .andExpect(status().isForbidden());
    }

    // ─── /stats/user/{botUserId} ─────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: /stats/user/1 — деталь alice")
    void userDetailAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/user/1").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    @DisplayName("USER_1: /stats/user/1 — свои данные, 200")
    void userDetailUserOwn() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/user/1").with(user(USER_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    @DisplayName("USER_1: /stats/user/2 — чужие данные, 403")
    void userDetailUserForbidden() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/user/2").with(user(USER_1)))
                .andExpect(status().isForbidden());
    }

    // ─── /stats/chats ────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: /stats/chats — три экспорта в чате")
    void chatsAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/chats")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exportCount").value(3));
    }

    // ─── /stats/timeseries ───────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: /stats/timeseries?metric=exports — все 8 дней (пустые = 0)")
    void timeSeriesAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/timeseries")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .param("metric", "exports")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(8)));
    }

    @Test
    @DisplayName("/stats/timeseries?granularity=bogus — 400")
    void timeSeriesBadGranularity() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/timeseries")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .param("granularity", "bogus")
                        .with(user(ADMIN)))
                .andExpect(status().isBadRequest());
    }

    // ─── /stats/status-breakdown ─────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: /stats/status-breakdown — COMPLETED=2, FAILED=1")
    void statusBreakdownAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/status-breakdown")
                        .param("period", "custom")
                        .param("from", "2026-04-08")
                        .param("to", "2026-04-15")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.COMPLETED").value(2))
                .andExpect(jsonPath("$.FAILED").value(1));
    }

    // ─── /stats/recent ───────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: /stats/recent — 3 записи DESC по started_at")
    void eventsAdmin() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/recent").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$[0].taskId").value("t3"));
    }

    @Test
    @DisplayName("USER_1: /stats/recent — только свои 2 записи")
    void eventsUserOnlyOwn() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/recent").with(user(USER_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    @DisplayName("USER_1: /stats/recent?userId=2 — 403")
    void eventsUserForbidden() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/recent")
                        .param("userId", "2")
                        .with(user(USER_1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN: /stats/recent?status=FAILED — только 1 запись")
    void eventsFilterByStatus() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/recent")
                        .param("status", "FAILED")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    // ─── Bad params ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("/stats/timeseries?from=bad-date → 400")
    void badDateParam() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/timeseries")
                        .param("period", "custom")
                        .param("from", "not-a-date")
                        .with(user(ADMIN)))
                .andExpect(status().isBadRequest());
    }

    private ExportEvent event(String taskId, long botUserId, long chatRefId,
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
