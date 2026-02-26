package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Обработчик сообщений Telegram export.
 * 
 * Преобразует сообщения в формат "YYYYMMDDtext".
 */
public class MessageProcessor {

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
            return result;
        }

        for (JsonNode message : messages) {
            String processed = processMessage(message);
            if (processed != null) {
                result.add(processed);
            }
        }

        return result;
    }
}
