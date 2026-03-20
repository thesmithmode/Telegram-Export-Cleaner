package com.tcleaner.status;

import java.util.Optional;

/**
 * Контракт для хранилища статусов обработки файлов.
 *
 * <p>Позволяет заменять реализацию: Redis, база данных, память и т.д.
 * без изменения бизнес-логики.</p>
 *
 * <p>Реализация: {@link ProcessingStatusService}.</p>
 */
public interface StatusRepository {

    /**
     * Сохраняет статус обработки файла с TTL.
     *
     * @param fileId идентификатор файла (UUID)
     * @param status статус обработки
     */
    void setStatus(String fileId, ProcessingStatus status);

    /**
     * Возвращает статус обработки файла.
     *
     * @param fileId идентификатор файла (UUID)
     * @return {@link Optional} со статусом, или пустой если запись истекла / не найдена
     */
    Optional<ProcessingStatus> getStatus(String fileId);

    /**
     * Удаляет статус из хранилища.
     *
     * @param fileId идентификатор файла (UUID)
     */
    void deleteStatus(String fileId);
}
