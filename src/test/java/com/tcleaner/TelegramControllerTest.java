package com.tcleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты для TelegramController.
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

    @Nested
    @DisplayName("convert() - загрузка файла")
    class Convert {

        @Test
        @DisplayName("Возвращает ошибку при null originalFilename")
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
        @DisplayName("Возвращает ошибку при пустом файле")
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
        @DisplayName("Возвращает ошибку при неверном расширении файла")
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
        @DisplayName("Возвращает 200 при корректном файле")
        void returns200ForValidFile() throws IOException {
            TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
            when(mockExporter.processFile(any(Path.class), any()))
                    .thenReturn(List.of("20250624 Hello"));

            TelegramController ctrl = new TelegramController(mockExporter);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            ResponseEntity<?> response = ctrl.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("20250624 Hello\n");
        }

        @Test
        @DisplayName("Возвращает 400 при TelegramExporterException с INVALID_JSON")
        void returns400WhenExporterThrowsInvalidJson() throws IOException {
            TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
            when(mockExporter.processFile(any(Path.class), any()))
                    .thenThrow(new TelegramExporterException("INVALID_JSON", "Невалидный JSON"));

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
        @DisplayName("Сообщение об ошибке не содержит путей к файлам сервера")
        void errorMessageDoesNotLeakServerPaths() throws IOException {
            TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
            when(mockExporter.processFile(any(Path.class), any()))
                    .thenThrow(new IOException("/tmp/telegram-cleaner12345/result.json not found"));

            TelegramController ctrl = new TelegramController(mockExporter);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            ResponseEntity<?> response = ctrl.convert(file, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            String body = response.getBody().toString();
            assertThat(body).doesNotContain("/tmp");
            assertThat(body).doesNotContain("telegram-cleaner");
        }
    }

    @Nested
    @DisplayName("convertJson() - отправка JSON напрямую")
    class ConvertJson {

        @Test
        @DisplayName("Возвращает ошибку для пустого тела")
        void returnsErrorForEmptyBody() {
            ResponseEntity<?> response = controller.convertJson("", null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).isEqualTo("Пустое содержимое");
        }

        @Test
        @DisplayName("Возвращает 200 при корректном JSON")
        void returns200ForValidJson() throws IOException {
            TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
            when(mockExporter.processFile(any(Path.class), any()))
                    .thenReturn(List.of("20250624 Test"));

            TelegramController ctrl = new TelegramController(mockExporter);

            ResponseEntity<?> response = ctrl.convertJson(
                    "{\"messages\":[]}", null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("20250624 Test\n");
        }

        @Test
        @DisplayName("Возвращает 400 при TelegramExporterException")
        void returns400WhenExporterThrows() throws IOException {
            TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);
            when(mockExporter.processFile(any(Path.class), any()))
                    .thenThrow(new TelegramExporterException("INVALID_JSON", "Невалидный JSON"));

            TelegramController ctrl = new TelegramController(mockExporter);

            ResponseEntity<?> response = ctrl.convertJson(
                    "not valid json", null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Возвращает 400 при startDate позже endDate")
        void returns400WhenStartDateAfterEndDate() {
            ResponseEntity<?> response = controller.convertJson(
                    "{\"messages\":[]}", "2025-12-31", "2025-01-01", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).contains("startDate");
        }
    }

    @Nested
    @DisplayName("convertJson() — дополнительные проверки")
    class ConvertJsonExtra {

        @Test
        @DisplayName("Возвращает 400 при слишком большом содержимом (> 10MB)")
        void returns400WhenBodyTooLarge() {
            String huge = "x".repeat(10 * 1024 * 1024 + 1);

            ResponseEntity<?> response = controller.convertJson(huge, null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).contains("10MB");
        }
    }

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
