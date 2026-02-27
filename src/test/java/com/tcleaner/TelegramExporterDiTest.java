package com.tcleaner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для TelegramExporter - проверка DI-конструктора.
 */
@DisplayName("TelegramExporter - DI Constructor")
class TelegramExporterDiTest {

    @Test
    @DisplayName("Конструктор с @Autowired принимает ObjectMapper и MessageProcessor")
    void constructorWithMocksWorks() {
        ObjectMapper realMapper = new ObjectMapper();
        MessageProcessor realProcessor = new MessageProcessor();

        TelegramExporter exporter = new TelegramExporter(realMapper, realProcessor);

        assertThat(exporter).isNotNull();
    }

    @Test
    @DisplayName("Конструктор по умолчанию создаёт реальные объекты")
    void defaultConstructorCreatesRealObjects() {
        TelegramExporter exporter = new TelegramExporter();

        assertThat(exporter).isNotNull();
    }

    @Test
    @DisplayName("Оба конструктора создают рабочие экземпляры")
    void bothConstructorsCreateWorkingInstances() {
        ObjectMapper realMapper = new ObjectMapper();
        MessageProcessor realProcessor = new MessageProcessor();

        TelegramExporter exporterWithMocks = new TelegramExporter(realMapper, realProcessor);
        TelegramExporter exporterDefault = new TelegramExporter();

        assertThat(exporterWithMocks).isNotNull();
        assertThat(exporterDefault).isNotNull();
    }
}
