package com.tcleaner.dashboard.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Обработчик ошибок JSON API дашборда. Скоуп ограничен пакетом
 * {@code com.tcleaner.dashboard.web}, чтобы не конфликтовать с
 * {@code ApiExceptionHandler} для {@code /api/**}.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} гарантирует, что для исключений из
 * {@code DashboardApiController} сработает именно этот handler, а не глобальный
 * {@code ApiExceptionHandler.handleGenericException(Exception.class)}, который
 * вернул бы 500 вместо 403/400.
 */
@RestControllerAdvice(basePackages = "com.tcleaner.dashboard.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DashboardExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DashboardExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Dashboard access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(ex.getMessage(), "forbidden"));
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EmptyResultDataAccessException ex) {
        log.info("Dashboard entity not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Запись не найдена", "not_found"));
    }

    @ExceptionHandler({DateTimeParseException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> handleBadParam(Exception ex) {
        log.warn("Dashboard bad param: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(error(
                "Невалидный параметр запроса (ожидался YYYY-MM-DD или число)", "bad_request"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException ex) {
        log.warn("Dashboard validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(error(ex.getMessage(), "bad_request"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleBodyValidation(MethodArgumentNotValidException ex) {
        log.warn("Dashboard body validation failed: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(error("Невалидные поля запроса", "validation_failed"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        log.warn("Dashboard response-status: {} {}", ex.getStatusCode(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(error(ex.getReason(), "error"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Dashboard internal error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("Внутренняя ошибка сервера", "internal_error"));
    }

    private Map<String, String> error(String message, String code) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message != null ? message : "Unknown");
        return body;
    }
}
