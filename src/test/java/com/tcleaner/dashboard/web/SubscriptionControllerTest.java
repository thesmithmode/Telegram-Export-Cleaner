package com.tcleaner.dashboard.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.DashboardTestUsers;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.SubscriptionStatus;
import com.tcleaner.dashboard.dto.CreateSubscriptionRequest;
import com.tcleaner.dashboard.service.subscription.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc-тесты RBAC-матрицы {@code /dashboard/api/subscriptions/**}.
 * Аутентификация моделируется через {@code SecurityMockMvcRequestPostProcessors.user(...)},
 * который кладёт готовый {@link DashboardUserDetails} напрямую в SecurityContext —
 * тот же подход, что в {@link DashboardApiControllerTest}.
 * {@link SubscriptionService} подменяется {@code @MockitoBean}: поведение задаётся per-test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SubscriptionController")
class SubscriptionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private SubscriptionService subscriptionService;
    @MockitoBean private TelegramExporter mockExporter;

    private static final DashboardUserDetails ADMIN   = DashboardTestUsers.admin();
    private static final DashboardUserDetails USER_1  = DashboardTestUsers.user("alice", 1L);
    private static final DashboardUserDetails USER_2  = DashboardTestUsers.user("bob",   2L);
    private static final DashboardUserDetails UNBOUND = DashboardTestUsers.unboundUser();

    // ─── Вспомогательные фабрики ────────────────────────────────────────────

    private static ChatSubscription stubSubscription(long id, long botUserId) {
        Instant now = Instant.parse("2026-04-23T12:00:00Z");
        return ChatSubscription.builder()
                .id(id)
                .botUserId(botUserId)
                .chatRefId(100L)
                .periodHours(24)
                .desiredTimeMsk("09:00")
                .sinceDate(now.minusSeconds(3600))
                .status(SubscriptionStatus.ACTIVE)
                .consecutiveFailures(0)
                .lastRunAt(null)
                .lastSuccessAt(null)
                .lastFailureAt(null)
                .lastConfirmAt(now)
                .confirmSentAt(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // ─── LIST ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list: USER видит только свои подписки — сервис вызван с principal.botUserId, параметр userId игнорируется")
    void listUser_seesOnlyOwn() throws Exception {
        ChatSubscription sub = stubSubscription(10L, 1L);
        when(subscriptionService.listForUser(1L)).thenReturn(List.of(sub));

        mockMvc.perform(get("/dashboard/api/subscriptions")
                        .param("userId", "999")   // должен игнорироваться
                        .with(user(USER_1)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].botUserId").value(1))
                .andExpect(jsonPath("$[0].id").value(10));

        verify(subscriptionService).listForUser(1L);
    }

    @Test
    @DisplayName("list: ADMIN без userId — listAll()")
    void listAdmin_noUserId_callsListAll() throws Exception {
        ChatSubscription sub1 = stubSubscription(10L, 1L);
        ChatSubscription sub2 = stubSubscription(20L, 2L);
        when(subscriptionService.listAll()).thenReturn(List.of(sub1, sub2));

        mockMvc.perform(get("/dashboard/api/subscriptions")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        verify(subscriptionService).listAll();
    }

    @Test
    @DisplayName("list: ADMIN с userId — listForUser(userId)")
    void listAdmin_withUserId_callsListForUser() throws Exception {
        ChatSubscription sub = stubSubscription(20L, 2L);
        when(subscriptionService.listForUser(2L)).thenReturn(List.of(sub));

        mockMvc.perform(get("/dashboard/api/subscriptions")
                        .param("userId", "2")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].botUserId").value(2));

        verify(subscriptionService).listForUser(2L);
    }

    // ─── GET by ID ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("get: USER пытается открыть чужую → 403")
    void getById_user_foreignSubscription_403() throws Exception {
        // Подписка принадлежит user 2, запрос от user 1
        ChatSubscription foreignSub = stubSubscription(20L, 2L);
        when(subscriptionService.findById(20L)).thenReturn(Optional.of(foreignSub));

        mockMvc.perform(get("/dashboard/api/subscriptions/20")
                        .with(user(USER_1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("get: подписка не найдена → 404")
    void getById_notFound_404() throws Exception {
        when(subscriptionService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/dashboard/api/subscriptions/99")
                        .with(user(ADMIN)))
                .andExpect(status().isNotFound());
    }

    // ─── CREATE ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create: USER создаёт свою → 201 + SubscriptionDto, сервис вызван с principal.botUserId")
    void create_user_ownSubscription_201() throws Exception {
        Instant sinceDate = Instant.parse("2026-04-23T09:00:00Z");
        CreateSubscriptionRequest req = new CreateSubscriptionRequest(
                100L, 24, "09:00", sinceDate);

        ChatSubscription created = stubSubscription(10L, 1L);
        when(subscriptionService.create(eq(1L), eq(100L), eq(24), eq("09:00"), eq(sinceDate)))
                .thenReturn(created);

        mockMvc.perform(post("/dashboard/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(USER_1)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.botUserId").value(1));

        verify(subscriptionService).create(1L, 100L, 24, "09:00", sinceDate);
    }

    @Test
    @DisplayName("create: ADMIN → 403 (AccessDeniedException)")
    void create_admin_403() throws Exception {
        CreateSubscriptionRequest req = new CreateSubscriptionRequest(
                100L, 24, "09:00", Instant.parse("2026-04-23T09:00:00Z"));

        mockMvc.perform(post("/dashboard/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(ADMIN)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("create: IllegalStateException (уже есть ACTIVE) → 409")
    void create_alreadyActiveSubscription_409() throws Exception {
        Instant sinceDate = Instant.parse("2026-04-23T09:00:00Z");
        CreateSubscriptionRequest req = new CreateSubscriptionRequest(
                100L, 24, "09:00", sinceDate);

        when(subscriptionService.create(anyLong(), anyLong(), anyInt(), anyString(), any()))
                .thenThrow(new IllegalStateException("user already has an active subscription"));

        mockMvc.perform(post("/dashboard/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(USER_1)).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("create: IllegalArgumentException (невалидный period) → 400")
    void create_invalidPeriod_400() throws Exception {
        Instant sinceDate = Instant.parse("2026-04-23T09:00:00Z");
        CreateSubscriptionRequest req = new CreateSubscriptionRequest(
                100L, 999, "09:00", sinceDate);

        when(subscriptionService.create(anyLong(), anyLong(), anyInt(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("period_hours must be 24/48/72/168"));

        mockMvc.perform(post("/dashboard/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(USER_1)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // ─── PAUSE ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pause: USER чужую → 403")
    void pause_user_foreignSubscription_403() throws Exception {
        ChatSubscription foreignSub = stubSubscription(20L, 2L);
        when(subscriptionService.findById(20L)).thenReturn(Optional.of(foreignSub));

        mockMvc.perform(patch("/dashboard/api/subscriptions/20/pause")
                        .with(user(USER_1)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ─── RESUME ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resume: USER свою → 200 + обновлённый SubscriptionDto")
    void resume_user_ownSubscription_200() throws Exception {
        ChatSubscription sub = stubSubscription(10L, 1L);
        sub.setStatus(SubscriptionStatus.PAUSED);
        when(subscriptionService.findById(10L)).thenReturn(Optional.of(sub));

        ChatSubscription resumed = stubSubscription(10L, 1L);
        when(subscriptionService.resume(10L)).thenReturn(resumed);

        mockMvc.perform(patch("/dashboard/api/subscriptions/10/resume")
                        .with(user(USER_1)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ─── DELETE ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete: ADMIN любую → 204")
    void delete_admin_anySubscription_204() throws Exception {
        ChatSubscription sub = stubSubscription(20L, 2L);
        when(subscriptionService.findById(20L)).thenReturn(Optional.of(sub));

        mockMvc.perform(delete("/dashboard/api/subscriptions/20")
                        .with(user(ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());

        verify(subscriptionService).delete(20L);
    }

    // ─── RESUME: edge cases ───────────────────────────────────────────────────

    @Test
    @DisplayName("resume: ARCHIVED подписка → 409 (сервис бросает IllegalStateException про archived)")
    void resume_archivedSubscription_409() throws Exception {
        ChatSubscription archivedSub = stubSubscription(10L, 1L);
        archivedSub.setStatus(SubscriptionStatus.ARCHIVED);
        when(subscriptionService.findById(10L)).thenReturn(Optional.of(archivedSub));
        when(subscriptionService.resume(10L))
                .thenThrow(new IllegalStateException("archived subscription cannot be resumed"));

        mockMvc.perform(patch("/dashboard/api/subscriptions/10/resume")
                        .with(user(USER_1)).with(csrf()))
                .andExpect(status().isConflict());
    }

    // ─── PAUSE: ADMIN чужая ───────────────────────────────────────────────────

    @Test
    @DisplayName("pause: ADMIN может паузить чужую подписку → 200")
    void pause_admin_foreignSubscription_200() throws Exception {
        // Подписка принадлежит user 2, запрос от ADMIN
        ChatSubscription foreignSub = stubSubscription(20L, 2L);
        when(subscriptionService.findById(20L)).thenReturn(Optional.of(foreignSub));

        ChatSubscription paused = stubSubscription(20L, 2L);
        paused.setStatus(SubscriptionStatus.PAUSED);
        when(subscriptionService.pause(20L)).thenReturn(paused);

        mockMvc.perform(patch("/dashboard/api/subscriptions/20/pause")
                        .with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(20))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        verify(subscriptionService).pause(20L);
    }

    // ─── AUTH ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list: USER без botUserId (unbound) → 401")
    void list_unboundUser_401() throws Exception {
        mockMvc.perform(get("/dashboard/api/subscriptions")
                        .with(user(UNBOUND)))
                .andExpect(status().isUnauthorized());
    }
}
