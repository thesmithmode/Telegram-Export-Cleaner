package com.tcleaner.api;

import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "api.key=test-api-key")
@DisplayName("ApiExceptionHandler")
class ApiExceptionHandlerTest {

    private static final String API_KEY = "test-api-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramExporter mockExporter;

    private MockMultipartFile dummyFile() {
        return new MockMultipartFile("file", "test.json", "application/json", "{}".getBytes());
    }

    @Nested
    @DisplayName("Обработка исключений")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("должен вернуть 400 при ошибке парсинга даты")
        void shouldReturn400OnDateParseError() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "invalid-date")
                    .header("X-API-Key", API_KEY))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("должен вернуть 400 при IllegalArgumentException")
        void shouldReturn400OnIllegalArgument() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "2024-12-31")
                    .param("endDate", "2024-01-01")
                    .header("X-API-Key", API_KEY))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("должен вернуть 401 при отсутствии API ключа")
        void shouldReturn401OnMissingApiKey() throws Exception {
            mockMvc.perform(post("/api/convert"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("должен вернуть 401 при неверном API ключе")
        void shouldReturn401OnInvalidApiKey() throws Exception {
            mockMvc.perform(post("/api/convert")
                    .header("X-API-Key", "wrong-key"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("должен вернуть JSON при ошибке")
        void shouldReturnJsonOnError() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "invalid")
                    .header("X-API-Key", API_KEY))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/json"));
        }
    }

    @Nested
    @DisplayName("Формат ошибки")
    class ErrorResponseFormatTests {

        @Test
        @DisplayName("ошибка должна содержать message")
        void shouldIncludeMessage() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "bad-date")
                    .header("X-API-Key", API_KEY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("ошибка должна содержать type")
        void shouldIncludeType() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "bad-date")
                    .header("X-API-Key", API_KEY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").exists());
        }

        @Test
        @DisplayName("ошибка должна содержать details")
        void shouldIncludeDetails() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "bad-date")
                    .header("X-API-Key", API_KEY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details").exists());
        }
    }

    @Nested
    @DisplayName("HTTP статус коды")
    class HttpStatusCodesTests {

        @Test
        @DisplayName("200 при успешной обработке")
        void shouldReturn200OnSuccess() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("400 при плохом запросе")
        void shouldReturn400OnBadRequest() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "not-a-date")
                    .header("X-API-Key", API_KEY))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("401 при отсутствии аутентификации")
        void shouldReturn401OnUnauthorized() throws Exception {
            mockMvc.perform(post("/api/convert"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
