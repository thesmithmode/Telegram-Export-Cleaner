package com.tcleaner;

/**
 * Статус обработки файла.
 */
public enum ProcessingStatus {
    /** Файл в очереди на обработку. */
    PENDING,
    /** Файл успешно обработан. */
    COMPLETED,
    /** Ошибка при обработке. */
    FAILED
}
