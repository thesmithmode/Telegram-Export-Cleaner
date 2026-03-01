package com.tcleaner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты для проверки безопасности.
 * Проверяет что FileController endpoints требуют аутентификацию.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

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
