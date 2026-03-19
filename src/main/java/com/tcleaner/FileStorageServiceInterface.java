package com.tcleaner;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Интерфейс для сервиса хранения файлов.
 * 
 * <p>Абстракция над различными реализациями хранилища:
 * локальная файловая система, S3, Azure Blob и т.д.</p>
 * 
 * <p>Контракт включает:</p>
 * <ul>
 *   <li>{@link #storeFile(Path)} - сохранение файла с диска</li>
 *   <li>{@link #storeFile(Object)} - сохранение multipart файла</li>
 *   <li>{@link #processFile(String)} - синхронная обработка файла</li>
 *   <li>{@link #processFileAsync(String)} - асинхронная обработка файла</li>
 *   <li>{@link #getFile(String)} - получение обработанного файла</li>
 *   <li>{@link #fileExists(String)} - проверка существования файла</li>
 *   <li>{@link #cleanupOldFiles()} - очистка устаревших файлов</li>
 * </ul>
 */
public interface FileStorageServiceInterface {

    /**
     * Сохраняет файл с диска в хранилище.
     *
     * @param sourcePath путь к исходному файлу
     * @return идентификатор сохранённого файла (UUID)
     */
    String storeFile(Path sourcePath);

    /**
     * Сохраняет multipart-файл в хранилище.
     *
     * @param file загружаемый файл (MultipartFile или аналог)
     * @return идентификатор сохранённого файла (UUID)
     */
    String storeFile(Object file);

    /**
     * Обрабатывает файл синхронно.
     *
     * @param fileId идентификатор файла
     * @return результат обработки
     */
    ProcessingResult processFile(String fileId);

    /**
     * Обрабатывает файл асинхронно.
     *
     * @param fileId идентификатор файла
     * @return Future с результатом обработки
     */
    CompletableFuture<ProcessingResult> processFileAsync(String fileId);

    /**
     * Получает обработанный файл.
     *
     * @param fileId идентификатор файла
     * @return путь к файлу
     */
    Path getFile(String fileId);

    /**
     * Проверяет существование обработанного файла.
     *
     * @param fileId идентификатор файла
     * @return true если файл существует
     */
    boolean fileExists(String fileId);

    /**
     * Очищает устаревшие файлы на основе TTL.
     */
    void cleanupOldFiles();
}
