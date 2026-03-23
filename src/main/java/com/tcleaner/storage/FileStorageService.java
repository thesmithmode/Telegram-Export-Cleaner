package com.tcleaner.storage;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.status.ProcessingResult;
import com.tcleaner.status.ProcessingStatus;
import com.tcleaner.status.ProcessingStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Сервис для работы с файлами в папках Import и Export.
 *
 * <p>Жизненный цикл файла:</p>
 * <ol>
 *   <li>Загрузка → {@code Import/<uuid>.json}</li>
 *   <li>Обработка → {@code Export/<uuid>.md}</li>
 *   <li>Входной файл удаляется из Import <strong>в любом случае</strong>
 *       (успех или ошибка) через finally-блок.</li>
 *   <li>При ошибке частично записанный {@code .md} в Export немедленно
 *       удаляется, чтобы клиент не смог скачать повреждённый файл.</li>
 * </ol>
 *
 * <p>Планировщик {@link StorageCleanupScheduler} периодически удаляет
 * устаревшие файлы из папки Export по TTL, заданному в {@link StorageConfig}.</p>
 */
@Service
public class FileStorageService implements FileStorageServiceInterface {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final String JSON_EXTENSION = ".json";
    private static final String MD_EXTENSION = ".md";

    /**
     * UUID v4 формат: 8-4-4-4-12 hex-символов в нижнем регистре.
     * Защищает от path-traversal атак (например {@code fileId = "../../etc/passwd"}).
     */
    private static final Pattern VALID_FILE_ID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** Нестатический лок — каждый экземпляр сервиса независим (корректно для тестов). */
    private final ReentrantLock cleanupLock = new ReentrantLock();

    private final StorageConfig config;
    private final Path importPath;
    private final Path exportPath;
    private final TelegramExporter exporter;
    private final ProcessingStatusService statusService;

    /**
     * Конструктор с внедрением зависимостей.
     *
     * @param config        конфигурация хранилища (пути, TTL)
     * @param exporter      обработчик Telegram JSON-файлов
     * @param statusService сервис хранения статусов обработки в Redis
     */
    public FileStorageService(StorageConfig config, TelegramExporter exporter,
            ProcessingStatusService statusService) {
        this.config = config;
        this.importPath = Paths.get(config.getImportPath());
        this.exportPath = Paths.get(config.getExportPath());
        this.exporter = exporter;
        this.statusService = statusService;

        initializeDirectories();
    }

    /**
     * Инициализирует папки Import и Export при старте, если они ещё не существуют.
     *
     * @throws RuntimeException если создать директории не удалось
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
     * Копирует файл с диска в папку Import, присваивая ему UUID-имя.
     *
     * <p>Используется в CLI-режиме и интеграционных тестах.</p>
     *
     * @param sourcePath путь к исходному файлу
     * @return сгенерированный ID файла (UUID v4)
     * @throws IOException при ошибке копирования
     */
    public String uploadFile(Path sourcePath) throws IOException {
        String fileId = UUID.randomUUID().toString();
        Path targetPath = importPath.resolve(fileId + JSON_EXTENSION);

        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Файл загружен: {} -> {}", sourcePath, targetPath);

        return fileId;
    }

    /**
     * Сохраняет multipart-файл в папку Import, присваивая ему UUID-имя.
     *
     * <p>Используется в {@link FileController}: UUID генерируется здесь,
     * чтобы контроллер не знал о деталях хранения.</p>
     *
     * @param file загружаемый мультипарт-файл
     * @return сгенерированный ID файла (UUID v4)
     * @throws IOException при ошибке записи на диск
     */
    public String uploadFile(MultipartFile file) throws IOException {
        String fileId = UUID.randomUUID().toString();
        Path targetPath = importPath.resolve(fileId + JSON_EXTENSION);
        file.transferTo(targetPath.toFile());
        log.info("Файл загружен: {} -> {}", file.getOriginalFilename(), targetPath);
        return fileId;
    }

    /**
     * Обрабатывает файл из папки Import и записывает результат в папку Export.
     *
     * <p><strong>Гарантии надёжности:</strong></p>
     * <ul>
     *   <li>Входной {@code .json} файл удаляется из Import <strong>всегда</strong>
     *       (finally-блок) — утечки диска в Import невозможны.</li>
     *   <li>При любой ошибке (невалидный JSON, нехватка места и т.д.) частично
     *       записанный {@code .md} файл немедленно удаляется из Export, чтобы
     *       {@link #exportFileExists} не возвращал {@code true} для битого файла.</li>
     * </ul>
     *
     * <p>Метод выполняется синхронно в вызывающем потоке.</p>
     *
     * @param fileId ID файла (UUID v4)
     * @return результат обработки: {@link ProcessingStatus#COMPLETED} при успехе
     *         или {@link ProcessingStatus#FAILED} при ошибке
     * @throws IllegalArgumentException если {@code fileId} не соответствует формату UUID v4
     */
    public ProcessingResult processFile(String fileId) {
        validateFileId(fileId);
        Path importFile = importPath.resolve(fileId + JSON_EXTENSION);
        Path exportFile = exportPath.resolve(fileId + MD_EXTENSION);

        statusService.setStatus(fileId, ProcessingStatus.PENDING);
        try {
            if (!Files.exists(importFile)) {
                log.error("Файл не найден: {}", importFile);
                statusService.setStatus(fileId, ProcessingStatus.FAILED);
                return ProcessingResult.error(fileId, "Файл не найден в папке Import");
            }

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(Files.newOutputStream(exportFile), StandardCharsets.UTF_8))) {
                exporter.processFileStreaming(importFile, null, writer);
            }

