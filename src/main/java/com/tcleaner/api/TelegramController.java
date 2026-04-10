package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * REST API контроллер для конвертации Telegram JSON export файлов в форматированный текст.
 *
 * Обрабатывает multipart/form-data запросы с JSON файлом из экспорта Telegram Desktop,
 * валидирует параметры фильтрации (даты, ключевые слова), и возвращает результат
 * потоковой передачей (streaming) для экономии памяти на больших файлах.
 *
 * Требует аутентификацию через API Key (заголовок X-API-Key), кроме /api/health.
 *
 * @see com.tcleaner.core.TelegramExporter
 * @see com.tcleaner.core.MessageFilter
 */
@RestController
@RequestMapping("/api")
public class TelegramController {

    private static final Logger log = LoggerFactory.getLogger(TelegramController.class);
    private final TelegramExporter exporter;

    /**
     * Конструктор с внедрением зависимости TelegramExporter.
     *
     * @param exporter экспортер для обработки JSON файлов
     */
    public TelegramController(TelegramExporter exporter) {
        this.exporter = exporter;
    }

    /**
     * Конвертирует Telegram JSON export файл в форматированный текстовый файл.
     *
     * Принимает multipart/form-data запрос с JSON файлом и опциональными параметрами
     * фильтрации. Возвращает форматированный текст потоковой передачей (StreamingResponseBody),
     * что позволяет обрабатывать файлы любого размера без буферизации в памяти.
     *
     * @param file JSON файл из экспорта Telegram Desktop (обязательный)
     * @param startDate начальная дата в формате YYYY-MM-DD (опционально)
     * @param endDate конечная дата в формате YYYY-MM-DD (опционально)
     * @param keywords список ключевых слов для включения, разделённые запятой (опционально)
     * @param excludeKeywords список ключевых слов для исключения, разделённые запятой (опционально)
     *
     * @return ResponseEntity с StreamingResponseBody содержащим форматированный текст
     *
     * @throws IllegalArgumentException если файл пустой или параметры фильтра невалидны
     * @throws IOException если ошибка при чтении/записи файла
     *
     * Примеры ошибок:
     * - 400 Bad Request: неверный формат даты (не YYYY-MM-DD)
     * - 400 Bad Request: startDate > endDate
     * - 401 Unauthorized: отсутствует или неверный X-API-Key
     * - 413 Payload Too Large: файл больше 2GB
     */
    @PostMapping("/convert")
    public ResponseEntity<StreamingResponseBody> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "keywords", required = false) String keywords,
            @RequestParam(value = "excludeKeywords", required = false) String excludeKeywords) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        // Фильтр готовим СРАЗУ, чтобы ошибки валидации вылетели до работы с файлами
        MessageFilter filter = MessageFilter.fromParameters(startDate, endDate, keywords, excludeKeywords);

        final Path tempFile = Files.createTempFile("tgc-", ".json");
        try (InputStream is = file.getInputStream()) {
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        StreamingResponseBody responseBody = outputStream -> {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                exporter.processFileStreaming(tempFile, filter, writer);
                writer.flush();
            } catch (Exception e) {
                log.error("ASYNCHRONOUS ERROR: {}", e.getMessage());
            } finally {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=output.txt")
                .contentType(MediaType.TEXT_PLAIN)
                .body(responseBody);
    }

    /**
     * Проверка состояния сервиса.
     *
     * Endpoint для мониторинга доступности API. Не требует аутентификации.
     * Может использоваться для health checks в Kubernetes/Docker/балансировщиках.
     *
     * @return ResponseEntity с JSON {"status": "UP"} при успехе
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Collections.singletonMap("status", "UP"));
    }
}
