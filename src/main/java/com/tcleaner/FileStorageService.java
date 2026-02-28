package com.tcleaner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Сервис для работы с файлами в папках Import и Export.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final String JSON_EXTENSION = ".json";
    private static final String MD_EXTENSION = ".md";
    private static final ReentrantLock cleanupLock = new ReentrantLock();

    private final StorageConfig config;
    private final Path importPath;
    private final Path exportPath;
    private final TelegramExporter exporter;

    /**
     * Конструктор.
     *
     * @param config   конфигурация хранилища
     * @param exporter обработчик Telegram файлов
     */
    public FileStorageService(StorageConfig config, TelegramExporter exporter) {
        this.config = config;
        this.importPath = Paths.get(config.getImportPath());
        this.exportPath = Paths.get(config.getExportPath());
        this.exporter = exporter;

        initializeDirectories();
    }

    /**
     * Инициализирует директории если они не существуют.
     */
    private void initializeDirectories() {
        try {
            Files.createDirectories(importPath);
            Files.createDirectories(exportPath);
            log.info("Директории инициализированы: import={}, export={}", importPath, exportPath);
        } catch (IOException e) {
            log.error("Ошибка при создании директорий: {}", e.getMessage());
            throw new RuntimeException("Не удалось создать директории", e);
        }
    }

    /**
     * Загружает файл в папку Import.
     *
     * @param sourcePath путь к исходному файлу
     * @return ID файла
     * @throws IOException при ошибке ввода-вывода
     */
    public String uploadFile(Path sourcePath) throws IOException {
        String fileId = UUID.randomUUID().toString();
        Path targetPath = importPath.resolve(fileId + JSON_EXTENSION);

        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Файл загружен: {} -> {}", sourcePath, targetPath);

        return fileId;
    }

    /**
     * Обрабатывает файл из Import и перемещает результат в Export.
     *
     * <p>Метод выполняется синхронно в вызывающем потоке. Для асинхронной
     * обработки оберните вызов в {@link java.util.concurrent.CompletableFuture}.</p>
     *
     * @param fileId ID файла
     * @return результат обработки
     */
    public ProcessingResult processFile(String fileId) {
        Path importFile = importPath.resolve(fileId + JSON_EXTENSION);
        Path exportFile = exportPath.resolve(fileId + MD_EXTENSION);

        try {
            if (!Files.exists(importFile)) {
                log.error("Файл не найден: {}", importFile);
                return ProcessingResult.error(fileId, "Файл не найден в папке Import");
            }

            List<String> lines = exporter.processFile(importFile);
            String result = String.join("\n", lines);

            Files.writeString(exportFile, result);
            Files.delete(importFile);

            log.info("Файл обработан: {} -> {}", importFile, exportFile);
            return ProcessingResult.success(fileId);

        } catch (Exception e) {
            log.error("Ошибка при обработке файла {}: {}", fileId, e.getMessage());
            return ProcessingResult.error(fileId, e.getMessage());
        }
    }

    /**
     * Очищает папку Export от старых файлов.
     */
    public void cleanupExportDirectory() {
        if (!cleanupLock.tryLock()) {
            log.info("Очистка уже выполняется, пропуск");
            return;
        }
        try {
            cleanupExportDirectoryInternal();
        } finally {
            cleanupLock.unlock();
        }
    }

    /**
     * Внутренний метод очистки.
     */
    private void cleanupExportDirectoryInternal() {
        Instant threshold = Instant.now().minus(config.getExportTtlMinutes(), ChronoUnit.MINUTES);

        try (Stream<Path> files = Files.list(exportPath)) {
            List<Path> filesToDelete = files
                .filter(path -> path.toString().endsWith(MD_EXTENSION))
                .filter(path -> {
                    try {
                        Instant lastModified = Files.getLastModifiedTime(path).toInstant();
                        return lastModified.isBefore(threshold);
                    } catch (IOException e) {
                        log.warn("Не удалось получить время файла {}: {}", path, e.getMessage());
                        return false;
                    }
                })
                .toList();

            for (Path file : filesToDelete) {
                try {
                    Files.delete(file);
                    log.info("Удалён старый файл: {}", file);
                } catch (IOException e) {
                    log.warn("Не удалось удалить файл {}: {}", file, e.getMessage());
                }
            }

            log.info("Очистка завершена: удалено {} файлов", filesToDelete.size());

        } catch (IOException e) {
            log.error("Ошибка при очистке папки Export: {}", e.getMessage());
        }
    }

    /**
     * Получает файл из папки Export.
     *
     * @param fileId ID файла
     * @return путь к файлу
     * @throws IOException если файл не найден
     */
    public Path getExportFile(String fileId) throws IOException {
        Path exportFile = exportPath.resolve(fileId + MD_EXTENSION);

        if (!Files.exists(exportFile)) {
            throw new IOException("Файл не найден: " + fileId);
        }

        return exportFile;
    }

    /**
     * Проверяет существование файла в Export.
     *
     * @param fileId ID файла
     * @return true если файл существует
     */
    public boolean exportFileExists(String fileId) {
        return Files.exists(exportPath.resolve(fileId + MD_EXTENSION));
    }

    public Path getImportPath() {
        return importPath;
    }

    public Path getExportPath() {
        return exportPath;
    }
}
