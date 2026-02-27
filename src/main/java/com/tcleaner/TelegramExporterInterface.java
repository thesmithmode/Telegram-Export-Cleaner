package com.tcleaner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Интерфейс для экспортера Telegram чата.
 *
 * <p>Определяет минимальный контракт для обработки файла экспорта Telegram.
 * Используется в {@link TelegramController} — только методы processFile.</p>
 *
 * <p>Для записи результата в файл см. {@link TelegramFileExporterInterface}.</p>
 *
 * @see TelegramExporter
 */
public interface TelegramExporterInterface {

    /**
     * Обрабатывает файл result.json и возвращает список обработанных сообщений.
     *
     * @param inputPath путь к файлу result.json
     * @return список обработанных строк
     * @throws IOException при ошибках чтения файла
     */
    List<String> processFile(Path inputPath) throws IOException;

    /**
     * Обрабатывает файл result.json с фильтрацией сообщений.
     *
     * @param inputPath путь к файлу result.json
     * @param filter    фильтр для сообщений (может быть null)
     * @return список обработанных строк
     * @throws IOException при ошибках чтения файла
     */
    List<String> processFile(Path inputPath, MessageFilter filter) throws IOException;
}
