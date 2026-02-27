package com.tcleaner;

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
    @DisplayName("Конструктор с message")
    class MessageConstructor {

        @Test
        @DisplayName("Сохраняет сообщение и устанавливает GENERAL_ERROR")
        void storesMessageAndDefaultCode() {
            TelegramExporterException ex = new TelegramExporterException("что-то пошло не так");

            assertThat(ex.getMessage()).isEqualTo("что-то пошло не так");
            assertThat(ex.getErrorCode()).isEqualTo("GENERAL_ERROR");
            assertThat(ex.getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("Конструктор с message + cause")
    class MessageCauseConstructor {

        @Test
        @DisplayName("Сохраняет сообщение, cause и устанавливает GENERAL_ERROR")
        void storesMessageCauseAndDefaultCode() {
            RuntimeException cause = new RuntimeException("первопричина");
            TelegramExporterException ex = new TelegramExporterException("обёртка", cause);

            assertThat(ex.getMessage()).isEqualTo("обёртка");
            assertThat(ex.getErrorCode()).isEqualTo("GENERAL_ERROR");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("Конструктор с errorCode + message")
    class ErrorCodeMessageConstructor {

        @Test
        @DisplayName("Сохраняет errorCode и message")
        void storesErrorCodeAndMessage() {
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

    @Nested
    @DisplayName("Конструктор с errorCode + message + cause")
    class ErrorCodeMessageCauseConstructor {

        @Test
        @DisplayName("Сохраняет errorCode, message и cause")
        void storesAllFields() {
            RuntimeException cause = new RuntimeException("json parse error");
            TelegramExporterException ex = new TelegramExporterException(
                    "INVALID_JSON", "Невалидный JSON", cause);

            assertThat(ex.getErrorCode()).isEqualTo("INVALID_JSON");
            assertThat(ex.getMessage()).isEqualTo("Невалидный JSON");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Test
    @DisplayName("Является RuntimeException")
    void isRuntimeException() {
        TelegramExporterException ex = new TelegramExporterException("test");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
