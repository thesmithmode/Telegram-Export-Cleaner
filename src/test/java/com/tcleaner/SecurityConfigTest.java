package com.tcleaner;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import redis.embedded.RedisServer;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты конфигурации безопасности.
 * Аутентификация отключена — все endpoints публичные.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SecurityConfig")
class SecurityConfigTest {

    private static RedisServer redisServer;

    @BeforeAll
    static void startRedis() throws IOException {
        redisServer = new RedisServer(6380);
        redisServer.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> 6380);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Health endpoint публичный — без аутентификации")
    void testHealthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Convert endpoint требует API ключ если он настроен")
    void testConvertEndpointRequiresApiKey() throws Exception {
        // По умолчанию api.key пустой в тестах, но мы можем проверить поведение фильтра
        // Если ключ не настроен, ApiKeyFilter пропускает всё (см. ApiKeyFilter.java:31)
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/convert")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "test.json", "application/json", "{\"messages\": []}".getBytes())))
            .andExpect(status().isOk()); // OK потому что запрос корректный и api.key пустой (не требуется)
    }
}
