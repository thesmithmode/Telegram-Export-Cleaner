package com.tcleaner;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Расширенный интерфейс экспортера — запись результата в файл.
 *
 * <p>Используется в CLI ({@link Main}), где нужна запись в файл.
 * Отделён от {@link TelegramExporterInterface} по принципу ISP —
 * контроллер не должен знать о файловом выводе.</p>
 */
public interface TelegramFileExporterInterface extends TelegramExporterInterface {

    /**
     * Обрабатывает файл и записывает результат в выходной файл.
     *
     * @param inputPath  путь к файлу result.json
     * @param outputPath путь к выходному файлу
     * @throws IOException при ошибках чтения/записи файла
     */
    void processFileToFile(Path inputPath, Path outputPath) throws IOException;
}
