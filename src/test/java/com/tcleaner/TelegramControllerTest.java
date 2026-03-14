package com.tcleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.mockito.Mockito.mock;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Тесты для TelegramController.
 *
 * Ожидаемое поведение:
 * - convert() возвращает ошибку при null originalFilename
 * - convert() возвращает ошибку при пустом файле
 * - convert() принимает только JSON файлы
 * - Controller работает с интерфейсом TelegramExporterInterface
 */
@DisplayName("TelegramController")
class TelegramControllerTest {

    private final TelegramExporter exporter = new TelegramExporter();
    private final TelegramController controller = new TelegramController(exporter);

    @Test
    @DisplayName("Конструктор принимает интерфейс TelegramExporterInterface")
    void constructorAcceptsInterface() {
        // given: мок интерфейса
        TelegramExporterInterface mockExporter = mock(TelegramExporterInterface.class);

        // when: создаём контроллер с моком
        TelegramController controllerWithMock = new TelegramController(mockExporter);

        // then: контроллер создан без ошибок
        assertThat(controllerWithMock).isNotNull();
    }

    @Nested
    @DisplayName("convert() - загрузка файла")
    class Convert {

        @Test
        @DisplayName("Возвращает ошибку при null originalFilename")
        void returnsErrorWhenOriginalFilenameIsNull() {
            // given: файл с null originalFilename (как может быть в некоторых HTTP клиентах)
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    null,
                    "application/json",
                    "{\"messages\": []}".getBytes()
            );

            // when
            ResponseEntity<?> response = controller.convert(file, null, null, null, null);

            // then: не должен NPE, должен вернуть 400
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).contains("JSON");
        }

        @Test
        @DisplayName("Возвращает ошибку при пустом файле")
        void returnsErrorWhenFileIsEmpty() {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "result.json",
                    "application/json",
                    new byte[0]
            );

            // when
            ResponseEntity<?> response = controller.convert(file, null, null, null, null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).isEqualTo("Файл пустой");
        }

        @Test
        @DisplayName("Возвращает ошибку при неверном расширении файла")
        void returnsErrorWhenFileExtensionIsNotJson() {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "result.txt",
                    "text/plain",
                    "some content".getBytes()
            );

            // when
            ResponseEntity<?> response = controller.convert(file, null, null, null, null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body.get("error")).isEqualTo("Ожидается JSON файл");
        }
    }
}
