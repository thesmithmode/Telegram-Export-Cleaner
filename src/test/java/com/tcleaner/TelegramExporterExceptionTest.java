package com.tcleaner;
import com.tcleaner.core.TelegramExporterException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для TelegramExporterException.
 */
@DisplayName("TelegramExporterException")
class TelegramExporterExceptionTest {

    @Nested
    @DisplayName("Конструктор с errorCode + message")
    class ErrorCodeMessageConstructor {

        @Test
        @DisplayName("Сохраняет errorCode и message")
        void storesErrorCodeAndMessage() {
            TelegramExporterException ex = new TelegramExporterException("GENERAL_ERROR", "что-то пошло не так");

            assertThat(ex.getErrorCode()).isEqualTo("GENERAL_ERROR");
            assertThat(ex.getMessage()).isEqualTo("что-то пошло не так");
            assertThat(ex.getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("Конструктор с errorCode + message + cause")
    class ErrorCodeMessageCauseConstructor {

        @Test
        @DisplayName("Сохраняет errorCode, message и cause")
        void storesAllFields() {
            RuntimeException cause = new RuntimeException("первопричина");
            TelegramExporterException ex = new TelegramExporterException("GENERAL_ERROR", "обёртка", cause);

            assertThat(ex.getMessage()).isEqualTo("обёртка");
            assertThat(ex.getErrorCode()).isEqualTo("GENERAL_ERROR");
            assertThat(ex.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("Поддерживает код FILE_NOT_FOUND")
        void supportsFileNotFoundCode() {
            TelegramExporterException ex = new TelegramExporterException("FILE_NOT_FOUND", "Файл не найден");

            assertThat(ex.getErrorCode()).isEqualTo("FILE_NOT_FOUND");
            assertThat(ex.getMessage()).isEqualTo("Файл не найден");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("Поддерживает код INVALID_JSON")
        void supportsInvalidJsonCode() {
            TelegramExporterException ex = new TelegramExporterException("INVALID_JSON", "Невалидный JSON");

            assertThat(ex.getErrorCode()).isEqualTo("INVALID_JSON");
        }
    }

    @Test
    @DisplayName("Является RuntimeException")
    void isRuntimeException() {
        TelegramExporterException ex = new TelegramExporterException("GENERAL_ERROR", "test");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
