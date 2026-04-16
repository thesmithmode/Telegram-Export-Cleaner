package com.tcleaner;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.core.TelegramExporterException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tcleaner.core.MessageProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TelegramExporter - Error Handling")
class TelegramExporterErrorHandlingTest {

    private TelegramExporter exporter;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        exporter = new TelegramExporter(mapper, new MessageProcessor());
    }

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Обработка ошибок файлового ввода/вывода")
    class FileIOErrors {

        @Test
        @DisplayName("Выбрасывает IOException для несуществующего файла")
        void throwsExceptionForNonExistentFile() {
            Path nonExistent = tempDir.resolve("nonexistent.json");

            assertThatThrownBy(() -> exporter.processFile(nonExistent))
                    .isInstanceOf(TelegramExporterException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "FILE_NOT_FOUND");
        }

        @Test
        @DisplayName("Выбрасывает IOException для невалидного JSON")
        void throwsExceptionForInvalidJson() throws IOException {
            Path invalidJson = tempDir.resolve("invalid.json");
            Files.writeString(invalidJson, "{ invalid json }");

            assertThatThrownBy(() -> exporter.processFile(invalidJson))
                    .isInstanceOf(TelegramExporterException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_JSON");
        }

        @Test
        @DisplayName("Возвращает пустой список для пустого JSON объекта")
        void returnsEmptyListForEmptyJson() throws IOException {
            Path emptyJson = tempDir.resolve("empty.json");
            Files.writeString(emptyJson, "{}");

            List<String> result = exporter.processFile(emptyJson);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Возвращает пустой список для JSON без массива messages")
        void returnsEmptyListForNoMessagesArray() throws IOException {
            Path noMessages = tempDir.resolve("no_messages.json");
            Files.writeString(noMessages, """
                {"name": "Test", "type": "private"}
                """);

            List<String> result = exporter.processFile(noMessages);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Возвращает пустой список для null messages")
        void returnsEmptyListForNullMessages() throws IOException {
            Path nullMessages = tempDir.resolve("null_messages.json");
            Files.writeString(nullMessages, """
                {"name": "Test", "messages": null}
                """);

            List<String> result = exporter.processFile(nullMessages);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Интеграционные сценарии")
    class IntegrationScenarios {

        @Test
        @DisplayName("Обрабатывает чат с большим количеством сообщений")
        void handlesLargeChat() throws IOException {
            StringBuilder sb = new StringBuilder("{\"messages\": [");
            for (int i = 0; i < 100; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format("""
                    {"id": %d, "type": "message", "date": "2025-06-24T10:00:%02d", "text": "Message %d"}
                    """, i, i % 60, i));
            }
            sb.append("]}");

            Path inputFile = tempDir.resolve("result.json");
            Files.writeString(inputFile, sb.toString());

            List<String> result = exporter.processFile(inputFile);

            assertThat(result).hasSize(100);
        }

        @Test
        @DisplayName("Обрабатывает чат с русскими символами")
        void handlesRussianText() throws IOException {
            String json = """
                {
                    "messages": [
                        {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Привет мир!"},
                        {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "text": "Как дела? 😀"}
                    ]
                }
                """;

            Path inputFile = tempDir.resolve("result.json");
            Files.writeString(inputFile, json);

            List<String> result = exporter.processFile(inputFile);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).contains("Привет мир");
            assertThat(result.get(1)).contains("Как дела");
        }
    }
}
