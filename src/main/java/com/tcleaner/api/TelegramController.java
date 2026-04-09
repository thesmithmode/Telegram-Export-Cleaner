package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.MessageFilterFactory;
import com.tcleaner.core.TelegramExporterInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * REST контроллер для потоковой конвертации Telegram экспорта.
 * 
 * <p>Оптимизирован для обработки огромных файлов (250к+ сообщений) с потреблением памяти O(1).</p>
 */
@RestController
@RequestMapping("/api")
public class TelegramController {

    private static final Logger log = LoggerFactory.getLogger(TelegramController.class);
    private final TelegramExporterInterface exporter;

    public TelegramController(TelegramExporterInterface exporter) {
        this.exporter = exporter;
    }

    /**
     * Конвертирует загруженный файл {@code result.json} в текстовый формат.
     * Использует StreamingResponseBody для записи результата напрямую в сокет.
     */
    @PostMapping("/convert")
    public ResponseEntity<?> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "keywords", required = false) String keywords,
            @RequestParam(value = "excludeKeywords", required = false) String excludeKeywords) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой"));
        }

        try {
            MessageFilter filter = MessageFilterFactory.build(startDate, endDate, keywords, excludeKeywords);
            
            // Создаем временную директорию (она будет удалена после завершения стриминга)
            Path tempDir = Files.createTempDirectory("telegram-cleaner-stream");
            Path inputFile = tempDir.resolve("result.json");
            
            // Сохраняем входящий поток на диск, чтобы не держать его в Heap
            file.transferTo(inputFile.toFile());
            log.info("Временный файл создан: {}", inputFile);

            StreamingResponseBody responseBody = outputStream -> {
                log.info("Начало стриминга ответа для файла {}", inputFile.getFileName());
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                    exporter.processFileStreaming(inputFile, filter, writer);
                    writer.flush();
                    log.info("Стриминг завершен успешно");
                } catch (Exception e) {
                    log.error("Ошибка во время стриминга ответа: ", e);
                } finally {
                    // Критично: удаляем временные файлы только после того, как StreamingResponseBody закончил работу
                    cleanupTempDir(tempDir);
                }
            };

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=output.txt")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(responseBody);

        } catch (Exception ex) {
            log.error("Ошибка при подготовке конвертации: ", ex);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to start processing", "details", ex.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    private void cleanupTempDir(Path tempDir) {
        try {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(pp -> {
                        try { Files.deleteIfExists(pp); } catch (IOException ignored) {}
                    });
            log.debug("Временная директория удалена: {}", tempDir);
        } catch (IOException e) {
            log.warn("Не удалось очистить временную директорию {}: {}", tempDir, e.getMessage());
        }
    }
}
