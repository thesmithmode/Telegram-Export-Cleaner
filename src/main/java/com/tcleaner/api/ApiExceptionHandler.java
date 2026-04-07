package com.tcleaner.api;

import com.tcleaner.core.TelegramExporterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Глобальный обработчик исключений REST API.
 *
 * <p>Маппинг исключений на HTTP-статусы:</p>
 * <ul>
 *   <li>{@link DateTimeParseException} → 400 (невалидный формат даты)</li>
 *   <li>{@link IllegalArgumentException} → 400 (невалидный диапазон дат)</li>
 *   <li>{@link TelegramExporterException} → 400 с кодом ошибки</li>
 *   <li>{@link IOException} → 500</li>
 *   <li>Любое другое → 500</li>
 * </ul>
 */
@ControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<Map<String, String>> handleDateTimeParse(DateTimeParseException ex) {
        log.warn("Невалидный формат даты в запросе: {}", ex.getParsedString());
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Невалидный формат даты. Используйте YYYY-MM-DD"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Ошибка валидации: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(TelegramExporterException.class)
    public ResponseEntity<Map<String, String>> handleExporterException(TelegramExporterException ex) {
        log.error("Ошибка экспортера [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getErrorCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleIOException(IOException ex) {
        log.error("Ошибка ввода/вывода при конвертации", ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Внутренняя ошибка сервера"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        log.error("Неожиданная ошибка при конвертации", ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Внутренняя ошибка сервера"));
    }
}
