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

/**
 * Глобальный обработчик исключений для REST API.
 *
 * Spring @ControllerAdvice для перехвата исключений со всех контроллеров
 * и преобразования их в правильные HTTP коды ответа и JSON-структуры.
 *
 * Обрабатывает следующие типы ошибок:
 * - DateTimeParseException → 400 Bad Request (неверный формат даты)
 * - MethodArgumentTypeMismatchException → 400 Bad Request (неверный тип параметра, напр. @DateTimeFormat)
 * - IllegalArgumentException → 400 Bad Request (неверные параметры)
 * - TelegramExporterException → 400 Bad Request (ошибка обработки)
 * - Exception (generic) → 500 Internal Server Error (неожиданная ошибка)
 *
 * Формат ошибки JSON:
 * {
 *   "message": "Описание ошибки для пользователя",
 *   "type": "ИмяИсключения",
 *   "details": "Детали (если есть)",
 *   "error": "Код ошибки (если есть)"
 * }
 *
 * @see org.springframework.web.bind.annotation.ControllerAdvice
 * @see com.tcleaner.core.TelegramExporterException
 */
@ControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Обработчик ошибок парсинга даты.
     *
     * Возвращает 400 Bad Request при невалидном формате даты.
     * Ожидается формат: YYYY-MM-DD (ISO 8601).
     *
     * @param ex исключение DateTimeParseException
     * @return ResponseEntity с 400 статусом и описанием ошибки
     */
    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<Map<String, String>> handleDateTimeParse(DateTimeParseException ex) {
        log.warn("Date parse error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(makeError("Невалидный формат даты. Используйте YYYY-MM-DD", ex));
    }

    /**
     * Обработчик ошибок типизации параметров запроса.
     *
     * Spring бросает {@link MethodArgumentTypeMismatchException} когда параметр запроса
     * не может быть сконвертирован в нужный тип (например, при {@code @DateTimeFormat}).
     *
     * @param ex исключение MethodArgumentTypeMismatchException
     * @return ResponseEntity с 400 статусом и описанием ошибки
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Parameter type mismatch for '{}': {}", ex.getName(), ex.getMessage());
        return ResponseEntity.badRequest().body(makeError(
                "Невалидный формат параметра '" + ex.getName() + "'. Используйте YYYY-MM-DD для дат", ex));
    }

    /**
     * Обработчик ошибок валидации параметров.
     *
     * Возвращает 400 Bad Request при нарушении ограничений.
     * Примеры: пустой файл, startDate > endDate, неверные параметры.
     *
     * @param ex исключение IllegalArgumentException
     * @return ResponseEntity с 400 статусом и описанием ошибки
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(makeError(ex.getMessage(), ex));
    }

    /**
     * Обработчик ошибок экспорта Telegram.
     *
     * Возвращает 400 Bad Request с кодом ошибки для ошибок обработки.
     * Это позволяет клиенту определить конкретную причину сбоя.
     *
     * @param ex исключение TelegramExporterException (содержит error code)
     * @return ResponseEntity с 400 статусом, описанием и кодом ошибки
     *
     * @see com.tcleaner.core.TelegramExporterException#getErrorCode()
     */
    @ExceptionHandler(TelegramExporterException.class)
    public ResponseEntity<Map<String, String>> handleExporterException(TelegramExporterException ex) {
        log.error("Exporter error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        Map<String, String> error = makeError(ex.getMessage(), ex);
        error.put("error", ex.getErrorCode());
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Обработчик неожиданных ошибок.
     *
     * Fallback для любых незаняных исключений. Возвращает 500 Internal Server Error
     * и логирует full stack trace для отладки.
     *
     * @param ex неожиданное исключение
     * @return ResponseEntity с 500 статусом и описанием ошибки (без деталей)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        log.error("CRITICAL ERROR: {} - {}", ex.getClass().getName(), ex.getMessage(), ex);
        return ResponseEntity.internalServerError().body(makeError("Внутренняя ошибка сервера", ex));
    }

    /**
     * Создаёт стандартный JSON-объект ошибки.
     *
     * @param message описание ошибки для пользователя (обязательное)
     * @param ex исключение (для извлечения типа и деталей)
     * @return Map с полями message, type, details
     */
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
