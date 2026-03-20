package com.tcleaner.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StorageConfig")
class StorageConfigTest {

    @Nested
    @DisplayName("Инициализация с дефолтными значениями")
    class DefaultValues {

        @Test
        void hasDefaultImportPath() {
            StorageConfig config = new StorageConfig();
            String importPath = config.getImportPath();
            assertThat(importPath).isNotNull().contains("tcleaner/import");
        }

        @Test
        void hasDefaultExportPath() {
            StorageConfig config = new StorageConfig();
            String exportPath = config.getExportPath();
            assertThat(exportPath).isNotNull().contains("tcleaner/export");
        }

        @Test
        void hasDefaultTtlMinutes() {
            StorageConfig config = new StorageConfig();
            assertThat(config.getExportTtlMinutes()).isEqualTo(10);
        }

        @Test
        void hasDefaultCleanupInterval() {
            StorageConfig config = new StorageConfig();
            assertThat(config.getCleanupIntervalMs()).isEqualTo(60000L);
        }
    }

    @Nested
    @DisplayName("Setter/Getter")
    class SettersGetters {

        @Test
        void setAndGetImportPath() {
            StorageConfig config = new StorageConfig();
            String newPath = "/custom/import";
            config.setImportPath(newPath);
            assertThat(config.getImportPath()).isEqualTo(newPath);
        }

        @Test
        void setAndGetExportPath() {
            StorageConfig config = new StorageConfig();
            String newPath = "/custom/export";
            config.setExportPath(newPath);
            assertThat(config.getExportPath()).isEqualTo(newPath);
        }

        @Test
        void setAndGetTtlMinutes() {
            StorageConfig config = new StorageConfig();
            config.setExportTtlMinutes(60);
            assertThat(config.getExportTtlMinutes()).isEqualTo(60);
        }

        @Test
        void setAndGetCleanupInterval() {
            StorageConfig config = new StorageConfig();
            long interval = 120000L;
            config.setCleanupIntervalMs(interval);
            assertThat(config.getCleanupIntervalMs()).isEqualTo(interval);
        }

        @Test
        void setterAcceptsNull() {
            StorageConfig config = new StorageConfig();
            config.setImportPath(null);
            assertThat(config.getImportPath()).isNull();
        }

        @Test
        void setterAcceptsEmptyString() {
            StorageConfig config = new StorageConfig();
            config.setImportPath("");
            assertThat(config.getImportPath()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Конфигурационные сценарии")
    class ConfigurationScenarios {

        @Test
        void independentPathConfiguration() {
            StorageConfig config = new StorageConfig();
            config.setImportPath("/data/import");
            config.setExportPath("/data/export");

            assertThat(config.getImportPath()).isEqualTo("/data/import");
            assertThat(config.getExportPath()).isEqualTo("/data/export");
        }

        @Test
        void ttlAndIntervalIndependent() {
            StorageConfig config = new StorageConfig();
            config.setExportTtlMinutes(30);
            config.setCleanupIntervalMs(90000L);

            assertThat(config.getExportTtlMinutes()).isEqualTo(30);
            assertThat(config.getCleanupIntervalMs()).isEqualTo(90000L);
        }

        @Test
        void multipleInstancesIndependent() {
            StorageConfig config1 = new StorageConfig();
            StorageConfig config2 = new StorageConfig();

            config1.setImportPath("/config1/import");
            config2.setImportPath("/config2/import");

            assertThat(config1.getImportPath()).isEqualTo("/config1/import");
            assertThat(config2.getImportPath()).isEqualTo("/config2/import");
        }

        @Test
        void zeroValuesAreValid() {
            StorageConfig config = new StorageConfig();
            config.setExportTtlMinutes(0);
            config.setCleanupIntervalMs(0L);

            assertThat(config.getExportTtlMinutes()).isZero();
            assertThat(config.getCleanupIntervalMs()).isZero();
        }

        @Test
        void negativeValuesAreAllowed() {
            StorageConfig config = new StorageConfig();
            config.setExportTtlMinutes(-1);
            config.setCleanupIntervalMs(-1L);

            assertThat(config.getExportTtlMinutes()).isNegative();
            assertThat(config.getCleanupIntervalMs()).isNegative();
        }
    }
}
