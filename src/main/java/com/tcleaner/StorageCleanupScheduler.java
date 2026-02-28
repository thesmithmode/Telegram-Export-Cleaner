package com.tcleaner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Шедулер для очистки папки Export от старых файлов.
 */
@Component
public class StorageCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupScheduler.class);

    private final FileStorageService fileStorageService;

    /**
     * Конструктор.
     *
     * @param fileStorageService сервис для работы с файлами
     */
    public StorageCleanupScheduler(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * Очищает папку Export от файлов старше TTL.
     * Запускается каждую минуту.
     */
    @Scheduled(fixedRateString = "${app.storage.cleanup-interval-ms:60000}")
    public void cleanup() {
        log.info("Запуск очистки папки Export");
        fileStorageService.cleanupExportDirectory();
    }
}
