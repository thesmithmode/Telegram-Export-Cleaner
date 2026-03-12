package com.tcleaner;

import java.time.Instant;

/**
 * Результат обработки файла.
 *
 * <p>Создаётся через фабричные методы {@link #success(String)} и {@link #error(String, String)}.
 * Экземпляры неизменяемы после создания.</p>
 */
public class ProcessingResult {

    private final String fileId;
    private final ProcessingStatus status;
    private final String errorMessage;
    private final Instant startedAt;
    private final Instant completedAt;

    private ProcessingResult(String fileId, ProcessingStatus status,
            String errorMessage, Instant completedAt) {
        this.fileId = fileId;
        this.status = status;
        this.errorMessage = errorMessage;
        this.startedAt = Instant.now();
        this.completedAt = completedAt;
    }

    /**
     * Успешный результат обработки.
     *
     * @param fileId ID обработанного файла
     * @return результат со статусом COMPLETED
     */
    public static ProcessingResult success(String fileId) {
        return new ProcessingResult(fileId, ProcessingStatus.COMPLETED, null, Instant.now());
    }

    /**
     * Результат с ошибкой.
     *
     * @param fileId  ID файла
     * @param message описание ошибки
     * @return результат со статусом FAILED
     */
    public static ProcessingResult error(String fileId, String message) {
        return new ProcessingResult(fileId, ProcessingStatus.FAILED, message, Instant.now());
    }

    public String getFileId() {
        return fileId;
    }

    public ProcessingStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
