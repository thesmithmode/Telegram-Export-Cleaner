package com.tcleaner;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.storage.FileStorageService;
import com.tcleaner.storage.StorageConfig;
import com.tcleaner.status.ProcessingStatusService;
import com.tcleaner.status.ProcessingResult;
import com.tcleaner.status.ProcessingStatus;
import com.tcleaner.core.TelegramExporterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для {@link FileStorageService}.
 *
 * <p>Покрывает:</p>
 * <ul>
 *   <li>загрузку файлов (uploadFile)</li>
 *   <li>обработку файлов (processFile): успех, ошибка, гарантии очистки</li>
 *   <li>очистку папки Export (cleanupExportDirectory)</li>
 *   <li>доступ к файлам (getExportFile, exportFileExists)</li>
 *   <li>валидацию fileId (path-traversal защита)</li>
 * </ul>
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

        ProcessingStatusService mockStatus = mock(ProcessingStatusService.class);
        storageService = new FileStorageService(config, new TelegramExporter(), mockStatus);
    }

    // ─── uploadFile ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("uploadFile()")
    class UploadFile {

        @Test
        @DisplayName("Возвращает UUID и создаёт файл в Import")
        void success() throws IOException {
            Path testFile = importDir.resolve("test.json");
            Files.writeString(testFile, "{\"messages\": []}");

            String fileId = storageService.uploadFile(testFile);

            assertNotNull(fileId);
            assertTrue(Files.exists(importDir.resolve(fileId + ".json")));
        }

        @Test
        @DisplayName("Автоматически создаёт директории, если их нет")
        void createsDirectoriesIfMissing() throws IOException {
            Path newImportDir = tempDir.resolve("new_import");
            Path newExportDir = tempDir.resolve("new_export");

            StorageConfig config = new StorageConfig();
            config.setImportPath(newImportDir.toString());
            config.setExportPath(newExportDir.toString());
            config.setExportTtlMinutes(10);

            FileStorageService newService = new FileStorageService(
                    config, new TelegramExporter(), mock(ProcessingStatusService.class));

            Path testFile = newImportDir.resolve("test.json");
            Files.writeString(testFile, "{\"messages\": []}");

            String fileId = newService.uploadFile(testFile);

            assertNotNull(fileId);
            assertTrue(Files.exists(newImportDir.resolve(fileId + ".json")));
        }
    }

    // ─── processFile ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("processFile()")
    class ProcessFile {

        @Test
        @DisplayName("Успех: создаёт .md в Export, удаляет .json из Import")
        void movesToExport() throws IOException {
            Path testFile = importDir.resolve("test.json");
            Files.writeString(testFile,
                    "{\"messages\": [{\"text\": \"Hello\", \"date\": \"2024-01-01T10:00:00\", \"type\": \"message\"}]}");

            String fileId = storageService.uploadFile(testFile);
            ProcessingResult result = storageService.processFile(fileId);

            assertEquals(ProcessingStatus.COMPLETED, result.getStatus());
            assertTrue(Files.exists(exportDir.resolve(fileId + ".md")));
            assertFalse(Files.exists(importDir.resolve(fileId + ".json")));
        }

        @Test
        @DisplayName("Входной файл удаляется из Import даже при успешной обработке")
        void deletesImportFileAfterSuccess() throws IOException {
            Path testFile = importDir.resolve("test.json");
            Files.writeString(testFile, "{\"messages\": []}");

            String fileId = storageService.uploadFile(testFile);
            assertTrue(Files.exists(importDir.resolve(fileId + ".json")));

            storageService.processFile(fileId);

            assertFalse(Files.exists(importDir.resolve(fileId + ".json")));
        }

        @Test
        @DisplayName("При ошибке обработки: входной .json удаляется, битый .md не остаётся")
        void onErrorDeletesBothInputAndPartialExport() throws IOException {
            // Создаём мок-экспортер, который бросает исключение при streaming-обработке
            TelegramExporter failingExporter = mock(TelegramExporter.class);
            doAnswer(inv -> {
                throw new TelegramExporterException("INVALID_JSON", "Битый JSON");
            }).when(failingExporter).processFileStreaming(any(Path.class), any(), any(Writer.class));

            StorageConfig config = new StorageConfig();
            config.setImportPath(importDir.toString());
            config.setExportPath(exportDir.toString());
            config.setExportTtlMinutes(10);

            FileStorageService service = new FileStorageService(
                    config, failingExporter, mock(ProcessingStatusService.class));

            // Вручную создаём файл в Import
            String fileId = UUID.randomUUID().toString();
            Path importFile = importDir.resolve(fileId + ".json");
            Files.writeString(importFile, "не-валидный-json");

            ProcessingResult result = service.processFile(fileId);

            assertEquals(ProcessingStatus.FAILED, result.getStatus());
            // Входной файл должен быть удалён (finally-блок)
            assertFalse(Files.exists(importFile),
                    "Входной .json должен быть удалён из Import даже при ошибке");
            // Частичный файл экспорта должен быть удалён (catch-блок)
            assertFalse(Files.exists(exportDir.resolve(fileId + ".md")),
                    "Частичный .md не должен оставаться в Export после ошибки");
        }

        @Test
        @DisplayName("Несуществующий файл в Import → FAILED, без NPE")
        void missingImportFile_returnsFailed() {
            String fileId = UUID.randomUUID().toString();
            ProcessingResult result = storageService.processFile(fileId);
            assertEquals(ProcessingStatus.FAILED, result.getStatus());
        }

        @Test
        @DisplayName("Невалидный fileId → IllegalArgumentException")
        void invalidFileId_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> storageService.processFile("not-a-uuid"));
        }

        @Test
        @DisplayName("null fileId → IllegalArgumentException")
        void nullFileId_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> storageService.processFile(null));
        }
    }

    // ─── cleanupExportDirectory ───────────────────────────────────────────────

    @Nested
    @DisplayName("cleanupExportDirectory()")
    class CleanupExport {

        @Test
        @DisplayName("Удаляет файлы старше TTL")
        void deletesOldFiles() throws IOException {
            String oldFileId = UUID.randomUUID().toString();
            Path oldFile = exportDir.resolve(oldFileId + ".md");
            Files.writeString(oldFile, "old content");
            Files.setLastModifiedTime(oldFile,
                    java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(900)));

            String newFileId = UUID.randomUUID().toString();
            Path newFile = exportDir.resolve(newFileId + ".md");
            Files.writeString(newFile, "new content");

            storageService.cleanupExportDirectory();

            assertFalse(Files.exists(oldFile), "Старый файл должен быть удалён");
            assertTrue(Files.exists(newFile), "Новый файл должен остаться");
        }

        @Test
        @DisplayName("Не удаляет свежие файлы")
        void keepsRecentFiles() throws IOException {
            String recentFileId = UUID.randomUUID().toString();
            Path recentFile = exportDir.resolve(recentFileId + ".md");
            Files.writeString(recentFile, "recent content");

            storageService.cleanupExportDirectory();

            assertTrue(Files.exists(recentFile));
        }
    }

    // ─── getExportFile / exportFileExists ────────────────────────────────────

    @Nested
    @DisplayName("getExportFile() / exportFileExists()")
    class ExportAccess {

        @Test
        @DisplayName("getExportFile возвращает корректный путь для существующего файла")
        void getExportFile_success() throws IOException {
            String fileId = UUID.randomUUID().toString();
            Path exportFile = exportDir.resolve(fileId + ".md");
            Files.writeString(exportFile, "exported content");

            Path result = storageService.getExportFile(fileId);

            assertNotNull(result);
            assertTrue(Files.exists(result));
            assertEquals("exported content", Files.readString(result));
        }

        @Test
        @DisplayName("getExportFile бросает IOException для несуществующего файла")
        void getExportFile_notFound() {
            assertThrows(IOException.class,
                    () -> storageService.getExportFile(UUID.randomUUID().toString()));
        }

        @Test
        @DisplayName("getExportFile бросает IllegalArgumentException при path-traversal попытке")
        void getExportFile_pathTraversal_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> storageService.getExportFile("../../etc/passwd"));
        }

        @Test
        @DisplayName("exportFileExists бросает IllegalArgumentException при невалидном fileId")
        void exportFileExists_invalidId_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> storageService.exportFileExists("../evil"));
        }
    }
}
