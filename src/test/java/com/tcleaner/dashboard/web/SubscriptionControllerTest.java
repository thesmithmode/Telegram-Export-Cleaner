package com.tcleaner.dashboard.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.DashboardTestUsers;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.SubscriptionStatus;
import com.tcleaner.dashboard.dto.CreateSubscriptionRequest;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.service.ingestion.ChatUpserter;
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
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SubscriptionController")
class SubscriptionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private SubscriptionService subscriptionService;
    @MockitoBean private ChatUpserter chatUpserter;
    @MockitoBean private ChatRepository chatRepository;
    @MockitoBean private TelegramExporter mockExporter;

    private static final DashboardUserDetails ADMIN   = DashboardTestUsers.admin();
    private static final DashboardUserDetails USER_1  = DashboardTestUsers.user("alice", 1L);
    private static final DashboardUserDetails UNBOUND = DashboardTestUsers.unboundUser();

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

    @Test
    @DisplayName("list: USER видит только свои подписки")
    void listUser_seesOnlyOwn() throws Exception {
        ChatSubscription sub = stubSubscription(10L, 1L);
        when(subscriptionService.listForUser(1L)).thenReturn(List.of(sub));

        mockMvc.perform(get("/dashboard/api/subscriptions")
                        .param("userId", "999")
                        .with(user(USER_1)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].botUserId").value(1))
                .andExpect(jsonPath("$[0].id").value(10));

        verify(subscriptionService).listForUser(1L);
    }

    @Test
    @DisplayName("create: USER создаёт свою")
    void create_user_ownSubscription_201() throws Exception {
        Instant sinceDate = Instant.parse("2026-04-23T09:00:00Z");
        CreateSubscriptionRequest req = new CreateSubscriptionRequest(
                "100", 24, "09:00", sinceDate);

        Chat chatStub = Chat.builder().id(100L).chatIdRaw("100").build();
        when(chatUpserter.upsert(any(), eq("100"), any(), any(), any())).thenReturn(chatStub);

        ChatSubscription created = stubSubscription(10L, 1L);
        when(subscriptionService.create(eq(1L), eq(100L), eq(24), eq("09:00"), eq(sinceDate)))
                .thenReturn(created);

        mockMvc.perform(post("/dashboard/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(USER_1)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    @DisplayName("create: ADMIN → 403")
    void create_admin_403() throws Exception {
        CreateSubscriptionRequest req = new CreateSubscriptionRequest(
                "100", 24, "09:00", Instant.parse("2026-04-23T12:00:00Z"));

        mockMvc.perform(post("/dashboard/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(ADMIN)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("pause: USER свою → 200")
    void pause_user_ownSubscription_200() throws Exception {
        ChatSubscription sub = stubSubscription(10L, 1L);
        when(subscriptionService.findById(10L)).thenReturn(Optional.of(sub));

        ChatSubscription paused = stubSubscription(10L, 1L);
        paused.setStatus(SubscriptionStatus.PAUSED);
        when(subscriptionService.pause(10L)).thenReturn(paused);

        mockMvc.perform(patch("/dashboard/api/subscriptions/10/pause")
                        .with(user(USER_1)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

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

    @Test
    @DisplayName("list: USER без botUserId → 401")
    void list_unboundUser_401() throws Exception {
        mockMvc.perform(get("/dashboard/api/subscriptions")
                        .with(user(UNBOUND)))
                .andExpect(status().isUnauthorized());
    }
}
