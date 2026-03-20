package com.tcleaner.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.tcleaner.storage.FileStorageServiceInterface;

/**
 * Шедулер для очистки папки Export от старых файлов.
 */
@Component
public class StorageCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupScheduler.class);

    private final FileStorageServiceInterface fileStorageService;

    /**
     * Конструктор.
     *
     * @param fileStorageService сервис для работы с файлами
     */
    public StorageCleanupScheduler(FileStorageServiceInterface fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * Очищает папку Export от файлов старше TTL.
     * Запускается с интервалом, указанным в конфиге
     * {@code app.storage.cleanup-interval-ms} (по умолчанию 60000 мс = 1 минута).
     */
    @Scheduled(fixedRateString = "${app.storage.cleanup-interval-ms:60000}")
    public void cleanup() {
        log.info("Запуск очистки папки Export");
        fileStorageService.cleanupExportDirectory();
    }
}
