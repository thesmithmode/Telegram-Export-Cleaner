package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        JsonNode root = objectMapper.readTree(inputPath.toFile());
        JsonNode messagesNode = root.get("messages");

        if (messagesNode == null || !messagesNode.isArray()) {
            return List.of();
        }

        List<JsonNode> messages = new ArrayList<>();
        messagesNode.forEach(messages::add);

        return messageProcessor.processMessages(messages);
    }

    /**
     * Обрабатывает файл и записывает результат в выходной файл.
     * 
     * @param inputPath путь к файлу result.json
     * @param outputPath путь к выходному файлу
     * @throws IOException при ошибках чтения/записи файла
     */
    public void processFileToFile(Path inputPath, Path outputPath) throws IOException {
        List<String> processed = processFile(inputPath);
        
        StringBuilder sb = new StringBuilder();
        for (String line : processed) {
            sb.append(line).append("\n");
        }
        
        Files.writeString(outputPath, sb.toString());
    }
}
