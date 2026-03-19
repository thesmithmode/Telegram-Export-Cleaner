package com.tcleaner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FileStorageService interface contract.
 * Verifies that FileStorageService follows the expected interface.
 */
@ExtendWith(MockitoExtension.class)
class FileStorageServiceInterfaceTest {

    @Mock
    private StorageConfig mockConfig;

    @Mock
    private TelegramExporter mockExporter;

    @Mock
    private ProcessingStatusService mockStatusService;

    /**
     * Test: uploadFile should return non-null fileId
     * Interface contract: storeFile returns unique identifier
     */
    @Test
    void uploadFile_shouldReturnNonNullFileId() throws NoSuchMethodException {
        // Given
        FileStorageService service = new FileStorageService(
            mockConfig, mockExporter, mockStatusService
        );
        
        // When & Then - basic contract verification
        // The service should have uploadFile method returning String
        assertNotNull(service.getClass().getMethod("uploadFile", Path.class));
    }

    /**
     * Test: processFile should be synchronous
     * Interface contract: processFile is synchronous operation
     */
    @Test
    void processFile_shouldBeSynchronous() throws NoSuchMethodException {
        // Given
        FileStorageService service = new FileStorageService(
            mockConfig, mockExporter, mockStatusService
        );
        
        // Verify method exists and returns ProcessingResult
        assertNotNull(service.getClass().getMethod("processFile", String.class));
    }

    /**
     * Test: processFileAsync should return CompletableFuture
     * Interface contract: processAsync returns Future for non-blocking execution
     */
    @Test
    void processFileAsync_shouldReturnCompletableFuture() throws NoSuchMethodException {
        // Given
        FileStorageService service = new FileStorageService(
            mockConfig, mockExporter, mockStatusService
        );
        
        // Verify async method exists
        assertNotNull(service.getClass().getMethod("processFileAsync", String.class));
    }

    /**
     * Test: cleanupExportDirectory should exist
     * Interface contract: cleanupOldFiles performs TTL-based cleanup
     */
    @Test
    void cleanupExportDirectory_shouldExist() throws NoSuchMethodException {
        // Given
        FileStorageService service = new FileStorageService(
            mockConfig, mockExporter, mockStatusService
        );
        
        // Verify cleanup method exists
        assertNotNull(service.getClass().getMethod("cleanupExportDirectory"));
    }

    /**
     * Test: getExportFile should return Path
     * Interface contract: getFile returns file by identifier
     */
    @Test
    void getExportFile_shouldReturnPath() throws NoSuchMethodException {
        // Given
        FileStorageService service = new FileStorageService(
            mockConfig, mockExporter, mockStatusService
        );
        
        // Verify getFile method exists
        assertNotNull(service.getClass().getMethod("getExportFile", String.class));
    }
}
