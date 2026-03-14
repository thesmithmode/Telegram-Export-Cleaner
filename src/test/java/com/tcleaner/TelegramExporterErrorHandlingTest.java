package com.tcleaner;

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

/**
 * Тесты для {@link TelegramExporter}: обработка ошибок и граничные условия.
 *
 * <p>Покрывает:</p>
 * <ul>
 *   <li>отсутствующий файл → {@code FILE_NOT_FOUND}</li>
 *   <li>невалидный JSON → {@code INVALID_JSON}</li>
 *   <li>отсутствие/null поля {@code messages} → пустой список</li>
 *   <li>корректную запись в выходной файл</li>
 *   <li>перезапись существующего файла</li>
 *   <li>большие чаты и Unicode</li>
 * </ul>
 */
@DisplayName("TelegramExporter — обработка ошибок")
class TelegramExporterErrorHandlingTest {

    @TempDir
    Path tempDir;

    // ─── Ошибки файлового ввода/вывода ───────────────────────────────────────

    @Nested
    @DisplayName("Ошибки файлового ввода/вывода")
    class FileIOErrors {

        @Test
        @DisplayName("Несуществующий файл → TelegramExporterException FILE_NOT_FOUND")
        void throwsExceptionForNonExistentFile() {
            Path nonExistent = tempDir.resolve("nonexistent.json");
            TelegramExporter exporter = new TelegramExporter();

            assertThatThrownBy(() -> exporter.processFile(nonExistent))
                    .isInstanceOf(TelegramExporterException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "FILE_NOT_FOUND");
        }

        @Test
        @DisplayName("Невалидный JSON → TelegramExporterException INVALID_JSON")
        void throwsExceptionForInvalidJson() throws IOException {
            Path invalidJson = tempDir.resolve("invalid.json");
            Files.writeString(invalidJson, "{ invalid json }");

            TelegramExporter exporter = new TelegramExporter();

            assertThatThrownBy(() -> exporter.processFile(invalidJson))
                    .isInstanceOf(TelegramExporterException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_JSON");
        }

        @Test
        @DisplayName("Пустой JSON-объект {} → пустой список (поле messages отсутствует)")
        void returnsEmptyListForEmptyJson() throws IOException {
            Path emptyJson = tempDir.resolve("empty.json");
            Files.writeString(emptyJson, "{}");

            TelegramExporter exporter = new TelegramExporter();
            List<String> result = exporter.processFile(emptyJson);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("JSON без поля messages → пустой список")
        void returnsEmptyListForNoMessagesArray() throws IOException {
            Path noMessages = tempDir.resolve("no_messages.json");
            Files.writeString(noMessages, """
                {"name": "Test", "type": "private"}
                """);

            TelegramExporter exporter = new TelegramExporter();
            List<String> result = exporter.processFile(noMessages);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("messages: null → пустой список")
        void returnsEmptyListForNullMessages() throws IOException {
            Path nullMessages = tempDir.resolve("null_messages.json");
            Files.writeString(nullMessages, """
                {"name": "Test", "messages": null}
                """);

            TelegramExporter exporter = new TelegramExporter();
            List<String> result = exporter.processFile(nullMessages);

            assertThat(result).isEmpty();
        }
    }

    // ─── Запись в выходной файл ───────────────────────────────────────────────

    @Nested
    @DisplayName("Запись в выходной файл")
    class FileOutputErrors {

        @Test
        @DisplayName("Корректно записывает одно сообщение в файл")
        void writesToFile() throws IOException {
            String json = """
                {
                    "messages": [
                        {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Test"}
                    ]
                }
                """;

            Path inputFile = tempDir.resolve("result.json");
            Path outputFile = tempDir.resolve("output.txt");
            Files.writeString(inputFile, json);

            TelegramExporter exporter = new TelegramExporter();
            exporter.processFileToFile(inputFile, outputFile);

            assertThat(outputFile).exists();
            List<String> lines = Files.readAllLines(outputFile);
            assertThat(lines).containsExactly("20250624 Test");
        }

        @Test
        @DisplayName("Перезаписывает существующий файл")
        void overwritesExistingFile() throws IOException {
            String json = """
                {"messages": [{"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "New"}]}
                """;

            Path inputFile = tempDir.resolve("result.json");
            Path outputFile = tempDir.resolve("output.txt");
            Files.writeString(inputFile, json);
            Files.writeString(outputFile, "old content");

            TelegramExporter exporter = new TelegramExporter();
            exporter.processFileToFile(inputFile, outputFile);

            String content = Files.readString(outputFile);
            assertThat(content).doesNotContain("old content");
            assertThat(content).contains("New");
        }

        @Test
        @DisplayName("Создаёт новый файл если его нет")
        void createsNewFile() throws IOException {
            String json = """
                {"messages": [{"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Test"}]}
                """;

            Path inputFile = tempDir.resolve("result.json");
            Path outputFile = tempDir.resolve("new_output.txt");
            Files.writeString(inputFile, json);

            TelegramExporter exporter = new TelegramExporter();
            exporter.processFileToFile(inputFile, outputFile);

            assertThat(outputFile).exists();
        }
    }

    // ─── Интеграционные сценарии ──────────────────────────────────────────────

    @Nested
    @DisplayName("Интеграционные сценарии")
    class IntegrationScenarios {

        @Test
        @DisplayName("Чат с 100 сообщениями — все обрабатываются")
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

            TelegramExporter exporter = new TelegramExporter();
            List<String> result = exporter.processFile(inputFile);

            assertThat(result).hasSize(100);
        }

        @Test
        @DisplayName("Сообщения с кириллицей и emoji обрабатываются корректно")
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

            TelegramExporter exporter = new TelegramExporter();
            List<String> result = exporter.processFile(inputFile);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).contains("Привет мир");
            assertThat(result.get(1)).contains("Как дела");
        }
    }
}
