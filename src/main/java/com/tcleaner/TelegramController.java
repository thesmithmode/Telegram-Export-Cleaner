package com.tcleaner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST контроллер для конвертации Telegram экспорта.
 * 
 * <p>Предоставляет endpoints для загрузки и обработки файлов экспорта Telegram.</p>
 * 
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST /api/convert - загрузка файла result.json</li>
 *   <li>POST /api/convert/json - отправка JSON напрямую</li>
 *   <li>GET /api/health - проверка здоровья сервиса</li>
 * </ul>
 * 
 * @author Telegram Cleaner Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api")
public class TelegramController {

    private static final Logger log = LoggerFactory.getLogger(TelegramController.class);

    private final TelegramExporterInterface exporter;

    /**
     * Конструктор с внедрением зависимости.
     * 
     * @param exporter экземпляр экспортера для обработки файлов
     */
    public TelegramController(TelegramExporterInterface exporter) {
        this.exporter = exporter;
    }

    /**
     * Конвертирует загруженный файл result.json в текстовый формат.
     * 
     * <p>Принимает multipart/form-data запрос с файлом result.json.
     * Возвращает текстовый файл с обработанными сообщениями.</p>
     * 
     * @param file загруженный файл result.json
     * @return текстовый файл с результатом или сообщение об ошибке
     */
    @PostMapping("/convert")
    public ResponseEntity<?> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "keywords", required = false) String keywords,
            @RequestParam(value = "excludeKeywords", required = false) String excludeKeywords) {
        
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Файл пустой"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.endsWith(".json")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Ожидается JSON файл"));
        }

        try (TempDirectory tempDir = new TempDirectory()) {
            Path inputFile = tempDir.resolve("result.json");

            file.transferTo(inputFile.toFile());
            
            MessageFilter filter = buildFilter(startDate, endDate, keywords, excludeKeywords);
            List<String> processed = exporter.processFile(inputFile, filter);
            
            String result = String.join("\n", processed) + "\n";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=output.txt")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(result);

        } catch (IOException e) {
            log.error("Ошибка при обработке файла: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка обработки: " + e.getMessage()));
        }
    }

    /**
     * Конвертирует JSON содержимое напрямую в текстовый формат.
     * 
     * <p>Принимает JSON тело запроса и возвращает обработанный текст.</p>
     * 
     * @param jsonContent содержимое result.json в виде строки
     * @return текст с результатом или сообщение об ошибке
     */
    @PostMapping("/convert/json")
    public ResponseEntity<?> convertJson(
            @RequestBody String jsonContent,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "keywords", required = false) String keywords,
            @RequestParam(value = "excludeKeywords", required = false) String excludeKeywords) {
        
        if (jsonContent == null || jsonContent.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Пустое содержимое"));
        }

        try (TempDirectory tempDir = new TempDirectory()) {
            Path inputFile = tempDir.resolve("result.json");

            Files.writeString(inputFile, jsonContent);
            
            MessageFilter filter = buildFilter(startDate, endDate, keywords, excludeKeywords);
            List<String> processed = exporter.processFile(inputFile, filter);
            
            String result = String.join("\n", processed) + "\n";

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(result);

        } catch (IOException e) {
            log.error("Ошибка при обработке JSON: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка обработки: " + e.getMessage()));
        }
    }

    /**
     * Проверяет здоровье сервиса.
     * 
     * @return статус сервиса
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    private MessageFilter buildFilter(String startDate, String endDate, 
            String keywords, String excludeKeywords) {
        
        MessageFilter filter = new MessageFilter();
        
        if (startDate != null && !startDate.isBlank()) {
            filter.withStartDate(LocalDate.parse(startDate));
        }
        
        if (endDate != null && !endDate.isBlank()) {
            filter.withEndDate(LocalDate.parse(endDate));
        }
        
        if (keywords != null && !keywords.isBlank()) {
            for (String kw : keywords.split(",")) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty()) {
                    filter.withKeyword(trimmed);
                }
            }
        }
        
        if (excludeKeywords != null && !excludeKeywords.isBlank()) {
            for (String kw : excludeKeywords.split(",")) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty()) {
                    filter.withExcludeKeyword(trimmed);
                }
            }
        }
        
        // Возвращаем пустой фильтр если ничего не задано
        return filter;
    }

    /**
     * Утилита для автоматической очистки временной директории.
     */
    private static class TempDirectory implements AutoCloseable {
        private final Path path;

        TempDirectory() throws IOException {
            this.path = Files.createTempDirectory("telegram-cleaner");
        }

        Path resolve(String fileName) {
            return path.resolve(fileName);
        }

        @Override
        public void close() throws IOException {
            Files.walk(path)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
