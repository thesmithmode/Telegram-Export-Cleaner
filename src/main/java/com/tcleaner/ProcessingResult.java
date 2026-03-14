package com.tcleaner;

import java.time.Instant;

/**
 * Результат обработки файла.
 */
public class ProcessingResult {

    /** ID файла. */
    private String fileId;

    /** Статус обработки. */
    private ProcessingStatus status;

    /** Сообщение об ошибке (если есть). */
    private String errorMessage;

    /** Время начала обработки. */
    private Instant startedAt;

    /** Время окончания обработки. */
    private Instant completedAt;

    public ProcessingResult() {
    }

    public ProcessingResult(String fileId, ProcessingStatus status) {
        this.fileId = fileId;
        this.status = status;
        this.startedAt = Instant.now();
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public ProcessingStatus getStatus() {
        return status;
    }

    public void setStatus(ProcessingStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    /**
     * Успешный результат.
     */
    public static ProcessingResult success(String fileId) {
        ProcessingResult result = new ProcessingResult(fileId, ProcessingStatus.COMPLETED);
        result.setCompletedAt(Instant.now());
        return result;
    }

    /**
     * Результат с ошибкой.
     */
    public static ProcessingResult error(String fileId, String message) {
        ProcessingResult result = new ProcessingResult(fileId, ProcessingStatus.FAILED);
        result.setErrorMessage(message);
        result.setCompletedAt(Instant.now());
        return result;
    }
}
