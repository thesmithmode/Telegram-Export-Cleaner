package com.tcleaner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tcleaner.TelegramExporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для FileStorageService.
 * Проверяет работу с папками Import и Export.
 */
class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    private Path importDir;
    private Path exportDir;
    private FileStorageService storageService;

    @BeforeEach
    void setUp() throws IOException {
        importDir = tempDir.resolve("import");
        exportDir = tempDir.resolve("export");
        Files.createDirectories(importDir);
        Files.createDirectories(exportDir);

        StorageConfig config = new StorageConfig();
        config.setImportPath(importDir.toString());
        config.setExportPath(exportDir.toString());
        config.setExportTtlMinutes(10);

        TelegramExporter exporter = new TelegramExporter();
        storageService = new FileStorageService(config, exporter);
    }

    @Test
    void testUploadFile_Success() throws IOException {
        Path testFile = importDir.resolve("test.json");
        Files.writeString(testFile, "{\"messages\": []}");

        String fileId = storageService.uploadFile(testFile);

        assertNotNull(fileId);
        assertTrue(Files.exists(importDir.resolve(fileId + ".json")));
    }

    @Test
    void testUploadFile_CreatesDirectoryIfNotExists() throws IOException {
        Path newImportDir = tempDir.resolve("new_import");
        Path newExportDir = tempDir.resolve("new_export");

        StorageConfig config = new StorageConfig();
        config.setImportPath(newImportDir.toString());
        config.setExportPath(newExportDir.toString());
        config.setExportTtlMinutes(10);

        TelegramExporter exporter = new TelegramExporter();
        FileStorageService newService = new FileStorageService(config, exporter);

        Path testFile = newImportDir.resolve("test.json");
        Files.writeString(testFile, "{\"messages\": []}");

        String fileId = newService.uploadFile(testFile);

        assertNotNull(fileId);
        assertTrue(Files.exists(newImportDir.resolve(fileId + ".json")));
    }

    @Test
    void testProcessFile_MovesToExport() throws IOException {
        Path testFile = importDir.resolve("test.json");
        Files.writeString(testFile, "{\"messages\": [{\"text\": \"Hello\", \"date\": \"2024-01-01T10:00:00\"}]}");

        String fileId = storageService.uploadFile(testFile);

        ProcessingResult result = storageService.processFile(fileId);

        assertEquals(ProcessingStatus.COMPLETED, result.getStatus());
        assertTrue(Files.exists(exportDir.resolve(fileId + ".md")));
        assertFalse(Files.exists(importDir.resolve(fileId + ".json")));
    }

    @Test
    void testDeleteImportFile_AfterProcessing() throws IOException {
        Path testFile = importDir.resolve("test.json");
        Files.writeString(testFile, "{\"messages\": []}");

        String fileId = storageService.uploadFile(testFile);

        assertTrue(Files.exists(importDir.resolve(fileId + ".json")));

        storageService.processFile(fileId);

        assertFalse(Files.exists(importDir.resolve(fileId + ".json")));
    }

    @Test
    void testExportCleanup_DeletesOldFiles() throws IOException {
        String oldFileId = UUID.randomUUID().toString();
        Path oldFile = exportDir.resolve(oldFileId + ".md");
        Files.writeString(oldFile, "old content");

        // Устанавливаем время модификации на 15 минут назад
        Instant oldTime = Instant.now().minusSeconds(900);
        Files.setLastModifiedTime(oldFile,
            java.nio.file.attribute.FileTime.from(oldTime));

        String newFileId = UUID.randomUUID().toString();
        Path newFile = exportDir.resolve(newFileId + ".md");
        Files.writeString(newFile, "new content");

        storageService.cleanupExportDirectory();

        assertFalse(Files.exists(oldFile));
        assertTrue(Files.exists(newFile));
    }

    @Test
    void testExportCleanup_KeepsRecentFiles() throws IOException {
        String recentFileId = UUID.randomUUID().toString();
        Path recentFile = exportDir.resolve(recentFileId + ".md");
        Files.writeString(recentFile, "recent content");

        storageService.cleanupExportDirectory();

        assertTrue(Files.exists(recentFile));
    }

    @Test
    void testGetExportFile() throws IOException {
        String fileId = UUID.randomUUID().toString();
        Path exportFile = exportDir.resolve(fileId + ".md");
        Files.writeString(exportFile, "exported content");

        Path result = storageService.getExportFile(fileId);

        assertNotNull(result);
        assertTrue(Files.exists(result));
        assertEquals("exported content", Files.readString(result));
    }

    @Test
    void testGetExportFile_NotFound() {
        assertThrows(IOException.class, () -> storageService.getExportFile(UUID.randomUUID().toString()));
    }

    @Test
    void testGetExportFile_InvalidFileId_ThrowsIllegalArgument() {
        // path-traversal попытка
        assertThrows(IllegalArgumentException.class,
            () -> storageService.getExportFile("../../etc/passwd"));
    }

    @Test
    void testExportFileExists_InvalidFileId_ThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> storageService.exportFileExists("../evil"));
    }

    @Test
    void testProcessFile_InvalidFileId_ThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> storageService.processFile("not-a-uuid"));
    }

    @Test
    void testProcessFile_NullFileId_ThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> storageService.processFile(null));
    }
}
