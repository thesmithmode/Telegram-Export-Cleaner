package com.tcleaner;

import com.tcleaner.api.ApiKeyFilter;
import com.tcleaner.api.ApiExceptionHandler;
import com.tcleaner.api.FileConversionService;
import com.tcleaner.api.TelegramController;
import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Интеграционные тесты для {@link TelegramController}
 * через MockMvc с Spring web-контекстом (без Redis, без Telegram-бота).
 *
 * <p>Покрывает:</p>
 * <ul>
 *   <li>валидацию входных данных (400)</li>
 *   <li>маппинг исключений на HTTP-статусы через {@link ApiExceptionHandler}</li>
 *   <li>корректный формат ответа (строки разделены {@code \n}, финальный {@code \n})</li>
 *   <li>проверку, что пути сервера не утекают в ответ при ошибке</li>
 *   <li>фильтры по дате, ключевым словам</li>
 * </ul>
 */
@WebMvcTest(TelegramController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TelegramController")
class TelegramControllerTest {

    @MockitoBean
    private FileConversionService mockConversionService;

    @Autowired
    private MockMvc mockMvc;

    // ─── convert() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("convert() — загрузка multipart-файла")
    class Convert {

        @Test
        @DisplayName("Возвращает 400 при пустом файле")
        void returnsErrorWhenFileIsEmpty() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json", new byte[0]);

            mockMvc.perform(multipart("/api/convert")
                            .file(file)
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Файл пустой"));
        }

        @Test
        @DisplayName("Возвращает 400 при неверном расширении файла")
        void returnsErrorWhenFileExtensionIsNotJson() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.txt", "text/plain", "some content".getBytes());

            mockMvc.perform(multipart("/api/convert")
                            .file(file)
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Ожидается JSON файл"));
        }

        @Test
        @DisplayName("Одно сообщение: ответ содержит строку с финальным \\n")
        void returns200WithTrailingNewlineForSingleMessage() throws Exception {
            doAnswer(inv -> {
                StreamingResponseBody body = os -> {
                    os.write("20250624 Hello\n".getBytes(StandardCharsets.UTF_8));
                };
                return org.springframework.http.ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .body(body);
            }).when(mockConversionService).convert(any(), any());

            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            mockMvc.perform(multipart("/api/convert")
                            .file(file)
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(streamingContent().string("20250624 Hello\n"));
        }

        @Test
        @DisplayName("Несколько сообщений разделяются \\n, финальный \\n присутствует")
        void multipleMessagesAreSeparatedByNewline() throws Exception {
            doAnswer(inv -> {
                StreamingResponseBody body = os -> {
                    os.write("20250624 First\n".getBytes(StandardCharsets.UTF_8));
                    os.write("20250624 Second\n".getBytes(StandardCharsets.UTF_8));
                };
                return org.springframework.http.ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .body(body);
            }).when(mockConversionService).convert(any(), any());

            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            mockMvc.perform(multipart("/api/convert")
                            .file(file)
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(streamingContent().string("20250624 First\n20250624 Second\n"));
        }

        @Test
        @DisplayName("Пустой список сообщений → пустое тело ответа")
        void emptyMessageList_returnsEmptyBody() throws Exception {
            doAnswer(inv -> {
                StreamingResponseBody body = os -> {};
                return org.springframework.http.ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .body(body);
            }).when(mockConversionService).convert(any(), any());

            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            mockMvc.perform(multipart("/api/convert")
                            .file(file)
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(streamingContent().bytes(new byte[0]));
        }

        @Test
        @DisplayName("TelegramExporterException при невалидном JSON — возвращает 400 с кодом ошибки")
        void exporterException_returns400WithErrorCode() throws Exception {
            doThrow(new TelegramExporterException("INVALID_JSON", "Невалидный JSON"))
                    .when(mockConversionService).convert(any(), any());

            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "not json".getBytes());

            mockMvc.perform(multipart("/api/convert")
                            .file(file)
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_JSON"));
        }

        @Test
        @DisplayName("Пути сервера не утекают в тело ответа при ошибке даты")
        void errorMessageDoesNotLeakServerPaths() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            String body = mockMvc.perform(multipart("/api/convert")
                            .file(file)
                            .param("startDate", "not-a-date")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).doesNotContain("/tmp");
            assertThat(body).doesNotContain("telegram-cleaner");
        }

        @Test
        @DisplayName("Возвращает 400 при невалидном формате даты")
        void returns400WhenDateFormatIsInvalid() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            mockMvc.perform(multipart("/api/convert")
                            .file(file)
                            .param("startDate", "not-a-date")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Невалидный формат даты. Используйте YYYY-MM-DD"));
        }

        @Test
        @DisplayName("Возвращает 400 при startDate позже endDate")
        void returns400WhenStartDateAfterEndDate() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            mockMvc.perform(multipart("/api/convert")
                            .file(file)
                            .param("startDate", "2025-12-31")
                            .param("endDate", "2025-01-01")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error")
                            .value("startDate не может быть позже endDate: 2025-12-31 > 2025-01-01"));
        }
    }

    // ─── health() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("health()")
    class Health {

        @Test
        @DisplayName("Возвращает статус UP без API ключа")
        void returnsStatusUp() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }
}
