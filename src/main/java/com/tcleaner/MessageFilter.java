package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Фильтр сообщений Telegram export.
 *
 * <p>Поддерживает фильтрацию по дате, ключевым словам, типу сообщения,
 * а также произвольные предикаты через {@link #withPredicate(Predicate)} —
 * что позволяет расширять фильтрацию без изменения класса (OCP).</p>
 */
public class MessageFilter {

    private LocalDate startDate;
    private LocalDate endDate;
    private List<String> keywords;
    private List<String> excludeKeywords;
    private List<String> includeTypes;
    private List<String> excludeTypes;
    private List<Predicate<JsonNode>> customPredicates;

    /**
     * Создаёт пустой фильтр без условий (все сообщения проходят).
     */
    public MessageFilter() {
        this.keywords = new ArrayList<>();
        this.excludeKeywords = new ArrayList<>();
        this.includeTypes = new ArrayList<>();
        this.excludeTypes = new ArrayList<>();
        this.customPredicates = new ArrayList<>();
    }

    /**
     * Устанавливает начальную дату диапазона фильтрации.
     *
     * @param startDate нижняя граница диапазона дат (включительно)
     * @return this для цепочки вызовов
     */
    public MessageFilter withStartDate(LocalDate startDate) {
        this.startDate = startDate;
        return this;
    }

    /**
     * Устанавливает конечную дату диапазона фильтрации.
     *
     * @param endDate верхняя граница диапазона дат (включительно)
     * @return this для цепочки вызовов
     */
    public MessageFilter withEndDate(LocalDate endDate) {
        this.endDate = endDate;
        return this;
    }

    /**
     * Добавляет ключевое слово для включения: сообщение проходит фильтр только если
     * содержит хотя бы одно из заданных ключевых слов (case-insensitive).
     *
     * @param keyword ключевое слово (регистр игнорируется)
     * @return this для цепочки вызовов
     */
    public MessageFilter withKeyword(String keyword) {
        this.keywords.add(keyword.toLowerCase());
        return this;
    }

    /**
     * Добавляет ключевое слово для исключения: сообщение отклоняется если
     * содержит хотя бы одно из исключаемых слов (case-insensitive).
     *
     * @param keyword ключевое слово для исключения (регистр игнорируется)
     * @return this для цепочки вызовов
     */
    public MessageFilter withExcludeKeyword(String keyword) {
        this.excludeKeywords.add(keyword.toLowerCase());
        return this;
    }

    /**
     * Добавляет тип сообщения для включения (например {@code "message"}).
     * Если список непустой, пропускаются только сообщения указанных типов.
     *
     * @param type тип сообщения из поля {@code "type"} в JSON
     * @return this для цепочки вызовов
     */
    public MessageFilter withIncludeType(String type) {
        this.includeTypes.add(type);
        return this;
    }

    /**
     * Добавляет тип сообщения для исключения (например {@code "service"}).
     *
     * @param type тип сообщения из поля {@code "type"} в JSON
     * @return this для цепочки вызовов
     */
    public MessageFilter withExcludeType(String type) {
        this.excludeTypes.add(type);
        return this;
    }

    /**
     * Добавляет произвольный предикат для фильтрации сообщений.
     *
     * <p>Позволяет расширять логику фильтрации без изменения класса.
     * Например: фильтр по длине, по автору, по наличию медиа и т.д.</p>
     *
     * @param predicate предикат, возвращающий true если сообщение проходит фильтр
     * @return this для цепочки вызовов
     */
    public MessageFilter withPredicate(Predicate<JsonNode> predicate) {
        this.customPredicates.add(predicate);
        return this;
    }

    /**
     * Проверяет соответствие сообщения всем условиям фильтра.
     *
     * @param message JSON-узел сообщения
     * @return true если сообщение проходит все условия
     */
    public boolean matches(JsonNode message) {
        if (message == null) {
            return false;
        }

        if (!includeTypes.isEmpty() || !excludeTypes.isEmpty()) {
            String type = message.has("type") ? message.get("type").asText() : "message";

            if (!includeTypes.isEmpty() && !includeTypes.contains(type)) {
                return false;
            }

            if (excludeTypes.contains(type)) {
                return false;
            }
        }

        if (startDate != null || endDate != null) {
            String dateStr = message.has("date") ? message.get("date").asText() : "";
            LocalDate messageDate = DateFormatter.parseDateToLocalDate(dateStr);

            if (messageDate == null) {
                return false;
            }

            if (startDate != null && messageDate.isBefore(startDate)) {
                return false;
            }

            if (endDate != null && messageDate.isAfter(endDate)) {
                return false;
            }
        }

        if (!keywords.isEmpty() || !excludeKeywords.isEmpty()) {
            String text = message.has("text") ? MarkdownParser.parseText(message.get("text")) : "";
            String textLower = text.toLowerCase();

            if (!keywords.isEmpty()) {
                boolean hasKeyword = keywords.stream().anyMatch(textLower::contains);
                if (!hasKeyword) {
                    return false;
                }
            }

            if (!excludeKeywords.isEmpty()) {
                boolean hasExcludeKeyword = excludeKeywords.stream().anyMatch(textLower::contains);
                if (hasExcludeKeyword) {
                    return false;
                }
            }
        }

        for (Predicate<JsonNode> predicate : customPredicates) {
            if (!predicate.test(message)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Фильтрует список сообщений.
     *
     * @param messages список JSON-узлов
     * @return отфильтрованный список
     */
    public List<JsonNode> filter(List<JsonNode> messages) {
        List<JsonNode> result = new ArrayList<>();

        if (messages == null || messages.isEmpty()) {
            return result;
        }

        for (JsonNode message : messages) {
            if (matches(message)) {
                result.add(message);
            }
        }

        return result;
    }
}
