package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.MessageFilterFactory;
import com.tcleaner.core.TelegramExporterException;
import com.tcleaner.core.TelegramExporterInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * REST контроллер для синхронной конвертации Telegram экспорта.
 *
 * <p>Предоставляет endpoint'ы для немедленной (синхронной) обработки файлов —
 * принял файл, вернул результат в теле ответа. Это отличает данный контроллер
 * от {@link FileController}, который работает асинхронно через очередь
 * Import → Export с опросом статуса.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/convert}      — загрузка файла {@code result.json} (multipart)</li>
 *   <li>{@code POST /api/convert/json} — передача JSON-содержимого напрямую в теле запроса</li>
 *   <li>{@code GET  /api/health}       — проверка доступности сервиса</li>
 * </ul>
 *
 * <h2>Память</h2>
 * <p>Список обработанных строк формируется один раз в {@link TelegramExporter} и
 * напрямую объединяется через {@code String.join} без промежуточной записи на диск
 * и повторного чтения. Для синхронного endpoint'а, где результат сразу отдаётся
 * клиенту, это оптимально.</p>
 */
@RestController
@RequestMapping("/api")
public class TelegramController {

    private static final Logger log = LoggerFactory.getLogger(TelegramController.class);

    /** Максимальный размер JSON-тела для {@code /api/convert/json} (символы, не байты). */
    private static final int MAX_JSON_BODY_CHARS = 10 * 1024 * 1024; // 10 MB

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
     * Конвертирует загруженный файл {@code result.json} в текстовый формат.
     *
     * <p>Принимает multipart/form-data запрос с файлом {@code result.json}.
     * Возвращает текстовый файл с обработанными сообщениями в теле ответа.</p>
     *
     * @param file            загруженный файл {@code result.json}
     * @param startDate       начальная дата фильтра в формате {@code YYYY-MM-DD}, или {@code null}
     * @param endDate         конечная дата фильтра в формате {@code YYYY-MM-DD}, или {@code null}
     * @param keywords        ключевые слова для включения сообщений, через запятую, или {@code null}
     * @param excludeKeywords ключевые слова для исключения сообщений, через запятую, или {@code null}
     * @return 200 с текстовым файлом, 400 при ошибке валидации, 500 при внутренней ошибке
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

