package com.tcleaner;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Расширенный интерфейс экспортера: запись результата в файл.
 *
 * <p>Используется в CLI ({@link Main}), где нужна запись в файл.
 * Отделён от {@link TelegramExporterInterface} по принципу ISP —
 * контроллер ({@link TelegramController}) не должен знать о файловом выводе.</p>
 *
 * <p>Методы без фильтра делегируют в перегрузку с {@code filter = null}.</p>
 */
public interface TelegramFileExporterInterface extends TelegramExporterInterface {

    /**
     * Обрабатывает файл без фильтрации и записывает результат в выходной файл.
     *
     * <p>Эквивалентно {@link #processFileToFile(Path, Path, MessageFilter)
     * processFileToFile(inputPath, outputPath, null)}.</p>
     *
     * @param inputPath  путь к файлу {@code result.json}
     * @param outputPath путь к выходному файлу (создаётся или перезаписывается)
     * @throws IOException при ошибках чтения/записи файла
     */
    void processFileToFile(Path inputPath, Path outputPath) throws IOException;

    /**
     * Обрабатывает файл с опциональной фильтрацией и записывает результат в выходной файл.
     *
     * <p>Запись выполняется построчно через {@code Files.write(path, lines, UTF-8)}
     * без промежуточного {@code String.join}.</p>
     *
     * @param inputPath  путь к файлу {@code result.json}
     * @param outputPath путь к выходному файлу (создаётся или перезаписывается)
     * @param filter     фильтр сообщений; {@code null} — фильтрация не применяется
     * @throws IOException при ошибках чтения/записи файла
     */
    void processFileToFile(Path inputPath, Path outputPath, MessageFilter filter) throws IOException;
}
