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
import java.util.ArrayList;
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

    private final TelegramExporter exporter;

    /**
     * Конструктор с внедрением зависимости.
     * 
     * @param exporter экземпляр экспортера для обработки файлов
     */
    public TelegramController(TelegramExporter exporter) {
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

        if (!file.getOriginalFilename().endsWith(".json")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Ожидается JSON файл"));
        }

        try (TempDirectory tempDir = new TempDirectory()) {
            Path inputFile = tempDir.resolve("result.json");

            file.transferTo(inputFile.toFile());
            
            List<String> processed = exporter.processFile(inputFile);
            processed = applyFilters(processed, startDate, endDate, keywords, excludeKeywords);
            
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
            
            List<String> processed = exporter.processFile(inputFile);
            processed = applyFilters(processed, startDate, endDate, keywords, excludeKeywords);
            
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

    private List<String> applyFilters(List<String> messages, String startDate, 
            String endDate, String keywords, String excludeKeywords) {
        
        if (startDate == null && endDate == null && 
            keywords == null && excludeKeywords == null) {
            return messages;
        }

        return messages.stream()
                .filter(line -> filterLine(line, startDate, endDate, keywords, excludeKeywords))
                .toList();
    }

    private boolean filterLine(String line, String startDate, String endDate, 
            String keywords, String excludeKeywords) {
        
        if (line == null || line.isBlank()) {
            return false;
        }

        if (keywords != null && !keywords.isBlank()) {
            List<String> keywordList = parseKeywords(keywords);
            boolean hasKeyword = keywordList.stream()
                    .anyMatch(kw -> line.toLowerCase().contains(kw.toLowerCase()));
            if (!hasKeyword) {
                return false;
            }
        }

        if (excludeKeywords != null && !excludeKeywords.isBlank()) {
            List<String> excludeList = parseKeywords(excludeKeywords);
            boolean hasExclude = excludeList.stream()
                    .anyMatch(kw -> line.toLowerCase().contains(kw.toLowerCase()));
            if (hasExclude) {
                return false;
            }
        }

        if (startDate != null || endDate != null) {
            String datePart = line.length() >= 8 ? line.substring(0, 8) : "";
            try {
                int year = Integer.parseInt(datePart.substring(0, 4));
                int month = Integer.parseInt(datePart.substring(4, 6));
                int day = Integer.parseInt(datePart.substring(6, 8));
                LocalDate msgDate = LocalDate.of(year, month, day);

                if (startDate != null) {
                    LocalDate start = LocalDate.parse(startDate);
                    if (msgDate.isBefore(start)) {
                        return false;
                    }
                }

                if (endDate != null) {
                    LocalDate end = LocalDate.parse(endDate);
                    if (msgDate.isAfter(end)) {
                        return false;
                    }
                }
            } catch (Exception e) {
                return false;
            }
        }

        return true;
    }

    private List<String> parseKeywords(String value) {
        List<String> result = new ArrayList<>();
        if (value != null && !value.isBlank()) {
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
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
