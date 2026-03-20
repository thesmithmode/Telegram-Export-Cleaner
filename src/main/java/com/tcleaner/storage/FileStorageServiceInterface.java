package com.tcleaner.storage;

import com.tcleaner.status.ProcessingResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Контракт для управления файлами в папках Import и Export.
 *
 * <p>Позволяет заменять реализацию хранилища (файлы, S3, облако и т.д.)
 * без изменения контроллеров и бизнес-логики.</p>
 *
 * <p>Реализация: {@link FileStorageService}.</p>
 */
public interface FileStorageServiceInterface {

    /**
     * Копирует файл с диска в папку Import, присваивая ему UUID-имя.
     *
     * @param sourcePath путь к исходному файлу
     * @return сгенерированный ID файла (UUID v4)
     * @throws IOException при ошибке копирования
     */
    String uploadFile(Path sourcePath) throws IOException;

    /**
     * Сохраняет multipart-файл в папку Import, присваивая ему UUID-имя.
     *
     * @param file загружаемый мультипарт-файл
     * @return сгенерированный ID файла (UUID v4)
     * @throws IOException при ошибке записи на диск
     */
    String uploadFile(MultipartFile file) throws IOException;

    /**
     * Обрабатывает файл из папки Import и записывает результат в папку Export.
     *
     * <p>Входной файл удаляется из Import в любом случае (успех или ошибка).
     * При ошибке частично записанный {@code .md} файл немедленно удаляется из Export.</p>
     *
     * @param fileId ID файла (UUID v4)
     * @return результат обработки: {@link ProcessingResult}
     * @throws IllegalArgumentException если {@code fileId} не соответствует формату UUID v4
     */
    ProcessingResult processFile(String fileId);

    /**
     * Асинхронно обрабатывает файл из папки Import.
     *
     * <p>Выполняется в пуле потоков Spring {@code @Async}. Вызывающий поток
     * освобождается немедленно.</p>
     *
     * @param fileId ID файла (UUID v4)
     * @return {@link CompletableFuture} с результатом обработки
     */
    CompletableFuture<ProcessingResult> processFileAsync(String fileId);

    /**
     * Возвращает путь к готовому файлу из папки Export.
     *
     * @param fileId ID файла (UUID v4)
     * @return путь к существующему {@code .md} файлу
     * @throws IOException если файл не найден в Export
     * @throws IllegalArgumentException если {@code fileId} не соответствует формату UUID v4
     */
    Path getExportFile(String fileId) throws IOException;

    /**
     * Проверяет, существует ли готовый файл в папке Export.
     *
     * @param fileId ID файла (UUID v4)
     * @return {@code true} если файл существует и не был удалён при ошибке обработки
     * @throws IllegalArgumentException если {@code fileId} не соответствует формату UUID v4
     */
    boolean exportFileExists(String fileId);

    /**
     * Запускает очистку папки Export от устаревших файлов.
     *
     * <p>Если очистка уже выполняется, вызов пропускается.</p>
     */
    void cleanupExportDirectory();
}
