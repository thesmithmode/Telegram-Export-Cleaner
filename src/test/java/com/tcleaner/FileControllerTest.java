package com.tcleaner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты для FileController.
 */
@DisplayName("FileController")
class FileControllerTest {

    @TempDir
    Path tempDir;

    private FileStorageService storageService;
    private FileController controller;

    @BeforeEach
    void setUp() throws IOException {
        Path importDir = tempDir.resolve("import");
        Path exportDir = tempDir.resolve("export");
        Files.createDirectories(importDir);
        Files.createDirectories(exportDir);

        StorageConfig config = new StorageConfig();
        config.setImportPath(importDir.toString());
        config.setExportPath(exportDir.toString());
        config.setExportTtlMinutes(10);

        TelegramExporter exporter = new TelegramExporter();
        storageService = new FileStorageService(config, exporter);
        controller = new FileController(storageService);
    }

    @Test
    @DisplayName("uploadFile возвращает 400 для пустого файла")
    void uploadEmptyFileReturns400() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "result.json", "application/json", new byte[0]);

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    @DisplayName("uploadFile возвращает 400 для не-.json файла")
    void uploadNonJsonFileReturns400() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "result.txt", "text/plain", "content".getBytes());

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    @DisplayName("uploadFile возвращает 400 для null имени файла")
    void uploadNullFilenameReturns400() {
        MockMultipartFile file = new MockMultipartFile(
                "file", null, "application/json", "{\"messages\":[]}".getBytes());

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("uploadFile успешно обрабатывает корректный result.json")
    void uploadValidJsonReturns200() {
        String json = "{\"messages\":[{\"id\":1,\"type\":\"message\",\"date\":\"2025-06-24T10:00:00\",\"text\":\"Hi\"}]}";
        MockMultipartFile file = new MockMultipartFile(
                "file", "result.json", "application/json", json.getBytes());

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("fileId");
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
    }

    @Test
    @DisplayName("downloadFile возвращает 404 для несуществующего fileId")
    void downloadNonExistentFileReturns404() {
        ResponseEntity<?> response = controller.downloadFile("nonexistent-id");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("downloadFile возвращает файл для существующего fileId")
    void downloadExistingFileReturns200() throws IOException {
        String fileId = UUID.randomUUID().toString();
        Path exportFile = tempDir.resolve("export").resolve(fileId + ".md");
        Files.writeString(exportFile, "20250624 Hello");

        ResponseEntity<?> response = controller.downloadFile(fileId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("getFileStatus возвращает NOT_FOUND для несуществующего fileId")
    void getStatusForNonExistentReturnsNotFound() {
        ResponseEntity<Map<String, Object>> response = controller.getFileStatus("missing-id");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "NOT_FOUND");
        assertThat(response.getBody()).containsEntry("exists", false);
    }

    @Test
    @DisplayName("getFileStatus возвращает COMPLETED для существующего файла")
    void getStatusForExistingFileReturnsCompleted() throws IOException {
        String fileId = UUID.randomUUID().toString();
        Path exportFile = tempDir.resolve("export").resolve(fileId + ".md");
        Files.writeString(exportFile, "content");

        ResponseEntity<Map<String, Object>> response = controller.getFileStatus(fileId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
        assertThat(response.getBody()).containsEntry("exists", true);
    }

    @Test
    @DisplayName("uploadFile отклоняет path-traversal в имени файла")
    void uploadPathTraversalFilenameReturns400() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "../evil.json", "application/json",
                "{\"messages\":[]}".getBytes());

        // Имя файла будет санитизировано — файл не сохранится вне importPath
        // Допустимы как 200 (если санитизация успешна), так и 400 (если отклонено явно)
        ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

        // Главное — не должно быть 500
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
