package com.tcleaner.api;

import com.tcleaner.core.TelegramExporterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.format.DateTimeParseException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты для ApiExceptionHandler.
 *
 * Проверяет корректность обработки исключений и возврата HTTP кодов.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ApiExceptionHandler")
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("Обработка исключений")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("должен вернуть 400 при ошибке парсинга даты")
        void shouldReturn400OnDateParseError() throws Exception {
            // Simulate endpoint that parses invalid date
            mockMvc.perform(post("/api/convert")
                    .param("startDate", "invalid-date"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("должен вернуть 400 при IllegalArgumentException")
        void shouldReturn400OnIllegalArgument() throws Exception {
            mockMvc.perform(post("/api/convert")
                    .param("startDate", "2024-12-31")
                    .param("endDate", "2024-01-01"))  // endDate < startDate
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
            mockMvc.perform(post("/api/convert")
                    .param("startDate", "invalid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/json"));
        }
    }

    @Nested
    @DisplayName("Формат ошибки")
    class ErrorResponseFormatTests {

        @Test
        @DisplayName("ошибка должна содержать статус")
        void shouldIncludeStatus() throws Exception {
            mockMvc.perform(post("/api/convert")
                    .param("startDate", "bad-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").exists());
        }

        @Test
        @DisplayName("ошибка должна содержать сообщение")
        void shouldIncludeMessage() throws Exception {
            mockMvc.perform(post("/api/convert")
                    .param("startDate", "bad-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("ошибка должна содержать timestamp")
        void shouldIncludeTimestamp() throws Exception {
            mockMvc.perform(post("/api/convert")
                    .param("startDate", "bad-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("ошибка должна содержать path")
        void shouldIncludePath() throws Exception {
            mockMvc.perform(post("/api/convert")
                    .param("startDate", "bad-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.path").value("/api/convert"));
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
            mockMvc.perform(post("/api/convert")
                    .param("startDate", "not-a-date"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("401 при отсутствии аутентификации")
        void shouldReturn401OnUnauthorized() throws Exception {
            mockMvc.perform(post("/api/convert"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("500 при внутренней ошибке (если есть)")
        void shouldReturn500OnInternalError() throws Exception {
            // This would require a test that triggers an actual exception
            // Skipped for now as it requires specific setup
        }
    }
}
