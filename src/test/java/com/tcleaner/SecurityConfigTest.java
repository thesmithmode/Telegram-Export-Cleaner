package com.tcleaner;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты для проверки безопасности.
 * Проверяет что FileController endpoints требуют аутентификацию.
 * Использует Testcontainers для запуска Redis.
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

    /**
     * Тест: GET /api/files/{fileId}/status требует аутентификацию.
     */
    @Test
    void testFileStatusRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/files/test-file-id/status"))
            .andExpect(status().isUnauthorized());
    }

    /**
     * Тест: GET /api/files/{fileId}/download требует аутентификацию.
     */
    @Test
    void testFileDownloadRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/files/test-file-id/download"))
            .andExpect(status().isUnauthorized());
    }

    /**
     * Тест: GET /api/health не требует аутентификацию (публичный endpoint).
     */
    @Test
    void testHealthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk());
    }

    /**
     * Тест: POST /api/files/upload требует аутентификацию.
     */
    @Test
    void testFileUploadRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/files/upload"))
            .andExpect(status().isUnauthorized());
    }
}
