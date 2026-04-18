package com.tcleaner.dashboard.config;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Матрица изоляции ролей — базовый enterprise-smoke для RBAC.
 * Цель: USER не должен ни прямо, ни через подмену query-параметров, ни через
 * обход URL попасть в admin-раздел или admin-API. Любая попытка → тихий
 * редирект на /dashboard/me (AccessDeniedHandler), без раскрытия существования
 * admin-страниц.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("DashboardAccessIsolation")
class DashboardAccessIsolationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BotUserRepository botUserRepo;
    @Autowired private ChatRepository chatRepo;
    @Autowired private ExportEventRepository eventRepo;
    @Autowired private EntityManager em;
    @Autowired private CacheManager cacheManager;

    @MockitoBean private TelegramExporter mockExporter;

    private static final DashboardUserDetails USER_42 = DashboardTestUsers.user("alice", 42L);
    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();

    @BeforeEach
    void seed() {
        botUserRepo.save(BotUser.builder()
                .botUserId(42L).username("alice").displayName("Alice")
                .firstSeen(Instant.parse("2026-01-01T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T12:00:00Z"))
                .totalExports(1).totalMessages(100L).totalBytes(1000L).build());
        botUserRepo.save(BotUser.builder()
                .botUserId(99L).username("mallory").displayName("Mallory")
                .firstSeen(Instant.parse("2026-02-01T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T15:00:00Z"))
                .totalExports(9).totalMessages(9000L).totalBytes(90000L).build());

        Chat chat = chatRepo.save(Chat.builder()
                .canonicalChatId("-100c").chatIdRaw("@c").chatTitle("C")
                .firstSeen(Instant.parse("2026-04-10T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T12:00:00Z")).build());

        eventRepo.save(ExportEvent.builder()
                .taskId("a1").botUserId(42L).chatRefId(chat.getId())
                .startedAt(Instant.parse("2026-04-12T12:00:00Z"))
                .status(ExportStatus.COMPLETED).messagesCount(100L).bytesCount(1000L)
                .source(ExportSource.BOT)
                .createdAt(Instant.parse("2026-04-12T12:00:00Z"))
                .updatedAt(Instant.parse("2026-04-12T12:00:00Z")).build());
        eventRepo.save(ExportEvent.builder()
                .taskId("m1").botUserId(99L).chatRefId(chat.getId())
                .startedAt(Instant.parse("2026-04-14T12:00:00Z"))
                .status(ExportStatus.COMPLETED).messagesCount(9000L).bytesCount(90000L)
                .source(ExportSource.BOT)
                .createdAt(Instant.parse("2026-04-14T12:00:00Z"))
                .updatedAt(Instant.parse("2026-04-14T12:00:00Z")).build());
        em.flush();
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    // ─── Публичный login ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /dashboard/login: anon=200, USER/ADMIN → landing по роли")
    void loginPage() throws Exception {
        mockMvc.perform(get("/dashboard/login")).andExpect(status().isOk());
        mockMvc.perform(get("/dashboard/login").with(user(USER_42)))
                .andExpect(redirectedUrl("/dashboard/me"));
        mockMvc.perform(get("/dashboard/login").with(user(ADMIN)))
                .andExpect(redirectedUrl("/dashboard/overview"));
    }

    @Test
    @DisplayName("GET /dashboard: anon→/login, USER→/me, ADMIN→/overview")
    void rootRouting() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
        mockMvc.perform(get("/dashboard").with(user(USER_42)))
                .andExpect(redirectedUrl("/dashboard/me"));
        mockMvc.perform(get("/dashboard").with(user(ADMIN)))
                .andExpect(redirectedUrl("/dashboard/overview"));
    }

    // ─── Admin-страницы: USER тихо улетает на /me ────────────────────────────

    @Test
    @DisplayName("USER на admin-страницах → silent redirect /dashboard/me")
    void userCannotReachAdminPages() throws Exception {
        for (String url : new String[]{
                "/dashboard/overview",
                "/dashboard/users",
                "/dashboard/user/42",
                "/dashboard/user/99",
                "/dashboard/chats",
                "/dashboard/events"}) {
            mockMvc.perform(get(url).with(user(USER_42)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/dashboard/me"));
        }
    }

    @Test
    @DisplayName("ADMIN на admin-страницах → 200")
    void adminReachesAdminPages() throws Exception {
        for (String url : new String[]{
                "/dashboard/overview",
                "/dashboard/users",
                "/dashboard/user/42",
                "/dashboard/chats",
                "/dashboard/events"}) {
            mockMvc.perform(get(url).with(user(ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Анонимный → 302 на /login для любого защищённого URL")
    void anonymousRedirectsToLogin() throws Exception {
        for (String url : new String[]{
                "/dashboard/overview",
                "/dashboard/users",
                "/dashboard/me",
                "/dashboard/api/stats/overview",
                "/dashboard/api/me/overview"}) {
            mockMvc.perform(get(url))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/dashboard/login"));
        }
    }

    // ─── Admin-API: USER не долетает ────────────────────────────────────────

    @Test
    @DisplayName("USER на admin-only API → 403 (ADMIN-exclusive endpoints)")
    void userForbiddenFromAdminOnlyApi() throws Exception {
        for (String url : new String[]{
                "/dashboard/api/stats/users",
                "/dashboard/api/stats/chats",
                "/dashboard/api/stats/timeseries",
                "/dashboard/api/stats/status-breakdown"}) {
            mockMvc.perform(get(url).with(user(USER_42)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("USER IDOR-попытка → 403 (чужой botUserId в stats/user/{id})")
    void userCannotReachOtherUserStats() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/user/99").with(user(USER_42)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("USER на свои stats-эндпоинты → 200 (overview, user/own, events)")
    void userCanReachOwnStatsEndpoints() throws Exception {
        mockMvc.perform(get("/dashboard/api/stats/overview").with(user(USER_42)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/dashboard/api/stats/user/42").with(user(USER_42)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/dashboard/api/stats/recent").with(user(USER_42)))
                .andExpect(status().isOk());
    }

    // ─── Личное API: USER видит свои данные, IDOR защищён ────────────────────

    @Test
    @DisplayName("USER → /api/me/overview — данные по своему botUserId=42")
    void userGetsOwnMeOverview() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/overview").with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExports").value(1))
                .andExpect(jsonPath("$.totalMessages").value(100));
    }

    @Test
    @DisplayName("IDOR: USER → /api/me/overview?userId=99 — игнорируется, возвращаются данные по 42")
    void idorAttemptIgnored() throws Exception {
        ResultActions r = mockMvc.perform(get("/dashboard/api/me/overview")
                        .param("userId", "99")
                        .with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExports").value(1))
                .andExpect(jsonPath("$.totalMessages").value(100));
        // НЕ 9000 и НЕ 9 — чужие данные mallory (botUserId=99)
    }

    @Test
    @DisplayName("USER → /api/me/events — только свои (1 запись), не видит чужих")
    void userEventsScopedToSelf() throws Exception {
        mockMvc.perform(get("/dashboard/api/me/events").with(user(USER_42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].taskId").value("a1"));
    }

    @Test
    @DisplayName("/dashboard/me доступен и USER, и ADMIN")
    void mePageAccessibleToBothRoles() throws Exception {
        mockMvc.perform(get("/dashboard/me").with(user(USER_42)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/dashboard/me").with(user(ADMIN)))
                .andExpect(status().isOk());
    }
}
