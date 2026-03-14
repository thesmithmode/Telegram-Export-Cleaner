package com.tcleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Тесты для StorageCleanupScheduler.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageCleanupScheduler")
class StorageCleanupSchedulerTest {

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private StorageCleanupScheduler scheduler;

    @Test
    @DisplayName("cleanup() вызывает cleanupExportDirectory у FileStorageService")
    void cleanupDelegatesToFileStorageService() {
        scheduler.cleanup();

        verify(fileStorageService, times(1)).cleanupExportDirectory();
    }

    @Test
    @DisplayName("cleanup() можно вызвать несколько раз подряд")
    void cleanupCanBeCalledMultipleTimes() {
        scheduler.cleanup();
        scheduler.cleanup();
        scheduler.cleanup();

        verify(fileStorageService, times(3)).cleanupExportDirectory();
    }
}
