package com.tcleaner.api;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.core.TelegramExporterException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ApiExceptionHandler")
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiExceptionHandler handler;

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
                    .param("startDate", "invalid-date"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("должен вернуть 400 при IllegalArgumentException")
        void shouldReturn400OnIllegalArgument() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "2024-12-31")
                    .param("endDate", "2024-01-01"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("должен вернуть JSON при ошибке")
        void shouldReturnJsonOnError() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "invalid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/json"));
        }

        @Test
        @DisplayName("должен вернуть 400 при передаче нечислового botUserId (TypeMismatch)")
        void shouldReturn400OnTypeMismatch() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("botUserId", "not-a-number"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").exists());
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
                    .param("startDate", "bad-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("ошибка не должна раскрывать type (anti-fingerprinting)")
        void shouldNotIncludeType() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "bad-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").doesNotExist());
        }

        @Test
        @DisplayName("ошибка не должна раскрывать details (anti-fingerprinting)")
        void shouldNotIncludeDetails() throws Exception {
            mockMvc.perform(multipart("/api/convert")
                    .file(dummyFile())
                    .param("startDate", "bad-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details").doesNotExist());
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
                    .param("startDate", "not-a-date"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Прямой вызов хэндлеров (unit)")
    class DirectHandlerTests {

        @Test
        @DisplayName("handleConstraintViolation → 400 + error=validation_failed")
        void handleConstraintViolation_returns400WithValidationFailed() {
            var ex = new ConstraintViolationException(Collections.emptySet());
            var response = handler.handleConstraintViolation(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("validation_failed", response.getBody().get("error"));
        }

        @Test
        @DisplayName("handleExporterException → 400 + error code из исключения")
        void handleExporterException_returns400WithErrorCode() {
            var ex = new TelegramExporterException("INVALID_FILE", "плохой формат файла");
            var response = handler.handleExporterException(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("INVALID_FILE", response.getBody().get("error"));
            assertEquals("плохой формат файла", response.getBody().get("message"));
        }

        @Test
        @DisplayName("handleGenericException → 500 + generic message без деталей")
        void handleGenericException_returns500WithoutDetails() {
            var ex = new RuntimeException("crash: internal state corrupted");
            var response = handler.handleGenericException(ex);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("Внутренняя ошибка сервера", response.getBody().get("message"));
            assertEquals("InternalError", response.getBody().get("type"));
        }
    }
}
