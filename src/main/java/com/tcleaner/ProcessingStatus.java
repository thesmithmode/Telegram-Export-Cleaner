package com.tcleaner;

/**
 * Статус обработки файла в асинхронном pipeline.
 *
 * <p>Жизненный цикл: {@link #PENDING} → {@link #COMPLETED} (успех)
 * или {@link #PENDING} → {@link #FAILED} (ошибка).</p>
 *
 * <p>Статус хранится в Redis с TTL равным {@code app.storage.export-ttl-minutes}.</p>
 */
public enum ProcessingStatus {
    /** Файл в очереди на обработку. */
    PENDING,
    /** Файл успешно обработан. */
    COMPLETED,
    /** Ошибка при обработке. */
    FAILED
}
