package com.tcleaner.dashboard.web;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.DashboardTestUsers;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.service.cache.CacheMetricsService;
import com.tcleaner.dashboard.dto.CacheMetricsDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC + JSON-контракт /dashboard/api/admin/cache-metrics.
 * Сервис мокается — проверяется именно гейт и сериализация DTO.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("CacheMetricsController")
class CacheMetricsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TelegramExporter mockExporter;
    @MockitoBean private CacheMetricsService cacheMetricsService;

    private static final DashboardUserDetails ADMIN = DashboardTestUsers.admin();
    private static final DashboardUserDetails USER_1 = DashboardTestUsers.user("alice", 1L);

    @Test
    @DisplayName("ADMIN: 200 + DTO")
    void adminSeesMetrics() throws Exception {
        when(cacheMetricsService.get()).thenReturn(new CacheMetricsDto(
                true, 1_000_000L, 10_000_000L, 10.0, 3L, 250L, 1700000000L,
                List.of(new CacheMetricsDto.ChatCacheEntry(
                        -1001L, null, "Test Chat", "testchat", "supergroup",
                        100L, 500_000L, 50.0, 1700000000.0)),
                List.of(new CacheMetricsDto.HeatmapBucket("hot", 2L, 700_000L),
                        new CacheMetricsDto.HeatmapBucket("warm", 1L, 300_000L),
                        new CacheMetricsDto.HeatmapBucket("cold", 0L, 0L)),
                Map.of("supergroup", new CacheMetricsDto.ChatTypeSegment(1L, 500_000L, 100L))));

        mockMvc.perform(get("/dashboard/api/admin/cache-metrics").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.usedBytes").value(1000000))
                .andExpect(jsonPath("$.limitBytes").value(10000000))
                .andExpect(jsonPath("$.pct").value(10.0))
                .andExpect(jsonPath("$.totalChats").value(3))
                .andExpect(jsonPath("$.topChats[0].title").value("Test Chat"))
                .andExpect(jsonPath("$.topChats[0].username").value("testchat"))
                .andExpect(jsonPath("$.topChats[0].chatType").value("supergroup"))
                .andExpect(jsonPath("$.heatmap[0].bucket").value("hot"))
                .andExpect(jsonPath("$.chatTypeSegmentation.supergroup.chatCount").value(1));
    }

    @Test
    @DisplayName("USER: 403 (ADMIN-only endpoint)")
    void userForbidden() throws Exception {
        mockMvc.perform(get("/dashboard/api/admin/cache-metrics").with(user(USER_1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Аноним: 302 на /login (не аутентифицирован)")
    void anonymousRedirect() throws Exception {
        mockMvc.perform(get("/dashboard/api/admin/cache-metrics"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("ADMIN + нет снапшота: 200 с available=false")
    void adminNoSnapshot() throws Exception {
        when(cacheMetricsService.get()).thenReturn(CacheMetricsDto.unavailable());

        mockMvc.perform(get("/dashboard/api/admin/cache-metrics").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.usedBytes").value(0))
                .andExpect(jsonPath("$.topChats").isEmpty());
    }
}
