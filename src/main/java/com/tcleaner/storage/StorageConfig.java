package com.tcleaner.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Конфигурация для путей хранения файлов.
 *
 * <p>Значения по умолчанию рассчитаны на локальный запуск.
 * В production переопределяются через {@code app.storage.*} в application.properties
 * или переменные окружения.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app.storage")
@Primary
public class StorageConfig {

    /** Путь к папке Import. */
    private String importPath = System.getProperty("java.io.tmpdir") + "/tcleaner/import";

    /** Путь к папке Export. */
    private String exportPath = System.getProperty("java.io.tmpdir") + "/tcleaner/export";

    /** Время жизни файлов в папке Export (минуты). */
    private int exportTtlMinutes = 10;

    /** Интервал запуска очистки (миллисекунды). */
    private long cleanupIntervalMs = 60000;

    /**
     * Возвращает путь к папке Import.
     *
     * @return путь к папке Import
     */
    public String getImportPath() {
        return importPath;
    }

    /**
     * Задаёт путь к папке Import.
     *
     * @param importPath путь к папке Import
     */
    public void setImportPath(String importPath) {
        this.importPath = importPath;
    }

    /**
     * Возвращает путь к папке Export.
     *
     * @return путь к папке Export
     */
    public String getExportPath() {
        return exportPath;
    }

    /**
     * Задаёт путь к папке Export.
     *
     * @param exportPath путь к папке Export
     */
    public void setExportPath(String exportPath) {
        this.exportPath = exportPath;
    }

    /**
     * Возвращает TTL файлов в папке Export (минуты).
     *
     * @return TTL в минутах
     */
    public int getExportTtlMinutes() {
        return exportTtlMinutes;
    }

    /**
     * Задаёт TTL файлов в папке Export.
     *
     * @param exportTtlMinutes TTL в минутах
     */
    public void setExportTtlMinutes(int exportTtlMinutes) {
        this.exportTtlMinutes = exportTtlMinutes;
    }

    /**
     * Возвращает интервал запуска очистки (миллисекунды).
     *
     * @return интервал в миллисекундах
     */
    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    /**
     * Задаёт интервал запуска очистки.
     *
     * @param cleanupIntervalMs интервал в миллисекундах
     */
    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }
}
