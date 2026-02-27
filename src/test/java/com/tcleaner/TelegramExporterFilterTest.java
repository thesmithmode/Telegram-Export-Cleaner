package com.tcleaner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для TelegramExporter с фильтрацией через MessageFilter.
 */
@DisplayName("TelegramExporter - Filter Integration")
class TelegramExporterFilterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("processFile с MessageFilter фильтрует по ключевым словам")
    void processFileWithKeywordFilter() throws IOException {
        String json = """
            {
                "name": "Test Chat",
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Hello world"},
                    {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "text": "Goodbye world"},
                    {"id": 3, "type": "message", "date": "2025-06-24T12:00:00", "text": "Another message"}
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        TelegramExporter exporter = new TelegramExporter();
        MessageFilter filter = new MessageFilter().withKeyword("hello");
        
        List<String> result = exporter.processFile(inputFile, filter);
        
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("Hello world");
    }

    @Test
    @DisplayName("processFile с MessageFilter фильтрует по диапазону дат")
    void processFileWithDateRangeFilter() throws IOException {
        String json = """
            {
                "name": "Test Chat",
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-20T10:00:00", "text": "June 20"},
                    {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "text": "June 24"},
                    {"id": 3, "type": "message", "date": "2025-06-28T12:00:00", "text": "June 28"}
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        TelegramExporter exporter = new TelegramExporter();
        MessageFilter filter = new MessageFilter()
                .withStartDate(LocalDate.of(2025, 6, 22))
                .withEndDate(LocalDate.of(2025, 6, 26));
        
        List<String> result = exporter.processFile(inputFile, filter);
        
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("June 24");
    }

    @Test
    @DisplayName("processFile без MessageFilter возвращает все сообщения")
    void processFileWithoutFilterReturnsAll() throws IOException {
        String json = """
            {
                "name": "Test Chat",
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "First"},
                    {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "text": "Second"}
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        TelegramExporter exporter = new TelegramExporter();
        
        List<String> result = exporter.processFile(inputFile, null);
        
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("processFile с null MessageFilter эквивалентен отсутствию фильтра")
    void processFileWithNullFilterIsSameAsNoFilter() throws IOException {
        String json = """
            {
                "name": "Test Chat",
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Test"}
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        TelegramExporter exporter = new TelegramExporter();
        
        List<String> resultNoFilter = exporter.processFile(inputFile);
        List<String> resultNullFilter = exporter.processFile(inputFile, null);
        
        assertThat(resultNullFilter).isEqualTo(resultNoFilter);
    }

    @Test
    @DisplayName("processFile с MessageFilter фильтрует по типу сообщения")
    void processFileWithTypeFilter() throws IOException {
        String json = """
            {
                "name": "Test Chat",
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Regular message"},
                    {"id": 2, "type": "service", "date": "2025-06-24T11:00:00", "actor": "User joined"},
                    {"id": 3, "type": "message", "date": "2025-06-24T12:00:00", "text": "Another message"}
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        TelegramExporter exporter = new TelegramExporter();
        MessageFilter filter = new MessageFilter().withIncludeType("message");
        
        List<String> result = exporter.processFile(inputFile, filter);
        
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(line -> line.contains("message"));
    }

    @Test
    @DisplayName("processFile с MessageFilter исключает типы сообщений")
    void processFileExcludesTypes() throws IOException {
        String json = """
            {
                "name": "Test Chat",
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Regular"},
                    {"id": 2, "type": "service", "date": "2025-06-24T11:00:00", "actor": "Joined"},
                    {"id": 3, "type": "message", "date": "2025-06-24T12:00:00", "text": "Another"}
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        TelegramExporter exporter = new TelegramExporter();
        MessageFilter filter = new MessageFilter().withExcludeType("service");
        
        List<String> result = exporter.processFile(inputFile, filter);
        
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("processFile с MessageFilter работает с DI-конструктором")
    void processFileWithFilterUsesDiConstructor() throws IOException {
        String json = """
            {
                "name": "Test",
                "messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Test"}
                ]
            }
            """;
        
        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);
        
        ObjectMapper mapper = new ObjectMapper();
        MessageProcessor processor = new MessageProcessor();
        TelegramExporter exporter = new TelegramExporter(mapper, processor);
        
        MessageFilter filter = new MessageFilter();
        List<String> result = exporter.processFile(inputFile, filter);
        
        assertThat(result).hasSize(1);
    }
}
