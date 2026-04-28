package com.tcleaner.api;

import com.tcleaner.core.TelegramExporterException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ограничено пакетом {@code com.tcleaner.api} — этот advice предназначен для REST-API
 * экспортера (фильтры DateTimeParseException, TelegramExporterException и catch-all на 500).
 * Без scope он ловит {@code ResponseStatusException}/{@code MethodArgumentNotValidException}
 * из dashboard-контроллеров и превращает их в 500 — ломая контракт (401/400 ожидаемы).
 */
@ControllerAdvice(basePackages = "com.tcleaner.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<Map<String, String>> handleDateTimeParse(DateTimeParseException ex) {
        log.warn("Date parse error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(makeError("Невалидный формат даты. Используйте YYYY-MM-DD", ex));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Parameter type mismatch for '{}': {}", ex.getName(), ex.getMessage());
        return ResponseEntity.badRequest().body(makeError(
                "Невалидный формат параметра '" + ex.getName() + "'. Используйте YYYY-MM-DD для дат", ex));
    }


    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(makeError(ex.getMessage(), ex));
    }


    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(ConstraintViolationException ex) {
        String details = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("Bean validation error: {}", details);
        Map<String, String> error = makeError("Невалидные параметры запроса: " + details, ex);
        error.put("error", "validation_failed");
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(TelegramExporterException.class)
    public ResponseEntity<Map<String, String>> handleExporterException(TelegramExporterException ex) {
        log.error("Exporter error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        Map<String, String> error = makeError(ex.getMessage(), ex);
        error.put("error", ex.getErrorCode());
        return ResponseEntity.badRequest().body(error);
    }

    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        log.error("CRITICAL ERROR: {} - {}", ex.getClass().getName(), ex.getMessage(), ex);
        Map<String, String> body = new HashMap<>();
        body.put("message", "Внутренняя ошибка сервера");
        body.put("type", "InternalError");
        return ResponseEntity.internalServerError().body(body);
    }


    private Map<String, String> makeError(String message, Exception ex) {
        Map<String, String> body = new HashMap<>();
        body.put("message", message != null ? message : "Unknown error");
        return body;
    }
}
