package com.tcleaner;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты конфигурации безопасности.
 * Аутентификация отключена — все endpoints публичные.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityConfigTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        registry.add("app.storage.import-path", () -> tmp.resolve("tcleaner-test/import").toString());
        registry.add("app.storage.export-path", () -> tmp.resolve("tcleaner-test/export").toString());
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.autoconfigure.exclude", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testHealthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk());
    }

    @Test
    void testFileStatusIsPublic() throws Exception {
        mockMvc.perform(get("/api/files/some-id/status"))
            .andExpect(status().isOk());
    }

    @Test
    void testIndexIsPublic() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Разрешает CORS preflight запрос с валидного origin")
    void allowsCorsPreflightFromValidOrigin() throws Exception {
        mockMvc.perform(options("/api/files/upload")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    @DisplayName("Разрешает GET запрос с CORS headers")
    void allowsSimpleGetRequest() throws Exception {
        mockMvc.perform(get("/api/files/status/test-id")
                .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Разрешает второй localhost origin")
    void allowsCorsFromSecondLocalhost() throws Exception {
        mockMvc.perform(options("/api/files/upload")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    }
}
