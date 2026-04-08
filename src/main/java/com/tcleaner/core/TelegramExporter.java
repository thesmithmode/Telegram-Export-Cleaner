package com.tcleaner.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
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
 * <h2>Режимы парсинга</h2>
 * <p>Класс предоставляет два режима обработки:</p>
 * <ul>
 *   <li><strong>Tree Model</strong> ({@link #processFile}) — загружает весь JSON в память.
 *       Удобен для небольших файлов и обратной совместимости.</li>
 *   <li><strong>Streaming</strong> ({@link #processFileStreaming}) — читает JSON через
 *       {@link JsonParser} побайтово, записывает результат сразу в {@link Writer}.
 *       Потребление памяти не зависит от размера файла — подходит для файлов любого
 *       размера и высокой конкурентности.</li>
 * </ul>
 *
 * <h2>Потокобезопасность</h2>
 * <p>Экземпляр потокобезопасен: {@link ObjectMapper} является thread-safe после
 * конфигурации, {@link MessageProcessor} не хранит состояния.</p>
 */
@Service
public class TelegramExporter implements TelegramExporterInterface {

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
        validateInputFile(inputPath);

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
     * Обрабатывает файл {@code result.json} через Jackson Streaming API без фильтрации.
     *
     * <p>Эквивалентно {@link #processFileStreaming(Path, MessageFilter, Writer)
     * processFileStreaming(inputPath, null, out)}.</p>
     *
     * @param inputPath путь к файлу {@code result.json}
     * @param out       {@link Writer} для записи результата (строки в формате {@code "YYYYMMDD текст\n"})
     * @return количество записанных строк
     * @throws IOException               при ошибках чтения файла или записи в {@code out}
     * @throws TelegramExporterException с кодом {@code FILE_NOT_FOUND} или {@code INVALID_JSON}
     */
    public int processFileStreaming(Path inputPath, Writer out) throws IOException {
        return processFileStreaming(inputPath, null, out);
    }

    /**
     * Обрабатывает файл {@code result.json} через Jackson Streaming API с опциональной фильтрацией.
     *
     * <p>Читает массив {@code "messages"} по одному элементу, не загружая весь JSON в память.
     * Каждое сообщение после фильтрации и форматирования немедленно записывается в {@code out}.
     * Пиковое потребление памяти определяется размером одного сообщения, а не всего файла.</p>
     *
     * <p>Пример использования:</p>
     * <pre>{@code
     * try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
     *     int count = exporter.processFileStreaming(inputPath, filter, writer);
     * }
     * }</pre>
     *
     * @param inputPath путь к файлу {@code result.json}
     * @param filter    фильтр сообщений; {@code null} — фильтрация не применяется
     * @param out       {@link Writer} для записи результата
     * @return количество записанных строк
     * @throws IOException               при ошибках чтения файла или записи в {@code out}
     * @throws TelegramExporterException с кодом {@code FILE_NOT_FOUND} или {@code INVALID_JSON}
     */
    public int processFileStreaming(Path inputPath, MessageFilter filter, Writer out)
            throws IOException {
        log.debug("Streaming-обработка файла: {}", inputPath);
        validateInputFile(inputPath);

        int written = 0;
        try (JsonParser parser = objectMapper.getFactory().createParser(inputPath.toFile())) {
            // Ищем поле "messages" на верхнем уровне
            if (!advanceToMessagesArray(parser, inputPath)) {
                log.warn("В файле отсутствует массив messages: {}", inputPath);
                return 0;
            }

            // Итерируем элементы массива по одному
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                JsonNode message = objectMapper.readTree(parser);
                if (filter != null && !filter.matches(message)) {
                    continue;
                }
                String line = messageProcessor.processMessage(message);
                if (line != null) {
                    out.write(line);
                    out.write('\n');
                    written++;
                }
            }
        } catch (TelegramExporterException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Ошибка парсинга JSON (streaming): {}", ex.getMessage());
            throw new TelegramExporterException("INVALID_JSON", "Невалидный JSON: " + ex.getMessage(), ex);
        }

        log.info("Streaming: записано {} строк из файла {}", written, inputPath.getFileName());
        return written;
    }

    /**
     * Продвигает {@link JsonParser} до начала массива {@code "messages"}.
     *
     * @param parser    активный {@link JsonParser}
     * @param inputPath путь к файлу (для логирования)
     * @return {@code true} если массив найден и парсер стоит на {@link JsonToken#START_ARRAY}
     * @throws IOException при ошибках чтения
     */
    private boolean advanceToMessagesArray(JsonParser parser, Path inputPath) throws IOException {
        while (parser.nextToken() != null) {
            if (JsonToken.FIELD_NAME.equals(parser.currentToken())
                    && "messages".equals(parser.getCurrentName())) {
                JsonToken next = parser.nextToken();
                if (JsonToken.START_ARRAY.equals(next)) {
                    return true;
                }
                log.warn("Поле messages не является массивом в файле: {}", inputPath);
                return false;
            }
        }
        return false;
    }

    /**
     * Проверяет существование входного файла.
     *
     * @param inputPath путь к файлу
     * @throws TelegramExporterException с кодом FILE_NOT_FOUND если файл не существует
     */
    private void validateInputFile(Path inputPath) {
        if (!Files.exists(inputPath)) {
            log.error("Файл не найден: {}", inputPath);
            throw new TelegramExporterException("FILE_NOT_FOUND", "Файл не найден: " + inputPath);
        }
    }

}
