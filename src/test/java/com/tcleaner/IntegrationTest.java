package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Интеграционные тесты для полного цикла обработки.
 */
@DisplayName("Интеграционные тесты")
class IntegrationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Полный цикл: чтение, обработка, запись")
    void fullProcessingCycle() throws IOException {
        // Создаём тестовый result.json
        String json = """
            {
                "name": "Test Chat",
                "type": "private",
                "messages": [
                    {
                        "id": 1,
                        "type": "message",
                        "date": "2025-06-24T10:00:00",
                        "from": "User1",
                        "text": "First message"
                    },
                    {
                        "id": 2,
                        "type": "message",
                        "date": "2025-06-24T11:00:00",
                        "from": "User2",
                        "text": "Second message"
                    },
                    {
                        "id": 3,
                        "type": "service",
                        "date": "2025-06-24T12:00:00",
                        "action": "join",
                        "actor": "User3"
                    },
                    {
                        "id": 4,
                        "type": "message",
                        "date": "2025-06-24T13:00:00",
                        "from": "User1",
                        "text": "Third message"
                    }
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        // Обрабатываем
        TelegramExporter exporter = new TelegramExporter();
        List<String> result = exporter.processFile(inputFile);
        
        // Проверяем результат
        assertThat(result).hasSize(3);
        assertThat(result).containsExactly(
            "20250624 First message",
            "20250624 Second message",
            "20250624 Third message"
        );
    }

    @Test
    @DisplayName("Обработка реального фрагмента из экспорта")
    void processesRealExportFragment() throws IOException {
        String json = """
            {
                "name": "Test Chat",
                "type": "private_supergroup",
                "messages": [
                    {
                        "id": 8,
                        "type": "message",
                        "date": "2025-06-24T15:29:46",
                        "from": "Sprut_Ai",
                        "text": "Друзья, прошу прощения! В видео после выгрузки проблема со звуком. Выложу в течении часа снова🥹🥹🥹"
                    },
                    {
                        "id": 9,
                        "type": "message",
                        "date": "2025-06-24T15:49:12",
                        "from": "Sprut_Ai",
                        "text": [
                            "Новое видео на канале:\\n",
                            {"type": "link", "text": "https://www.youtube.com/watch?v=XGpMVsEqsqM"},
                            "\\n\\nОЧЕНЬ ЖДУ ОБРАТНУЮ СВЯЗЬ !😉\\n\\nИнструкция по установке"
                        ]
                    }
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        TelegramExporter exporter = new TelegramExporter();
        List<String> result = exporter.processFile(inputFile);
        
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo("20250624 Друзья, прошу прощения! В видео после выгрузки проблема со звуком. Выложу в течении часа снова🥹🥹🥹");
        // Note: In real Telegram export, plain strings in arrays become TextNodes
        // So "Новое видео..." is NOT lost - it's the first TextNode processed as "plain" type
        assertThat(result.get(1)).startsWith("20250624 Новое видео на канале:");
        assertThat(result.get(1)).contains("https://www.youtube.com");
    }

    @Test
    @DisplayName("Проверка вывода в файл")
    void writesToOutputFile() throws IOException {
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
        
        TelegramExporter exporter = new TelegramExporter();
        exporter.processFileToFile(inputFile, outputFile);
        
        // Проверяем содержимое выходного файла
        String content = Files.readString(outputFile);
        assertThat(content).isEqualTo("20250624 Line 1\n20250624 Line 2\n");
    }

    @Test
    @DisplayName("Корректная обработка пустого списка сообщений")
    void handlesEmptyMessageList() throws IOException {
        String json = """
            {
                "name": "Empty Chat",
                "messages": []
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        TelegramExporter exporter = new TelegramExporter();
        List<String> result = exporter.processFile(inputFile);
        
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Корректная обработка сообщений с различными сущностями")
    void handlesVariousEntities() throws IOException {
        String json = """
            {
                "messages": [
                    {
                        "id": 1,
                        "type": "message",
                        "date": "2025-06-24T10:00:00",
                        "text": [
                            {"type": "plain", "text": "Check "},
                            {"type": "bold", "text": "bold"},
                            {"type": "italic", "text": " italic"},
                            {"type": "code", "text": " code"},
                            {"type": "link", "text": " https://test.com"},
                            {"type": "spoiler", "text": " secret"}
                        ]
                    }
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        TelegramExporter exporter = new TelegramExporter();
        List<String> result = exporter.processFile(inputFile);
        
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("20250624 Check **bold*** italic*` code` https://test.com|| secret||");
    }
}
