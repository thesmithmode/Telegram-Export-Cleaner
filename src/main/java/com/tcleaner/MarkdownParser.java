package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Парсер text_entities из Telegram export в Markdown.
 * 
 * Поддерживаемые типы сущностей:
 * - plain → text
 * - bold → **text**
 * - italic → *text*
 * - strikethrough → ~~text~~
 * - code → `text`
 * - pre → ```\ntext\n```
 * - link → text
 * - text_link → [text](href)
 * - mention → @username (если text уже начинается с @, не дублирует символ)
 * - hashtag → #hashtag
 * - email → email
 * - phone → phone
 * - spoiler → ||text||
 * - underline → <u>text</u>
 * - blockquote → > text
 * - custom_emoji → [emoji_ID]
 * - bot_command → /command
 */
public class MarkdownParser {

    private MarkdownParser() {
    }

    /**
     * Парсит одну сущность в Markdown.
     * 
     * @param entity JSON-узел с сущностью
     * @return Markdown-строка
     */
    public static String parseEntity(JsonNode entity) {
        if (entity == null) {
            return "";
        }

        // Handle plain text nodes (strings in JSON arrays become TextNodes)
        if (entity.isTextual()) {
            return entity.asText();
        }

        String type = entity.has("type") ? entity.get("type").asText() : "plain";
        String text = entity.has("text") ? entity.get("text").asText() : "";

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
            case "hashtag" -> "#" + text;
            case "cashtag" -> "$" + text;
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

    /**
     * Парсит сущность pre (code block) с языком или без.
     */
    private static String parsePre(JsonNode entity, String text) {
        String language = entity.has("language") ? entity.get("language").asText() : "";
        if (language.isEmpty()) {
            return "```\n" + text + "\n```";
        }
        return "```" + language + "\n" + text + "\n```";
    }

    /**
     * Парсит сущность text_link.
     */
    private static String parseTextLink(JsonNode entity, String text) {
        String href = entity.has("href") ? entity.get("href").asText() : "#";
        return "[" + text + "](" + href + ")";
    }

    /**
     * Парсит custom_emoji.
     */
    private static String parseCustomEmoji(JsonNode entity, String text) {
        String documentId = entity.has("document_id") ? entity.get("document_id").asText() : "";
        return "[emoji_" + documentId + "]";
    }

    /**
     * Парсит список сущностей в одну Markdown-строку.
     * 
     * @param entities список JSON-узлов
     * @return объединённая Markdown-строка
     */
    public static String parseEntityList(List<JsonNode> entities) {
        if (entities == null || entities.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode entity : entities) {
            sb.append(parseEntity(entity));
        }
        return sb.toString();
    }

    /**
     * Парсит поле text из сообщения.
     * Может быть строкой или массивом (mixed content).
     * 
     * @param textField значение поля text (String, List<JsonNode> или null)
     * @return обработанный текст
     */
    public static String parseText(Object textField) {
        if (textField == null) {
            return "";
        }

        if (textField instanceof String stringText) {
            return stringText;
        }

        if (textField instanceof List<?> listText) {
            StringBuilder sb = new StringBuilder();
            for (Object item : listText) {
                if (item instanceof JsonNode node) {
                    sb.append(parseEntity(node));
                } else if (item instanceof String stringItem) {
                    sb.append(stringItem);
                }
            }
            return sb.toString();
        }

        return "";
    }

    /**
     * Парсит JsonNode, который может быть строкой или массивом.
     * 
     * @param node JSON-узел
     * @return обработанный текст
     */
    public static String parseText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }

        if (node.isTextual()) {
            return node.asText();
        }

        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode element : node) {
                sb.append(parseEntity(element));
            }
            return sb.toString();
        }

        return "";
    }
}
