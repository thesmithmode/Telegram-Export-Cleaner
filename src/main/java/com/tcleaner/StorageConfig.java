package com.tcleaner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация для путей хранения файлов.
 */
@Configuration
@ConfigurationProperties(prefix = "app.storage")
public class StorageConfig {

    /** Путь к папке Import. */
    private String importPath = "/data/import";

    /** Путь к папке Export. */
    private String exportPath = "/data/export";

    /** Время жизни файлов в папке Export (минуты). */
    private int exportTtlMinutes = 10;

    /** Интервал запуска очистки (миллисекунды). */
    private long cleanupIntervalMs = 60000;

    public String getImportPath() {
        return importPath;
    }

    public void setImportPath(String importPath) {
        this.importPath = importPath;
    }

    public String getExportPath() {
        return exportPath;
    }

    public void setExportPath(String exportPath) {
        this.exportPath = exportPath;
    }

    public int getExportTtlMinutes() {
        return exportTtlMinutes;
    }

    public void setExportTtlMinutes(int exportTtlMinutes) {
        this.exportTtlMinutes = exportTtlMinutes;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }
}
