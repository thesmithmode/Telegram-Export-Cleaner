package com.tcleaner.bot;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Чистые pure-функции для парсинга пользовательского ввода: t.me-ссылки,
 * @username, topic_id, dd.MM.yyyy даты. Выделено из ExportBot (God class).
 * Без зависимостей и состояния — статический utility-класс.
 */
public final class BotInputParser {

    private static final Pattern TME_LINK_PATTERN =
            Pattern.compile("https?://t\\.me/([a-zA-Z][a-zA-Z0-9_]{3,})(?:/(\\d+))?");

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^@([a-zA-Z][a-zA-Z0-9_]{3,})$");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private BotInputParser() {
        // utility
    }

    /** Извлекает username из t.me-ссылки или @username. {@code null} если не распознано или input null. */
    public static String extractUsername(String input) {
        if (input == null) return null;
        return parseTmeLink(input)[0];
    }

    /** Извлекает topic_id из t.me/.../<id>. {@code null} если отсутствует, некорректен или input null. */
    public static Integer extractTopicId(String input) {
        if (input == null) return null;
        return parseTopicId(parseTmeLink(input)[1]);
    }

    /** Парсит дату из формата dd.MM.yyyy. {@code null} при невалидном вводе или null. */
    public static LocalDate parseDate(String text) {
        if (text == null) return null;
        try {
            return LocalDate.parse(text.trim(), DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    /** Форматтер для отображения дат пользователю. */
    public static DateTimeFormatter dateFormat() {
        return DATE_FORMAT;
    }

    private static Integer parseTopicId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int topicId = Integer.parseInt(raw);
            return topicId > 0 ? topicId : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Один проход regex: [username, rawTopicId]. */
    private static String[] parseTmeLink(String input) {
        Matcher matcher = TME_LINK_PATTERN.matcher(input);
        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        Matcher usernameMatcher = USERNAME_PATTERN.matcher(input);
        if (usernameMatcher.matches()) {
            return new String[]{usernameMatcher.group(1), null};
        }
        return new String[]{null, null};
    }
}
