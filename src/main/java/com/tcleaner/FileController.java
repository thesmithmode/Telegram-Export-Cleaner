package com.tcleaner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST контроллер для работы с файлами через папки Import/Export.
 *
 * <p>Файл сохраняется под UUID-именем сразу при получении,
 * что исключает конфликты при параллельных запросах.</p>
 *
 * <p>Порядок проверок в {@link #uploadFile}:
 * <ol>
 *   <li>Валидация файла (400) — не засоряет rate limit таймер</li>
 *   <li>Rate limit (429) — проверяется только для корректных файлов</li>
 *   <li>Сохранение и асинхронная обработка</li>
 * </ol>
 * </p>
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private static final long RATE_LIMIT_MS = 15_000L;
    private final AtomicLong lastUploadTime = new AtomicLong(0);

    private final FileStorageService fileStorageService;
    private final ProcessingStatusService statusService;

    /**
     * Конструктор.
     *
     * @param fileStorageService сервис для работы с файлами
     * @param statusService      сервис статусов из Redis
     */
    public FileController(FileStorageService fileStorageService,
            ProcessingStatusService statusService) {
        this.fileStorageService = fileStorageService;
        this.statusService = statusService;
    }

    /**
     * Загружает файл в папку Import и запускает асинхронную обработку.
     *
     * <p>Порядок проверок: сначала валидация файла (400), потом rate limit (429).
     * Невалидные запросы не расходуют квоту rate limit.</p>
     *
     * @param file загружаемый файл (result.json из Telegram)
     * @return 202 Accepted с fileId, либо 400/429/500 при ошибке
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file) {
        log.info("Получен файл: {}, размер: {} байт", file.getOriginalFilename(), file.getSize());

        // 1. Валидация — до rate limit, чтобы невалидные запросы не расходовали квоту
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Файл пустой"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".json")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Ожидается файл с расширением .json"));
        }

        // 2. Rate limit — только для корректных файлов
        long last = lastUploadTime.get();
        long now = System.currentTimeMillis();
        if (now - last < RATE_LIMIT_MS) {
            long waitSec = (RATE_LIMIT_MS - (now - last)) / 1000 + 1;
            log.warn("Rate limit: запрос отклонён, следующий через {} сек", waitSec);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Слишком частые запросы. Подождите " + waitSec + " сек."));
        }
        if (!lastUploadTime.compareAndSet(last, now)) {
            // Другой поток успел раньше — тоже rate limit
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Слишком частые запросы."));
        }

        // 3. Сохранение и запуск обработки
        try {
            String fileId = fileStorageService.uploadFile(file);
            fileStorageService.processFileAsync(fileId);

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "fileId", fileId,
                    "status", ProcessingStatus.PENDING.name(),
                    "message", "Файл принят в обработку"
            ));

        } catch (IOException ex) {
            log.error("Ошибка ввода/вывода при загрузке файла: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка при сохранении файла"));
        } catch (Exception ex) {
            log.error("Неожиданная ошибка при загрузке файла: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Внутренняя ошибка сервера"));
        }
    }

    /**
     * Скачивает обработанный файл из папки Export.
     *
     * @param fileId ID файла (UUID v4)
     * @return файл для скачивания, 404 если не найден
     */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId) {
        log.info("Запрос на скачивание файла: {}", fileId);

        try {
            if (!fileStorageService.exportFileExists(fileId)) {
                return ResponseEntity.notFound().build();
            }

            Path filePath = fileStorageService.getExportFile(fileId);
            Resource resource = new UrlResource(filePath.toUri());

            ContentDisposition contentDisposition = ContentDisposition.attachment()
                    .filename(fileId + ".md", StandardCharsets.UTF_8)
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                    .body(resource);

        } catch (IllegalArgumentException ex) {
            log.warn("Недопустимый fileId при скачивании: {}", fileId);
            return ResponseEntity.notFound().build();
        } catch (IOException ex) {
            log.error("Ошибка чтения файла {}: {}", fileId, ex.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Возвращает статус обработки файла.
     *
     * <p>Сначала смотрим статус в Redis (быстро), потом на диск (запасной вариант).</p>
     *
     * @param fileId ID файла (UUID v4)
     * @return статус файла
     */
    @GetMapping("/{fileId}/status")
    public ResponseEntity<Map<String, Object>> getFileStatus(@PathVariable String fileId) {
        // 1. Redis — быстрый ответ
        Optional<ProcessingStatus> redisStatus = statusService.getStatus(fileId);
        if (redisStatus.isPresent()) {
            ProcessingStatus status = redisStatus.get();
            return ResponseEntity.ok(Map.of(
                    "fileId", fileId,
                    "status", status.name(),
                    "exists", status == ProcessingStatus.COMPLETED
            ));
        }

        // 2. Диск — запасной вариант (например, Redis перезапустился)
        boolean exists;
        try {
            exists = fileStorageService.exportFileExists(fileId);
        } catch (IllegalArgumentException ex) {
            exists = false;
        }

        return ResponseEntity.ok(Map.of(
                "fileId", fileId,
                "exists", exists,
                "status", exists ? ProcessingStatus.COMPLETED.name() : "NOT_FOUND"
        ));
    }
}
