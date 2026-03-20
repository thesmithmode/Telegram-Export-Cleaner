package com.tcleaner;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.storage.FileStorageService;
import com.tcleaner.storage.StorageConfig;
import com.tcleaner.status.ProcessingStatusService;
import com.tcleaner.status.ProcessingResult;
import com.tcleaner.api.FileController;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для FileController.
 *
 * <p>Два вложенных класса:
 * <ul>
 *   <li>{@link WithRealStorage} — интеграционные тесты с реальным диском</li>
 *   <li>{@link WithMockedStorage} — unit-тесты через Mockito (@Mock, @InjectMocks, @Captor)</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileController")
class FileControllerTest {

    @Mock
    private FileStorageService storageService;

    @Mock
    private ProcessingStatusService statusService;

    @InjectMocks
    private FileController controller;

    @Captor
    private ArgumentCaptor<String> fileIdCaptor;

    // -----------------------------------------------------------------------
    // Интеграционные тесты с реальным сервисом и файловой системой
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("С реальным хранилищем")
    class WithRealStorage {

        @TempDir
        Path tempDir;

        private FileController realController;
        private Path exportDir;

        @BeforeEach
        void setUp() throws IOException {
            Path importDir = tempDir.resolve("import");
            exportDir = tempDir.resolve("export");
            Files.createDirectories(importDir);
            Files.createDirectories(exportDir);

            StorageConfig config = new StorageConfig();
            config.setImportPath(importDir.toString());
            config.setExportPath(exportDir.toString());
            config.setExportTtlMinutes(10);

            ProcessingStatusService mockStatus = Mockito.mock(ProcessingStatusService.class);
            FileStorageService realService = new FileStorageService(
                    config, new TelegramExporter(), mockStatus);
            realController = new FileController(realService, mockStatus);
        }

        @Test
        @DisplayName("uploadFile возвращает 400 для пустого файла")
        void uploadEmptyFileReturns400() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json", new byte[0]);
            ResponseEntity<Map<String, Object>> response = realController.uploadFile(file);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("error");
        }

