package com.tcleaner.core;

import com.tcleaner.TelegramFileExporterInterface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Основной обработчик Telegram-экспорта: читает {@code result.json} и
 * преобразует сообщения в текстовый формат.
 *
 * <h2>Архитектура</h2>
 * <p>Класс поддерживает два режима инициализации:</p>
 * <ul>
 *   <li><strong>Spring DI</strong> — через {@link #TelegramExporter(ObjectMapper, MessageProcessor)}.
 *       ObjectMapper и MessageProcessor внедряются контейнером.</li>
 *   <li><strong>CLI</strong> — через {@link #TelegramExporter()}. Оба объекта
 *       создаются с теми же настройками, что и Spring-бин.</li>
 * </ul>
 *
 * <h2>Ограничения памяти</h2>
 * <p>Парсинг JSON через Jackson Tree Model ({@code objectMapper.readTree}) загружает
 * всё дерево в память. Для Web API это безопасно — загрузка ограничена
 * {@code spring.servlet.multipart.max-file-size=50MB} в {@code application.properties}.
 * Для CLI ограничений нет: подача файла &gt;100 МБ может вызвать {@code OutOfMemoryError}.</p>
 *
 * <h2>Потокобезопасность</h2>
 * <p>Экземпляр потокобезопасен: {@link ObjectMapper} является thread-safe после
 * конфигурации, {@link MessageProcessor} не хранит состояния.</p>
 */
@Service
public class TelegramExporter implements TelegramFileExporterInterface {

    private static final Logger log = LoggerFactory.getLogger(TelegramExporter.class);

    private final ObjectMapper objectMapper;
    private final MessageProcessor messageProcessor;

    /**
     * Конструктор для Spring DI.
     *
     * @param objectMapper     Jackson ObjectMapper для парсинга JSON (thread-safe)
     * @param messageProcessor процессор сообщений
     */
    @Autowired
    public TelegramExporter(ObjectMapper objectMapper, MessageProcessor messageProcessor) {
        this.objectMapper = objectMapper;
        this.messageProcessor = messageProcessor;
    }

    /**
     * Конструктор для CLI-режима.
     *
     * <p>Создаёт {@link ObjectMapper} с теми же настройками, что и Spring-бин,
     * чтобы поведение в CLI и Web API было идентичным.</p>
     */
    public TelegramExporter() {
        this.objectMapper = createDefaultObjectMapper();
        this.messageProcessor = new MessageProcessor();
    }

    /**
     * Создаёт {@link ObjectMapper} с базовой конфигурацией (поддержка Java 8 Time API).
     *
     * <p>Вынесен в {@code static}-метод для переиспользования в тестах.</p>
     *
     * @return сконфигурированный ObjectMapper
     */
    public static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * Обрабатывает файл {@code result.json} без фильтрации.
     *
     * <p>Эквивалентно вызову {@link #processFile(Path, MessageFilter) processFile(inputPath, null)}.</p>
     *
     * @param inputPath путь к файлу {@code result.json}
     * @return список обработанных строк в формате {@code "YYYYMMDD текст"}
     * @throws IOException               при ошибках чтения файла
     * @throws TelegramExporterException при отсутствии файла или невалидном JSON
     */
    public List<String> processFile(Path inputPath) throws IOException {
        return processFile(inputPath, null);
    }

    /**
     * Обрабатывает файл {@code result.json} с опциональной фильтрацией сообщений.
     *
     * <p>Шаги обработки:</p>
     * <ol>
     *   <li>Проверка существования файла.</li>
     *   <li>Парсинг JSON через Jackson Tree Model.</li>
     *   <li>Извлечение массива {@code "messages"}.</li>
     *   <li>Применение фильтра (если задан).</li>
     *   <li>Делегирование форматирования в {@link MessageProcessor}.</li>
     * </ol>
     *
     * @param inputPath путь к файлу {@code result.json}
     * @param filter    фильтр сообщений; {@code null} — фильтрация не применяется
     * @return список обработанных строк; пустой список если {@code "messages"} отсутствует
     * @throws IOException               при ошибках чтения файла
     * @throws TelegramExporterException с кодом {@code FILE_NOT_FOUND} или {@code INVALID_JSON}
     */
    public List<String> processFile(Path inputPath, MessageFilter filter) throws IOException {
        log.debug("Начало обработки файла: {}", inputPath);

        if (!Files.exists(inputPath)) {
            log.error("Файл не найден: {}", inputPath);
            throw new TelegramExporterException("FILE_NOT_FOUND", "Файл не найден: " + inputPath);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(inputPath.toFile());
        } catch (Exception ex) {
            log.error("Ошибка парсинга JSON: {}", ex.getMessage());
            throw new TelegramExporterException("INVALID_JSON", "Невалидный JSON: " + ex.getMessage(), ex);
        }

        JsonNode messagesNode = root.get("messages");

        if (messagesNode == null || !messagesNode.isArray()) {
            log.warn("В файле отсутствует массив messages или он имеет неверный формат: {}", inputPath);
            return List.of();
        }

        List<JsonNode> messages = new ArrayList<>();
        messagesNode.forEach(messages::add);

        if (filter != null) {
            messages = filter.filter(messages);
        }

        log.debug("Найдено {} сообщений для обработки", messages.size());

        List<String> result = messageProcessor.processMessages(messages);
        log.info("Обработано {} сообщений из файла {}", result.size(), inputPath.getFileName());

        return result;
    }

    /**
     * Обрабатывает файл без фильтрации и записывает результат в выходной файл.
     *
     * <p>Эквивалентно {@link #processFileToFile(Path, Path, MessageFilter)
     * processFileToFile(inputPath, outputPath, null)}.</p>
     *
     * @param inputPath  путь к файлу {@code result.json}
     * @param outputPath путь к выходному файлу
     * @throws IOException               при ошибках чтения/записи
     * @throws TelegramExporterException при отсутствии файла или невалидном JSON
     */
    public void processFileToFile(Path inputPath, Path outputPath) throws IOException {
        processFileToFile(inputPath, outputPath, null);
    }

    /**
     * Обрабатывает файл с опциональной фильтрацией и записывает результат в выходной файл.
     *
     * <p>Запись выполняется построчно через {@code Files.write(path, lines, UTF-8)}
     * без промежуточного {@code String.join} — потребление памяти не удваивается
     * независимо от размера результата.</p>
     *
     * @param inputPath  путь к файлу {@code result.json}
     * @param outputPath путь к выходному файлу (создаётся или перезаписывается)
     * @param filter     фильтр сообщений; {@code null} — фильтрация не применяется
     * @throws IOException               при ошибках чтения/записи
     * @throws TelegramExporterException при отсутствии файла или невалидном JSON
     */
    public void processFileToFile(Path inputPath, Path outputPath, MessageFilter filter)
            throws IOException {
        log.debug("Начало обработки: {} -> {}", inputPath, outputPath);

        List<String> processed = processFile(inputPath, filter);

        Files.write(outputPath, processed, StandardCharsets.UTF_8);
        log.info("Результат записан в файл: {} ({} строк)", outputPath, processed.size());
    }
}
