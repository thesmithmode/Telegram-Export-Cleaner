package com.tcleaner.status;

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
    private final Instant completedAt;

    private ProcessingResult(String fileId, ProcessingStatus status,
            String errorMessage, Instant completedAt) {
        this.fileId = fileId;
        this.status = status;
        this.errorMessage = errorMessage;
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

    /**
     * Возвращает идентификатор файла.
     *
     * @return UUID v4 файла
     */
    public String getFileId() {
        return fileId;
    }

    /**
     * Возвращает статус обработки.
     *
     * @return {@link ProcessingStatus#COMPLETED} или {@link ProcessingStatus#FAILED}
     */
    public ProcessingStatus getStatus() {
        return status;
    }

    /**
     * Возвращает описание ошибки.
     *
     * @return текст ошибки, или {@code null} при успешной обработке
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Возвращает время завершения обработки.
     *
     * <p>Устанавливается в {@code Instant.now()} в момент создания объекта результата
     * через фабричные методы {@link #success(String)} или {@link #error(String, String)}.</p>
     *
     * @return момент создания объекта результата
     */
    public Instant getCompletedAt() {
        return completedAt;
    }
}
