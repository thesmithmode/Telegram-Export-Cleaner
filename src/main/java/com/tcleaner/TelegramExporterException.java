package com.tcleaner;

/**
 * Исключение для ошибок при обработке Telegram экспорта.
 */
public class TelegramExporterException extends RuntimeException {

    private final String errorCode;

    public TelegramExporterException(String message) {
        super(message);
        this.errorCode = "GENERAL_ERROR";
    }

    public TelegramExporterException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "GENERAL_ERROR";
    }

    public TelegramExporterException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TelegramExporterException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
