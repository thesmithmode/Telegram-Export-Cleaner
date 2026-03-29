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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * REST контроллер для синхронной конвертации Telegram экспорта.
 *
 * <p>Предоставляет endpoint'ы для синхронной обработки файлов —
 * принял файл, вернул результат в теле ответа.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/convert} — загрузка файла {@code result.json} (multipart)</li>
 *   <li>{@code GET  /api/health}  — проверка доступности сервиса</li>
 * </ul>
 *
 * <h2>Память</h2>
 * <p>Обработка выполняется через Jackson Streaming API: сообщения читаются по одному
 * и пишутся в {@link StringWriter}. Пиковое потребление памяти пропорционально
 * размеру результата (а не входного файла).</p>
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
            return streamWithTempDir(file, filter);
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
     * Обрабатывает multipart-файл и возвращает результат как строку.
     *
     * <p>Файл сохраняется во временную директорию, затем читается через Jackson Streaming API.
     * Результат накапливается в {@link StringWriter} и возвращается как {@code text/plain}.</p>
     *
     * @param file   загруженный multipart-файл
     * @param filter фильтр сообщений, или {@code null}
     * @return текстовый ответ с обработанными сообщениями
     * @throws IOException при ошибках ввода-вывода
     */
    private ResponseEntity<?> streamWithTempDir(
            MultipartFile file, MessageFilter filter) throws IOException {
        try (TempDirectory tempDir = new TempDirectory()) {
            Path inputFile = tempDir.resolve("result.json");
            file.transferTo(inputFile.toFile());

            StringWriter sw = new StringWriter();
            try (BufferedWriter writer = new BufferedWriter(sw)) {
                exporter.processFileStreaming(inputFile, filter, writer);
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=output.txt")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(sw.toString());
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
