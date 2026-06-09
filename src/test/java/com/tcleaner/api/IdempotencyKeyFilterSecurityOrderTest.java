package com.tcleaner.api;

import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Idempotency filter security order")
class IdempotencyKeyFilterSecurityOrderTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramExporter mockExporter;

    @MockitoBean
    private StringRedisTemplate redis;

    @Test
    @DisplayName("unauthenticated dashboard request with Idempotency-Key does not allocate Redis state")
    void unauthenticatedDashboardRequestDoesNotAllocateRedisState() throws Exception {
        mockMvc.perform(post("/dashboard/api/me/feedback")
                .header(IdempotencyKeyFilter.HEADER, "attack-dashboard-key-12345")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());

        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("API request with invalid X-API-Key does not allocate Redis state")
    void invalidApiKeyRequestDoesNotAllocateRedisState() throws Exception {
        mockMvc.perform(post("/api/convert")
                .header(IdempotencyKeyFilter.HEADER, "attack-api-key-1234567890")
                .header("X-API-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());

        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("public Telegram login request with Idempotency-Key does not allocate Redis state")
    void publicTelegramLoginRequestDoesNotAllocateRedisState() throws Exception {
        mockMvc.perform(post("/dashboard/login/telegram")
                .header(IdempotencyKeyFilter.HEADER, "attack-login-key-1234567890")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());

        verify(redis, never()).opsForValue();
    }
}
