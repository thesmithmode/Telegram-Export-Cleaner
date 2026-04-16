package com.tcleaner.api;

import com.tcleaner.core.TelegramExporterException;
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

@ControllerAdvice
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
        body.put("type", ex.getClass().getSimpleName());
        if (ex.getMessage() != null) {
            body.put("details", ex.getMessage());
        }
        return body;
    }
}
