package com.tcleaner;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.core.MessageProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    @DisplayName("Дефолтный ObjectMapper содержит JavaTimeModule")
    void defaultObjectMapperHasJavaTimeModule() {
        ObjectMapper mapper = TelegramExporter.createDefaultObjectMapper();
        assertThat(mapper.getRegisteredModuleIds())
                .contains("jackson-datatype-jsr310");
    }

    @Test
    @DisplayName("DI-конструктор и конструктор по умолчанию дают одинаковый результат на одном JSON")
    void diAndDefaultConstructorProduceSameOutput() throws Exception {
        String json = """
                {"messages": [
                    {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Hello"},
                    {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "text": "World"}
                ]}
                """;
        Path file = tempDir.resolve("chat.json");
        Files.writeString(file, json);

        TelegramExporter defaultExporter = new TelegramExporter();
        TelegramExporter diExporter = new TelegramExporter(
                TelegramExporter.createDefaultObjectMapper(), new MessageProcessor());

        List<String> defaultResult = defaultExporter.processFile(file);
        List<String> diResult = diExporter.processFile(file);

        assertThat(diResult).isEqualTo(defaultResult);
        assertThat(diResult).hasSize(2);
        assertThat(diResult.get(0)).contains("Hello");
        assertThat(diResult.get(1)).contains("World");
    }
}
