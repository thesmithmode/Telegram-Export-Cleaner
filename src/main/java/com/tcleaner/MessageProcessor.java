package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Обработчик сообщений Telegram export.
 * 
 * Преобразует сообщения в формат "YYYYMMDDtext".
 */
@Component
public class MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    public MessageProcessor() {
    }

    /**
     * Обрабатывает одно сообщение.
     * 
     * @param message JSON-узел сообщения
     * @return строка формата "YYYYMMDDtext" или null для service-сообщений/пустых сообщений
     */
    public String processMessage(JsonNode message) {
        if (message == null) {
            return null;
        }

        String type = message.has("type") ? message.get("type").asText() : "message";

        if ("service".equals(type)) {
            return null;
        }

        String dateStr = message.has("date") ? message.get("date").asText() : "";
        String date = DateFormatter.parseDate(dateStr);

        if (date.isEmpty()) {
            return null;
        }

        JsonNode textNode = message.get("text");
        String text = MarkdownParser.parseText(textNode);
        
        if (text.isBlank()) {
            return null;
        }

        // Replace newlines with spaces to keep each message on single line
        text = text.replace('\n', ' ').replace('\r', ' ');

        return date + " " + text;
    }

    /**
     * Обрабатывает список сообщений.
     * 
     * @param messages список JSON-узлов сообщений
     * @return список обработанных строк
     */
    public List<String> processMessages(List<JsonNode> messages) {
        List<String> result = new ArrayList<>();
        
        if (messages == null || messages.isEmpty()) {
            log.debug("Получен пустой или null список сообщений");
            return result;
        }

        log.debug("Начало обработки {} сообщений", messages.size());
        int skipped = 0;
        
        for (JsonNode message : messages) {
            String processed = processMessage(message);
            if (processed != null) {
                result.add(processed);
            } else {
                skipped++;
            }
        }

        log.debug("Обработка завершена: обработано={}, пропущено={}", result.size(), skipped);
        return result;
    }
}
