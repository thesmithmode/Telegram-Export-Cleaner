package com.tcleaner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты порядка проверок в FileController.uploadFile():
 * 1. Сначала валидация файла (400), потом rate limit (429).
 */
@DisplayName("FileController — порядок проверок при загрузке")
class FileControllerRateLimitTest {

    private FileStorageService storageService;
    private ProcessingStatusService statusService;
    private FileController controller;

    @BeforeEach
    void setUp() {
        storageService = Mockito.mock(FileStorageService.class);
        statusService = Mockito.mock(ProcessingStatusService.class);
        controller = new FileController(storageService, statusService);
    }

    @Nested
    @DisplayName("Пустой файл всегда даёт 400, даже при превышении rate limit")
    class EmptyFileAlways400 {

        @Test
        @DisplayName("Первый вызов: пустой файл → 400, не 429")
        void emptyFile_returns400_notRateLimit() {
            MockMultipartFile empty = new MockMultipartFile(
                    "file", "result.json", "application/json", new byte[0]);

            ResponseEntity<Map<String, Object>> resp = controller.uploadFile(empty);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Второй вызов подряд: пустой файл → 400, не 429")
        void emptyFile_secondCall_returns400_notRateLimit() {
            MockMultipartFile empty = new MockMultipartFile(
                    "file", "result.json", "application/json", new byte[0]);

            controller.uploadFile(empty); // первый вызов
            ResponseEntity<Map<String, Object>> resp = controller.uploadFile(empty); // сразу второй

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("Файл не .json всегда даёт 400, даже при превышении rate limit")
    class NonJsonAlways400 {

        @Test
        @DisplayName("Неверное расширение → 400 при повторном вызове")
        void nonJsonFile_secondCall_returns400() {
            MockMultipartFile txt = new MockMultipartFile(
                    "file", "result.txt", "text/plain", "content".getBytes());

            controller.uploadFile(txt);
            ResponseEntity<Map<String, Object>> resp = controller.uploadFile(txt);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("Rate limit срабатывает только для корректных файлов")
    class RateLimitOnlyForValidFiles {

        @Test
        @DisplayName("Второй валидный запрос сразу после первого → 429")
        void secondValidRequest_returns429() throws Exception {
            MockMultipartFile valid = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            Mockito.when(storageService.uploadFile(Mockito.any(org.springframework.web.multipart.MultipartFile.class)))
                    .thenReturn("fake-uuid-0000-0000-0000-000000000001");
            Mockito.when(storageService.processFileAsync(Mockito.anyString()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                            ProcessingResult.success("fake-uuid-0000-0000-0000-000000000001")));

            controller.uploadFile(valid); // первый — проходит
            ResponseEntity<Map<String, Object>> resp = controller.uploadFile(valid); // второй — rate limit

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(resp.getBody()).containsKey("error");
        }

        @Test
        @DisplayName("Сообщение о rate limit содержит количество секунд ожидания")
        void rateLimitMessage_containsWaitSeconds() throws Exception {
            MockMultipartFile valid = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            Mockito.when(storageService.uploadFile(Mockito.any(org.springframework.web.multipart.MultipartFile.class)))
                    .thenReturn("fake-uuid-0000-0000-0000-000000000001");
            Mockito.when(storageService.processFileAsync(Mockito.anyString()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                            ProcessingResult.success("fake-uuid-0000-0000-0000-000000000001")));

            controller.uploadFile(valid);
            ResponseEntity<Map<String, Object>> resp = controller.uploadFile(valid);

            String errorMsg = (String) resp.getBody().get("error");
            assertThat(errorMsg).containsPattern("\\d+ сек");
        }
    }
}
