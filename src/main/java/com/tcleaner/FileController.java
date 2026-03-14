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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST контроллер для работы с файлами через папки Import/Export.
 *
 * <p>Файл сохраняется под UUID-именем сразу при получении,
 * что исключает конфликты при параллельных запросах.</p>
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

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
     * <p>Файл сохраняется под UUID-именем, обработка запускается в фоне.
     * Отвечает сразу — 202 Accepted — не дожидаясь завершения обработки.
     * Клиент опрашивает GET /api/files/{fileId}/status для получения результата.</p>
     *
     * @param file загружаемый файл (result.json из Telegram)
     * @return 202 Accepted с fileId, либо 400/500 при ошибке
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        log.info("Получен файл: {}, размер: {} байт", file.getOriginalFilename(), file.getSize());

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Файл пустой"));
            }

            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".json")) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Ожидается файл с расширением .json"));
            }

            // Сохраняем файл синхронно — быстрое I/O, необходимо до запуска async
            String fileId = fileStorageService.uploadFile(file);

            // Обработка запускается в отдельном потоке — HTTP-поток свободен немедленно
            fileStorageService.processFileAsync(fileId);

            Map<String, Object> response = new HashMap<>();
            response.put("fileId", fileId);
            response.put("status", ProcessingStatus.PENDING.name());
            response.put("message", "Файл принят в обработку");

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);

        } catch (IOException e) {
            log.error("Ошибка ввода/вывода при загрузке файла: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Ошибка при сохранении файла");
            return ResponseEntity.internalServerError().body(error);
        } catch (Exception e) {
            log.error("Неожиданная ошибка при загрузке файла: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Внутренняя ошибка сервера");
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Скачивает обработанный файл из папки Export.
     *
     * @param fileId ID файла
     * @return файл для скачивания
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

        } catch (IllegalArgumentException e) {
            log.warn("Недопустимый fileId при скачивании: {}", fileId);
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            log.error("Ошибка чтения файла {}: {}", fileId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Возвращает статус обработки файла.
     *
     * <p>Сначала смотрим статус в Redis (быстро), потом на диск (запасной вариант).</p>
     *
     * @param fileId ID файла
     * @return статус файла
     */
    @GetMapping("/{fileId}/status")
    public ResponseEntity<Map<String, Object>> getFileStatus(@PathVariable String fileId) {
        // 1. Redis — быстрый ответ (есть PENDING / COMPLETED / FAILED)
        Optional<ProcessingStatus> redisStatus = statusService.getStatus(fileId);
        if (redisStatus.isPresent()) {
            Map<String, Object> response = new HashMap<>();
            response.put("fileId", fileId);
            response.put("status", redisStatus.get().name());
            response.put("exists", redisStatus.get() == ProcessingStatus.COMPLETED);
            return ResponseEntity.ok(response);
        }

        // 2. Диск — запасной вариант (например, Redis перезапустился)
        boolean exists;
        try {
            exists = fileStorageService.exportFileExists(fileId);
        } catch (IllegalArgumentException ex) {
            exists = false;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("fileId", fileId);
        response.put("exists", exists);
        response.put("status", exists ? ProcessingStatus.COMPLETED.name() : "NOT_FOUND");
        return ResponseEntity.ok(response);
    }
}
