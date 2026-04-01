package com.tcleaner;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.core.TelegramExporterException;
import com.tcleaner.api.TelegramController;
import com.tcleaner.core.TelegramExporterInterface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Юнит-тесты для {@link TelegramController}.
 *
 * <p>Покрывает:</p>
 * <ul>
 *   <li>валидацию входных данных (400)</li>
 *   <li>маппинг исключений на HTTP-статусы</li>
 *   <li>корректный формат ответа (строки разделены {@code \n}, финальный {@code \n})</li>
 *   <li>проверку, что пути сервера не утекают в ответ при ошибке</li>
 *   <li>фильтры по дате, ключевым словам</li>
 * </ul>
 */
@DisplayName("TelegramController")
class TelegramControllerTest {

    private final TelegramExporter exporter = new TelegramExporter();
    private final TelegramController controller = new TelegramController(exporter);

    @Test
    @DisplayName("Конструктор принимает интерфейс TelegramExporterInterface")
    void constructorAcceptsInterface() {
        TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
        TelegramController controllerWithMock = new TelegramController(mockExporter);
        assertThat(controllerWithMock).isNotNull();
    }

    // ─── convert() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("convert() — загрузка multipart-файла")
    class Convert {

        @Test
        @DisplayName("Возвращает 400 при null originalFilename")
        void returnsErrorWhenOriginalFilenameIsNull() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", null, "application/json",
                    "{\"messages\": []}".getBytes());

            ResponseEntity<?> response = controller.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).contains("JSON");
        }

        @Test
        @DisplayName("Возвращает 400 при пустом файле")
        void returnsErrorWhenFileIsEmpty() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json", new byte[0]);

            ResponseEntity<?> response = controller.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).isEqualTo("Файл пустой");
        }

        @Test
        @DisplayName("Возвращает 400 при неверном расширении файла")
        void returnsErrorWhenFileExtensionIsNotJson() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.txt", "text/plain", "some content".getBytes());

            ResponseEntity<?> response = controller.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).isEqualTo("Ожидается JSON файл");
        }

        @Test
        @DisplayName("Одно сообщение: ответ содержит строку с финальным \\n")
        void returns200WithTrailingNewlineForSingleMessage() throws IOException {
            TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
            doAnswer(inv -> {
                Writer w = inv.getArgument(2);
                w.write("20250624 Hello\n");
                return 1;
            }).when(mockExporter).processFileStreaming(any(Path.class), any(), any(Writer.class));

            TelegramController ctrl = new TelegramController(mockExporter);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            ResponseEntity<?> response = ctrl.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((String) response.getBody()).isEqualTo("20250624 Hello\n");
        }

        @Test
        @DisplayName("Несколько сообщений разделяются \\n, финальный \\n присутствует")
        void multipleMessagesAreSeparatedByNewline() throws IOException {
            TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
            doAnswer(inv -> {
                Writer w = inv.getArgument(2);
                w.write("20250624 First\n");
                w.write("20250624 Second\n");
                return 2;
            }).when(mockExporter).processFileStreaming(any(Path.class), any(), any(Writer.class));

            TelegramController ctrl = new TelegramController(mockExporter);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            ResponseEntity<?> response = ctrl.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((String) response.getBody()).isEqualTo("20250624 First\n20250624 Second\n");
        }

        @Test
        @DisplayName("Пустой список сообщений → пустое тело ответа")
        void emptyMessageList_returnsEmptyBody() throws IOException {
            TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
            doAnswer(inv -> 0)
                    .when(mockExporter).processFileStreaming(any(Path.class), any(), any(Writer.class));

            TelegramController ctrl = new TelegramController(mockExporter);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            ResponseEntity<?> response = ctrl.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((String) response.getBody()).isEqualTo("");
        }

        @Test
        @DisplayName("TelegramExporterException при невалидном JSON — возвращает 400 с кодом ошибки")
        void exporterException_returns400WithErrorCode() throws IOException {
            TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
            doAnswer(inv -> {
                throw new TelegramExporterException("INVALID_JSON", "Невалидный JSON");
            }).when(mockExporter).processFileStreaming(any(Path.class), any(), any(Writer.class));

            TelegramController ctrl = new TelegramController(mockExporter);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "not json".getBytes());

            ResponseEntity<?> response = ctrl.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).isEqualTo("INVALID_JSON");
        }

        @Test
        @DisplayName("Пути сервера не утекают в тело ответа при ошибке даты")
        void errorMessageDoesNotLeakServerPaths() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            ResponseEntity<?> response = controller.convert(file, "not-a-date", null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            String body = response.getBody().toString();
            assertThat(body).doesNotContain("/tmp");
            assertThat(body).doesNotContain("telegram-cleaner");
        }

        @Test
        @DisplayName("Возвращает 400 при невалидном формате даты")
        void returns400WhenDateFormatIsInvalid() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            ResponseEntity<?> response = controller.convert(file, "not-a-date", null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).contains("дат");
        }

        @Test
        @DisplayName("Возвращает 400 при startDate позже endDate")
        void returns400WhenStartDateAfterEndDate() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            ResponseEntity<?> response = controller.convert(
                    file, "2025-12-31", "2025-01-01", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).contains("startDate");
        }

        @Test
        @DisplayName("Реальный экспорт: одно сообщение обрабатывается корректно")
        void realExport_singleMessage() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[{\"id\":1,\"type\":\"message\",\"date\":\"2025-06-24T10:00:00\",\"text\":\"Hello\"}]}".getBytes());

            ResponseEntity<?> response = controller.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((String) response.getBody()).isEqualTo("20250624 Hello\n");
        }
    }

    // ─── health() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("health()")
    class Health {

        @Test
        @DisplayName("Возвращает статус UP")
        void returnsStatusUp() {
            ResponseEntity<Map<String, String>> response = controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "UP");
        }
    }
}
