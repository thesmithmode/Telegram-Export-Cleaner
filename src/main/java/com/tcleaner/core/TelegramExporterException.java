package com.tcleaner.core;

public class TelegramExporterException extends RuntimeException {

    private final String errorCode;

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
