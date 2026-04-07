package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.MessageFilterFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.Map;

/**
 * REST контроллер для синхронной конвертации Telegram экспорта.
 *
 * <p>Делегирует бизнес-логику в {@link FileConversionService}.
 * Исключения обрабатываются через {@link ApiExceptionHandler}.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/convert} — загрузка файла {@code result.json} (multipart). Требует API ключ.</li>
 *   <li>{@code GET  /api/health}  — проверка доступности сервиса. Без API ключа.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class TelegramController {

    private final FileConversionService conversionService;

    public TelegramController(FileConversionService conversionService) {
        this.conversionService = conversionService;
    }

    /**
     * Конвертирует загруженный файл {@code result.json} в текстовый формат.
     *
     * <p>Требует заголовок {@code X-API-Key} (проверяется в {@link ApiKeyFilter}).</p>
     *
     * @param file            загруженный файл {@code result.json}
     * @param startDate       начальная дата фильтра в формате {@code YYYY-MM-DD}, или {@code null}
     * @param endDate         конечная дата фильтра в формате {@code YYYY-MM-DD}, или {@code null}
     * @param keywords        ключевые слова для включения, через запятую, или {@code null}
     * @param excludeKeywords ключевые слова для исключения, через запятую, или {@code null}
     * @return 200 с текстовым файлом, 400 при ошибке валидации, 401 при неверном API ключе
     */
    @PostMapping("/convert")
    public ResponseEntity<StreamingResponseBody> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "keywords", required = false) String keywords,
            @RequestParam(value = "excludeKeywords", required = false) String excludeKeywords)
            throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.endsWith(".json")) {
            throw new IllegalArgumentException("Ожидается JSON файл");
        }

        MessageFilter filter = MessageFilterFactory.build(startDate, endDate, keywords, excludeKeywords);
        return conversionService.convert(file, filter);
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
}