        @Test
        @DisplayName("uploadFile возвращает 400 для не-.json файла")
        void uploadNonJsonFileReturns400() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.txt", "text/plain", "content".getBytes());
            ResponseEntity<Map<String, Object>> response = realController.uploadFile(file);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("error");
        }

        @Test
        @DisplayName("uploadFile возвращает 400 для null имени файла")
        void uploadNullFilenameReturns400() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", null, "application/json", "{\"messages\":[]}".getBytes());
            ResponseEntity<Map<String, Object>> response = realController.uploadFile(file);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("uploadFile возвращает 202 Accepted и PENDING для корректного файла")
        void uploadValidJsonReturns202() {
            String json = "{\"messages\":[{\"id\":1,\"type\":\"message\","
                    + "\"date\":\"2025-06-24T10:00:00\",\"text\":\"Hi\"}]}";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json", json.getBytes());

            ResponseEntity<Map<String, Object>> response = realController.uploadFile(file);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).containsKey("fileId");
            assertThat(response.getBody()).containsEntry("status", "PENDING");
        }

        @Test
        @DisplayName("downloadFile возвращает 404 для несуществующего UUID fileId")
        void downloadNonExistentFileReturns404() {
            ResponseEntity<?> response = realController.downloadFile(UUID.randomUUID().toString());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("downloadFile возвращает 404 для невалидного fileId (не UUID)")
        void downloadInvalidFileIdReturns404() {
            ResponseEntity<?> response = realController.downloadFile("../../etc/passwd");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("downloadFile возвращает 200 для существующего файла")
        void downloadExistingFileReturns200() throws IOException {
            String fileId = UUID.randomUUID().toString();
            Files.writeString(exportDir.resolve(fileId + ".md"), "20250624 Hello");
            ResponseEntity<?> response = realController.downloadFile(fileId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("getFileStatus возвращает NOT_FOUND для несуществующего fileId")
        void getStatusForNonExistentReturnsNotFound() {
            ResponseEntity<Map<String, Object>> response = realController.getFileStatus("missing-id");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "NOT_FOUND");
            assertThat(response.getBody()).containsEntry("exists", false);
        }

        @Test
        @DisplayName("getFileStatus возвращает COMPLETED для существующего файла")
        void getStatusForExistingFileReturnsCompleted() throws IOException {
            String fileId = UUID.randomUUID().toString();
            Files.writeString(exportDir.resolve(fileId + ".md"), "content");
            ResponseEntity<Map<String, Object>> response = realController.getFileStatus(fileId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "COMPLETED");
            assertThat(response.getBody()).containsEntry("exists", true);
        }

        @Test
        @DisplayName("uploadFile: path-traversal в имени файла безопасен — UUID изолирует хранилище")
        void uploadPathTraversalFilenameIsSafe() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "../evil.json", "application/json",
                    "{\"messages\":[]}".getBytes());
            ResponseEntity<Map<String, Object>> response = realController.uploadFile(file);
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // -----------------------------------------------------------------------
    // Мок-тесты: поведение контроллера через Mockito
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Через мок FileStorageService")
    class WithMockedStorage {

        @Test
        @DisplayName("uploadFile не обращается к сервису если файл пустой")
        void uploadEmptyFile_neverCallsService() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json", new byte[0]);

            ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("uploadFile передаёт в processFileAsync именно тот fileId, что вернул uploadFile")
        void uploadFile_passesCorrectFileIdToProcessFileAsync() throws IOException {
            String expectedFileId = UUID.randomUUID().toString();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            when(storageService.uploadFile(any(MultipartFile.class))).thenReturn(expectedFileId);
            when(storageService.processFileAsync(anyString()))
                    .thenReturn(CompletableFuture.completedFuture(
                            ProcessingResult.success(expectedFileId)));

            controller.uploadFile(file);

            // ArgumentCaptor захватывает аргумент переданный в processFileAsync
            verify(storageService).processFileAsync(fileIdCaptor.capture());
            assertThat(fileIdCaptor.getValue()).isEqualTo(expectedFileId);
        }

        @Test
        @DisplayName("uploadFile возвращает 202 и не ждёт завершения обработки")
        void uploadFile_returns202Immediately() throws IOException {
            String fileId = UUID.randomUUID().toString();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());

            when(storageService.uploadFile(any(MultipartFile.class))).thenReturn(fileId);
            when(storageService.processFileAsync(anyString()))
                    .thenReturn(CompletableFuture.completedFuture(
                            ProcessingResult.success(fileId)));

            ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).containsEntry("status", "PENDING");
            // processFile (синхронный) не должен вызываться — только async
            verify(storageService, never()).processFile(anyString());
        }

        @Test
        @DisplayName("downloadFile возвращает 404 если exportFileExists = false, getExportFile не вызывается")
        void downloadFile_returns404WhenFileNotExists() throws IOException {
            String fileId = UUID.randomUUID().toString();
            when(storageService.exportFileExists(fileId)).thenReturn(false);

            ResponseEntity<?> response = controller.downloadFile(fileId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(storageService, never()).getExportFile(anyString());
        }

        @Test
        @DisplayName("downloadFile возвращает 500 если getExportFile бросает IOException")
        void downloadFile_returns500OnIOException() throws IOException {
            String fileId = UUID.randomUUID().toString();
            when(storageService.exportFileExists(fileId)).thenReturn(true);
            when(storageService.getExportFile(fileId))
                    .thenThrow(new IOException("Диск недоступен"));

            ResponseEntity<?> response = controller.downloadFile(fileId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("getFileStatus вызывает exportFileExists ровно один раз")
        void getFileStatus_callsExportFileExistsOnce() {
            String fileId = UUID.randomUUID().toString();
            when(storageService.exportFileExists(fileId)).thenReturn(true);

            controller.getFileStatus(fileId);

            verify(storageService, times(1)).exportFileExists(fileId);
        }

        @Test
        @DisplayName("uploadFile возвращает 500 если сервис бросает IOException при сохранении")
        void uploadFile_returns500OnIOException() throws IOException {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "result.json", "application/json",
                    "{\"messages\":[]}".getBytes());
            when(storageService.uploadFile(any(MultipartFile.class)))
                    .thenThrow(new IOException("Диск переполнен"));

            ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