            statusService.setStatus(fileId, ProcessingStatus.COMPLETED);
            log.info("Файл обработан: {} -> {}", importFile, exportFile);
            return ProcessingResult.success(fileId);

        } catch (Exception ex) {
            log.error("Ошибка при обработке файла {}: {}", fileId, ex.getMessage());
            statusService.setStatus(fileId, ProcessingStatus.FAILED);
            // Удаляем частично записанный файл экспорта, чтобы клиент не мог
            // скачать битый результат (exportFileExists вернёт false).
            try {
                Files.deleteIfExists(exportFile);
            } catch (IOException deleteEx) {
                log.warn("Не удалось удалить частичный файл экспорта {}: {}",
                        exportFile, deleteEx.getMessage());
            }
            return ProcessingResult.error(fileId, ex.getMessage());
        } finally {
            // Входной файл удаляется всегда — он больше не нужен независимо от исхода.
            try {
                Files.deleteIfExists(importFile);
            } catch (IOException ignore) {
                log.warn("Не удалось удалить входной файл {}", importFile, ignore);
            }
        }
    }

    /**
     * Запускает очистку папки Export от устаревших файлов.
     *
     * <p>Если очистка уже выполняется в другом потоке, вызов пропускается
     * (non-blocking {@link ReentrantLock#tryLock()}).</p>
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
     * Внутренняя логика очистки: удаляет {@code .md} файлы старше TTL.
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
     * Возвращает путь к готовому файлу из папки Export.
     *
     * @param fileId ID файла (UUID v4)
     * @return путь к существующему {@code .md} файлу
     * @throws IOException              если файл не найден в Export
     * @throws IllegalArgumentException если {@code fileId} не соответствует формату UUID v4
     */
    public Path getExportFile(String fileId) throws IOException {
        validateFileId(fileId);
        Path exportFile = exportPath.resolve(fileId + MD_EXTENSION);

        if (!Files.exists(exportFile)) {
            throw new IOException("Файл не найден: " + fileId);
        }

        return exportFile;
    }

    /**
     * Проверяет, существует ли готовый файл в папке Export.
     *
     * @param fileId ID файла (UUID v4)
     * @return {@code true} если файл существует и не был удалён при ошибке обработки
     * @throws IllegalArgumentException если {@code fileId} не соответствует формату UUID v4
     */
    public boolean exportFileExists(String fileId) {
        validateFileId(fileId);
        return Files.exists(exportPath.resolve(fileId + MD_EXTENSION));
    }

    /**
     * Проверяет, что {@code fileId} соответствует формату UUID v4 в нижнем регистре.
     *
     * <p>Защищает от path-traversal атак: любой {@code fileId} вида
     * {@code "../../etc/passwd"} будет отклонён до обращения к файловой системе.</p>
     *
     * @param fileId проверяемый идентификатор
     * @throws IllegalArgumentException если формат неверен или значение {@code null}
     */
    private void validateFileId(String fileId) {
        if (fileId == null || !VALID_FILE_ID.matcher(fileId).matches()) {
            throw new IllegalArgumentException("Недопустимый fileId: " + fileId);
        }
    }

    /**
     * Асинхронно обрабатывает файл из папки Import.
     *
     * <p>Spring AOP перехватывает вызов и выполняет тело метода в отдельном потоке
     * из пула {@code ThreadPoolTaskExecutor}. Вызывающий HTTP-поток освобождается
     * немедленно и может принять следующий запрос.</p>
     *
     * <p><strong>Почему {@code CompletableFuture.completedFuture}?</strong>
     * Это корректный паттерн для Spring {@code @Async}: синхронный
     * {@link #processFile(String)} выполняется в пуловом потоке и блокирует его,
     * а {@code completedFuture} лишь оборачивает уже готовый результат.
     * Вызывающая сторона получает Future мгновенно и может проверить статус
     * через {@link ProcessingStatusService}.</p>
     *
     * @param fileId ID файла (UUID v4)
     * @return {@link CompletableFuture} с результатом обработки
     */
    @Async
    public CompletableFuture<ProcessingResult> processFileAsync(String fileId) {
        log.info("Асинхронная обработка файла: {}", fileId);
        return CompletableFuture.completedFuture(processFile(fileId));
    }
}
