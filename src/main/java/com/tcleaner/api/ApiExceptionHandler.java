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

    /**
     * Обрабатывает ошибку парсинга формата даты.
     *
     * @param ex исключение при невалидном формате даты
     * @return ResponseEntity с статусом 400 и сообщением об ошибке формата даты
     */
    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<Map<String, String>> handleDateTimeParse(DateTimeParseException ex) {
        log.warn("Невалидный формат даты в запросе: {}", ex.getParsedString());
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Невалидный формат даты. Используйте YYYY-MM-DD"));
    }

    /**
     * Обрабатывает ошибку валидации аргументов.
     *
     * @param ex исключение при невалидных аргументах (например, неверный диапазон дат)
     * @return ResponseEntity с статусом 400 и сообщением об ошибке
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Ошибка валидации: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Обрабатывает ошибки экспортирования Telegram данных.
     *
     * @param ex исключение с машиночитаемым кодом ошибки (FILE_NOT_FOUND, INVALID_JSON и т.д.)
     * @return ResponseEntity с статусом 400, кодом ошибки и сообщением
     */
    @ExceptionHandler(TelegramExporterException.class)
    public ResponseEntity<Map<String, String>> handleExporterException(TelegramExporterException ex) {
        log.error("Ошибка экспортера [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getErrorCode(), "message", ex.getMessage()));
    }

    /**
     * Обрабатывает ошибки ввода/вывода при работе с файлами.
     *
     * @param ex исключение при операциях с файлами или потоками
     * @return ResponseEntity с статусом 500 и сообщением об ошибке сервера
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleIoException(IOException ex) {
        log.error("Ошибка ввода/вывода при конвертации", ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Внутренняя ошибка сервера"));
    }

    /**
     * Обрабатывает любые неожиданные исключения, не перехваченные более специфичными обработчиками.
     *
     * @param ex любое исключение, которое не было явно обработано выше
     * @return ResponseEntity с статусом 500 и сообщением об ошибке сервера
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        log.error("Неожиданная ошибка при конвертации", ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Внутренняя ошибка сервера"));
    }
}
