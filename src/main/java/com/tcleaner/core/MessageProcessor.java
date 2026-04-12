package com.tcleaner.core;

import com.tcleaner.format.DateFormatter;
import com.tcleaner.format.MarkdownParser;
import com.tcleaner.format.MessageFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    public static final String DEFAULT_MESSAGE_TYPE = "message";

    public MessageProcessor() {
    }

    public static String getMessageType(JsonNode message) {
        return message.has("type") ? message.get("type").asText() : DEFAULT_MESSAGE_TYPE;
    }

    public String processMessage(JsonNode message) {
        if (message == null) {
            return null;
        }

        String type = getMessageType(message);

        if ("service".equals(type)) {
            return null;
        }

        String dateStr = JsonUtils.getText(message, "date");
        String date = DateFormatter.parseDate(dateStr);

        if (date.isEmpty()) {
            return null;
        }

        JsonNode textNode = message.get("text");
        String text = MarkdownParser.parseText(textNode);

        if (text.isBlank()) {
            return null;
        }

        text = MessageFormatter.normalizeNewlines(text);
        return MessageFormatter.format(date, text);
    }

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
