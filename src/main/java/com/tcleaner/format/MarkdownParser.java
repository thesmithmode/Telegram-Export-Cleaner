package com.tcleaner.format;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public class MarkdownParser {

    private MarkdownParser() {
    }

    public static String parseEntity(JsonNode entity) {
        if (entity == null) {
            return "";
        }

        // Handle plain text nodes (strings in JSON arrays become TextNodes)
        if (entity.isTextual()) {
            return entity.asText();
        }

        String type = entity.path("type").asText("plain");
        String text = entity.path("text").asText();

        return switch (type) {
            case "plain" -> text;
            case "bold" -> "**" + text + "**";
            case "italic" -> "*" + text + "*";
            case "strikethrough" -> "~~" + text + "~~";
            case "code" -> "`" + text + "`";
            case "pre" -> parsePre(entity, text);
            case "link" -> text;
            case "text_link" -> parseTextLink(entity, text);
            case "mention" -> text.startsWith("@") ? text : "@" + text;
            case "mention_name" -> text;
            // В Telegram Desktop export поле text уже содержит # (например "#java") — не дублируем
            case "hashtag" -> text.startsWith("#") ? text : "#" + text;
            // Аналогично для cashtag
            case "cashtag" -> text.startsWith("$") ? text : "$" + text;
            case "email" -> text;
            case "phone" -> text;
            case "spoiler" -> "||" + text + "||";
            case "underline" -> "<u>" + text + "</u>";
            case "blockquote" -> "> " + text;
            case "custom_emoji" -> parseCustomEmoji(entity, text);
            case "bot_command" -> text;
            case "bank_card" -> "[CARD]";
            default -> text;
        };
    }

    private static String parsePre(JsonNode entity, String text) {
        String language = entity.path("language").asText();
        if (language.isEmpty()) {
            return "```\n" + text + "\n```";
        }
        return "```" + language + "\n" + text + "\n```";
    }

    private static String parseTextLink(JsonNode entity, String text) {
        String href = entity.path("href").asText("#");
        // Валидируем URL перед вставкой в markdown
        String safeHref = UrlValidator.sanitizeUrl(href, "#");
        return "[" + text + "](" + safeHref + ")";
    }

    private static String parseCustomEmoji(JsonNode entity, String text) {
        String documentId = entity.path("document_id").asText();
        return "[emoji_" + documentId + "]";
    }

    private static String parseEntityArray(Iterable<JsonNode> entities) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode entity : entities) {
            sb.append(parseEntity(entity));
        }
        return sb.toString();
    }

    public static String parseEntityList(List<JsonNode> entities) {
        if (entities == null || entities.isEmpty()) {
            return "";
        }
        return parseEntityArray(entities);
    }

    public static String parseText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }

        if (node.isTextual()) {
            return node.asText();
        }

        if (node.isArray()) {
            return parseEntityArray(node);
        }

        return "";
    }
}
