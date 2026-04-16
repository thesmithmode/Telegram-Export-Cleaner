package com.tcleaner.core;

/**
 * Исключение для ошибок при обработке Telegram экспорта.
 *
 * <p>Содержит {@link #errorCode} — машиночитаемый идентификатор ошибки,
 * который возвращается клиенту в HTTP-ответе. Предпочтительные значения:
 * {@code FILE_NOT_FOUND} и {@code INVALID_JSON}.</p>
 */
public class TelegramExporterException extends RuntimeException {

    private final String errorCode;

    /**
     * Создаёт исключение с явным кодом ошибки.
     *
     * @param errorCode машиночитаемый код ошибки (например {@code FILE_NOT_FOUND})
     * @param message   описание ошибки
     */
    public TelegramExporterException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Создаёт исключение с явным кодом ошибки и причиной.
     *
     * @param errorCode машиночитаемый код ошибки (например {@code INVALID_JSON})
     * @param message   описание ошибки
     * @param cause     исходное исключение
     */
    public TelegramExporterException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Возвращает машиночитаемый код ошибки.
     *
     * @return код ошибки, например {@code FILE_NOT_FOUND} или {@code INVALID_JSON}
     */
    public String getErrorCode() {
        return errorCode;
    }
}
