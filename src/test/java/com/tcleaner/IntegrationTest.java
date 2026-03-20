package com.tcleaner;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты для полного цикла обработки Telegram-экспорта.
 *
 * <p>Тесты работают с реальным {@link TelegramExporter} без моков,
 * проверяя сквозной путь от JSON-файла до итоговых строк.</p>
 */
@DisplayName("Интеграционные тесты")
class IntegrationTest {

    @TempDir
    Path tempDir;

    private TelegramExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new TelegramExporter();
    }

    @Test
    @DisplayName("Полный цикл: чтение, обработка, запись — service-сообщения пропускаются")
    void fullProcessingCycle() throws IOException {
        String json = """
            {
                "name": "Test Chat",
                "type": "private",
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "from": "User1", "text": "First message"},
                    {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "from": "User2", "text": "Second message"},
                    {"id": 3, "type": "service",  "date": "2025-06-24T12:00:00", "action": "join", "actor": "User3"},
                    {"id": 4, "type": "message", "date": "2025-06-24T13:00:00", "from": "User1", "text": "Third message"}
                ]
            }
            """;

        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);

        List<String> result = exporter.processFile(inputFile);

        assertThat(result).hasSize(3);
        assertThat(result).containsExactly(
            "20250624 First message",
            "20250624 Second message",
            "20250624 Third message"
        );
    }

    @Test
    @DisplayName("Обработка реального фрагмента из экспорта (mixed-content text)")
    void processesRealExportFragment() throws IOException {
        String json = """
            {
                "name": "Test Chat",
                "type": "private_supergroup",
                "messages": [
                    {
                        "id": 8, "type": "message", "date": "2025-06-24T15:29:46",
                        "from": "Sprut_Ai",
                        "text": "Друзья, прошу прощения! В видео после выгрузки проблема со звуком."
                    },
                    {
                        "id": 9, "type": "message", "date": "2025-06-24T15:49:12",
                        "from": "Sprut_Ai",
                        "text": [
                            "Новое видео на канале:\\n",
                            {"type": "link", "text": "https://www.youtube.com/watch?v=XGpMVsEqsqM"},
                            "\\n\\nИнструкция по установке"
                        ]
                    }
                ]
            }
            """;

        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);

        List<String> result = exporter.processFile(inputFile);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).startsWith("20250624 Друзья");
        assertThat(result.get(1)).startsWith("20250624 Новое видео на канале:");
        assertThat(result.get(1)).contains("https://www.youtube.com");
    }

    @Test
    @DisplayName("processFileToFile без фильтра: содержимое совпадает с processFile")
    void processFileToFileMatchesProcessFile() throws IOException {
        String json = """
            {
                "name": "Test",
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Line 1"},
                    {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "text": "Line 2"}
                ]
            }
            """;

        Path inputFile = tempDir.resolve("result.json");
        Path outputFile = tempDir.resolve("output.txt");
        Files.writeString(inputFile, json);

        exporter.processFileToFile(inputFile, outputFile);

        // Files.write пишет строки через системный разделитель строк
        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).containsExactly(
            "20250624 Line 1",
            "20250624 Line 2"
        );
    }

    @Test
    @DisplayName("processFileToFile с фильтром: применяет фильтр перед записью")
    void processFileToFileWithFilterAppliesFilter() throws IOException {
        String json = """
            {
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Keep this"},
                    {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "text": "Skip this"}
                ]
            }
            """;

        Path inputFile = tempDir.resolve("result.json");
        Path outputFile = tempDir.resolve("output.txt");
        Files.writeString(inputFile, json);

        MessageFilter filter = new MessageFilter().withKeyword("keep");
        exporter.processFileToFile(inputFile, outputFile, filter);

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("Keep this");
    }

    @Test
    @DisplayName("Пустой список сообщений → пустой выходной файл")
    void handlesEmptyMessageList() throws IOException {
        String json = """
            {
                "name": "Empty Chat",
                "messages": []
            }
            """;

        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);

        List<String> result = exporter.processFile(inputFile);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Корректная обработка смешанных Markdown-сущностей")
    void handlesVariousEntities() throws IOException {
        String json = """
            {
                "messages": [
                    {
                        "id": 1, "type": "message", "date": "2025-06-24T10:00:00",
                        "text": [
                            {"type": "plain", "text": "Check "},
                            {"type": "bold",  "text": "bold"},
                            {"type": "plain", "text": " "},
                            {"type": "italic","text": "italic"},
                            {"type": "plain", "text": " "},
                            {"type": "code",  "text": "code"},
                            {"type": "plain", "text": " "},
                            {"type": "link",  "text": "https://test.com"},
                            {"type": "plain", "text": " "},
                            {"type": "spoiler","text": "secret"}
                        ]
                    }
                ]
            }
            """;

        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);

        List<String> result = exporter.processFile(inputFile);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(
                "20250624 Check **bold** *italic* `code` https://test.com ||secret||");
    }
}
