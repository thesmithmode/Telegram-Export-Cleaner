package com.tcleaner;

import java.util.Optional;

/**
 * Интерфейс репозитория статусов обработки файлов.
 * 
 * <p>Абстракция над хранилищем статусов:
 * Redis, база данных, in-memory и т.д.</p>
 * 
 * <p>Методы:</p>
 * <ul>
 *   <li>{@link #setStatus(String, ProcessingStatus)} - сохранение статуса</li>
 *   <li>{@link #getStatus(String)} - получение статуса</li>
 *   <li>{@link #deleteStatus(String)} - удаление статуса</li>
 * </ul>
 */
public interface StatusRepository {

    /**
     * Сохраняет статус обработки файла.
     *
     * @param fileId идентификатор файла
     * @param status статус обработки
     */
    void setStatus(String fileId, ProcessingStatus status);

    /**
     * Возвращает статус обработки файла.
     *
     * @param fileId идентификатор файла
     * @return Optional со статусом, или пустой если не найден/истёк
     */
    Optional<ProcessingStatus> getStatus(String fileId);

    /**
     * Удаляет статус из хранилища.
     *
     * @param fileId идентификатор файла
     */
    void deleteStatus(String fileId);
}
