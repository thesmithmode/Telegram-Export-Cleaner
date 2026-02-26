package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Основной класс для экспорта Telegram чата.
 * Читает result.json и преобразует в текстовый формат.
 */
public class TelegramExporter {

    private static final Logger log = LoggerFactory.getLogger(TelegramExporter.class);

    private final ObjectMapper objectMapper;
    private final MessageProcessor messageProcessor;

    public TelegramExporter() {
        this.objectMapper = new ObjectMapper();
        this.messageProcessor = new MessageProcessor();
    }

    /**
     * Обрабатывает файл result.json и возвращает список обработанных сообщений.
     * 
     * @param inputPath путь к файлу result.json
     * @return список обработанных строк
     * @throws IOException при ошибках чтения файла
     */
    public List<String> processFile(Path inputPath) throws IOException {
        log.debug("Начало обработки файла: {}", inputPath);
        
        if (!Files.exists(inputPath)) {
            log.error("Файл не найден: {}", inputPath);
            throw new TelegramExporterException("FILE_NOT_FOUND", "Файл не найден: " + inputPath);
        }
        
        JsonNode root;
        try {
            root = objectMapper.readTree(inputPath.toFile());
        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", e.getMessage());
            throw new TelegramExporterException("INVALID_JSON", "Невалидный JSON: " + e.getMessage(), e);
        }
        
        JsonNode messagesNode = root.get("messages");

        if (messagesNode == null || !messagesNode.isArray()) {
            log.warn("В файле отсутствует массив messages или он имеет неверный формат: {}", inputPath);
            return List.of();
        }

        List<JsonNode> messages = new ArrayList<>();
        messagesNode.forEach(messages::add);
        
        log.debug("Найдено {} сообщений для обработки", messages.size());

        List<String> result = messageProcessor.processMessages(messages);
        log.info("Обработано {} сообщений из файла {}", result.size(), inputPath.getFileName());
        
        return result;
    }

    /**
     * Обрабатывает файл и записывает результат в выходной файл.
     * 
     * @param inputPath путь к файлу result.json
     * @param outputPath путь к выходному файлу
     * @throws IOException при ошибках чтения/записи файла
     */
    public void processFileToFile(Path inputPath, Path outputPath) throws IOException {
        log.debug("Начало обработки: {} -> {}", inputPath, outputPath);
        
        List<String> processed = processFile(inputPath);
        
        StringBuilder sb = new StringBuilder();
        for (String line : processed) {
            sb.append(line).append("\n");
        }
        
        Files.writeString(outputPath, sb.toString());
        log.info("Результат записан в файл: {} ({} строк)", outputPath, processed.size());
    }
}
