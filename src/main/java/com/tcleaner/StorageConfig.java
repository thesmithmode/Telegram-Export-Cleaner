package com.tcleaner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация для путей хранения файлов.
 */
@Configuration
@ConfigurationProperties(prefix = "app.storage")
public class StorageConfig {

    /** Путь к папке Import.
     * Для продакшн обязательно задайте APP_STORAGE_IMPORT_PATH (либо app.storage.import-path). */
    private String importPath = System.getProperty("java.io.tmpdir") + "/tcleaner/import";

    /** Путь к папке Export.
     * Для продакшн обязательно задайте APP_STORAGE_EXPORT_PATH (либо app.storage.export-path). */
    private String exportPath = System.getProperty("java.io.tmpdir") + "/tcleaner/export";

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