        try {
            MessageFilter filter = MessageFilterFactory.build(startDate, endDate, keywords, excludeKeywords);
            return processWithTempDir(file, filter);
        } catch (Exception ex) {
            return handleConvertException(ex);
        }
    }

    /**
     * Конвертирует JSON-содержимое, переданное напрямую в теле запроса.
     *
     * <p>Принимает строку с содержимым {@code result.json} и возвращает обработанный
     * текст. Максимальный размер тела ограничен {@value #MAX_JSON_BODY_CHARS} символами
     * (лимит multipart на {@code /api/convert} к телу запроса не применяется).</p>
     *
     * @param jsonContent     содержимое {@code result.json} в виде строки
     * @param startDate       начальная дата фильтра в формате {@code YYYY-MM-DD}, или {@code null}
     * @param endDate         конечная дата фильтра в формате {@code YYYY-MM-DD}, или {@code null}
     * @param keywords        ключевые слова для включения сообщений, через запятую, или {@code null}
     * @param excludeKeywords ключевые слова для исключения сообщений, через запятую, или {@code null}
     * @return 200 с текстом, 400 при ошибке валидации или превышении размера, 500 при внутренней ошибке
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

        if (jsonContent.length() > MAX_JSON_BODY_CHARS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Содержимое превышает максимально допустимый размер 10MB"));
        }

        try {
            MessageFilter filter = MessageFilterFactory.build(startDate, endDate, keywords, excludeKeywords);
            return processJsonWithTempDir(jsonContent, filter);
        } catch (Exception ex) {
            return handleConvertException(ex);
        }
    }

    /**
     * Проверяет доступность сервиса.
     *
     * @return 200 со статусом {@code {"status": "UP"}}
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    /**
     * Обрабатывает multipart-файл во временной директории и возвращает результат.
     *
     * <p>Файл сохраняется во временную директорию для передачи в {@link TelegramExporter}.
     * Результат возвращается клиенту напрямую из памяти — без дополнительных I/O-операций.</p>
     *
     * @param file   загруженный multipart-файл
     * @param filter фильтр сообщений, или {@code null}
     * @return текстовый ответ с обработанными сообщениями
     * @throws IOException при ошибках ввода-вывода
     */
    private ResponseEntity<?> processWithTempDir(
            MultipartFile file, MessageFilter filter) throws IOException {
        try (TempDirectory tempDir = new TempDirectory()) {
            Path inputFile = tempDir.resolve("result.json");
            file.transferTo(inputFile.toFile());

            List<String> processed = exporter.processFile(inputFile, filter);
            String result = String.join("\n", processed);
            if (!result.isEmpty()) {
                result += "\n";
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=output.txt")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(result);
        }
    }

    /**
     * Обрабатывает JSON-строку во временной директории и возвращает результат.
     *
     * <p>JSON сохраняется во временный файл для передачи в {@link TelegramExporter}.
     * Результат возвращается клиенту напрямую из памяти.</p>
     *
     * @param jsonContent содержимое {@code result.json}
     * @param filter      фильтр сообщений, или {@code null}
     * @return текстовый ответ с обработанными сообщениями
     * @throws IOException при ошибках ввода-вывода
     */
    private ResponseEntity<?> processJsonWithTempDir(
            String jsonContent, MessageFilter filter) throws IOException {
        try (TempDirectory tempDir = new TempDirectory()) {
            Path inputFile = tempDir.resolve("result.json");
            Files.writeString(inputFile, jsonContent);

            List<String> processed = exporter.processFile(inputFile, filter);
            String result = String.join("\n", processed);
            if (!result.isEmpty()) {
                result += "\n";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(result);
        }
    }

    /**
     * Единая обработка исключений для обоих методов конвертации.
     *
     * <p>Маппинг исключений на HTTP-статусы:</p>
     * <ul>
     *   <li>{@link java.time.format.DateTimeParseException} → 400 (невалидный формат даты)</li>
     *   <li>{@link IllegalArgumentException} → 400 (невалидный диапазон дат)</li>
     *   <li>{@link TelegramExporterException} → 400 с кодом ошибки</li>
     *   <li>{@link IOException} → 500</li>
     *   <li>Любое другое → 500</li>
     * </ul>
     *
     * @param ex пойманное исключение
     * @return ответ с описанием ошибки
     */
    private ResponseEntity<?> handleConvertException(Exception ex) {
        if (ex instanceof java.time.format.DateTimeParseException dtpe) {
            log.warn("Невалидный формат даты в запросе: {}", dtpe.getParsedString());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Невалидный формат даты. Используйте YYYY-MM-DD"));
        }
        if (ex instanceof IllegalArgumentException) {
            log.warn("Невалидный диапазон дат: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", ex.getMessage()));
        }
        if (ex instanceof TelegramExporterException tex) {
            log.error("Ошибка экспортера [{}]: {}", tex.getErrorCode(), tex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", tex.getErrorCode(), "message", tex.getMessage()));
        }
        if (ex instanceof IOException) {
            log.error("Ошибка ввода/вывода при конвертации", ex);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Внутренняя ошибка сервера"));
        }
        log.error("Неожиданная ошибка при конвертации", ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Внутренняя ошибка сервера"));
    }

    /**
     * Утилитный класс для автоматической очистки временной директории.
     *
     * <p>Используется в try-with-resources: директория и все её содержимое
     * удаляются в {@link #close()}, даже если в теле блока было выброшено
     * исключение.</p>
     */
    private static class TempDirectory implements AutoCloseable {

        private static final Logger log = LoggerFactory.getLogger(TempDirectory.class);
        private final Path path;

        /**
         * Создаёт новую временную директорию с префиксом {@code telegram-cleaner}.
         *
         * @throws IOException если не удалось создать директорию
         */
        TempDirectory() throws IOException {
            this.path = Files.createTempDirectory("telegram-cleaner");
        }

        /**
         * Возвращает путь к файлу внутри временной директории.
         *
         * @param fileName имя файла (без пути)
         * @return полный путь {@code <tempDir>/<fileName>}
         */
        Path resolve(String fileName) {
            return path.resolve(fileName);
        }

        /**
         * Рекурсивно удаляет директорию и все её содержимое.
         *
         * <p>Ошибки удаления отдельных файлов логируются, но не останавливают
         * очистку остальных файлов.</p>
         *
         * @throws IOException если не удалось обойти дерево директорий
         */
        @Override
        public void close() throws IOException {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(pp -> {
                        try {
                            Files.deleteIfExists(pp);
                        } catch (IOException e) {
                            log.warn("Не удалось удалить временный файл: {}", pp, e);
                        }
                    });
        }
    }
}
