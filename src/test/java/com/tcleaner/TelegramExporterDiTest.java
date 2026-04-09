package com.tcleaner;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.core.MessageProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для TelegramExporter - проверка DI-конструктора.
 */
@DisplayName("TelegramExporter - DI Constructor")
class TelegramExporterDiTest {

    @TempDir
    Path tempDir;

    private ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Test
    @DisplayName("DI-конструктор корректно инициализирует экспортер")
    void diConstructorWorks() throws Exception {
        String json = """
                {"messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Hello"},
                    {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "text": "World"}
                ]}
                """;
        Path file = tempDir.resolve("chat.json");
        Files.writeString(file, json);

        TelegramExporter diExporter = new TelegramExporter(createMapper(), new MessageProcessor());

        List<String> diResult = diExporter.processFile(file);

        assertThat(diResult).hasSize(2);
        assertThat(diResult.get(0)).contains("Hello");
        assertThat(diResult.get(1)).contains("World");
    }
}
