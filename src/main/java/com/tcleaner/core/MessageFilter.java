package com.tcleaner.core;

import com.tcleaner.format.DateFormatter;
import com.tcleaner.format.MarkdownParser;
import com.tcleaner.format.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class MessageFilter {

    private LocalDate startDate;
    private LocalDate endDate;
    private final List<String> keywords;
    private final List<String> excludeKeywords;
    private final Set<String> includeTypes;
    private final Set<String> excludeTypes;
    private final List<Predicate<JsonNode>> customPredicates;

    
    public MessageFilter() {
        this.keywords = new ArrayList<>();
        this.excludeKeywords = new ArrayList<>();
        this.includeTypes = new HashSet<>();
        this.excludeTypes = new HashSet<>();
        this.customPredicates = new ArrayList<>();
    }

    
    public static MessageFilter fromParameters(LocalDate startDate, LocalDate endDate,
                                             String keywords, String excludeKeywords) {
        boolean hasFilters = startDate != null
                || endDate != null
                || isPresent(keywords)
                || isPresent(excludeKeywords);

        if (!hasFilters) {
            return null;
        }

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException(
                    "startDate не может быть позже endDate: " + startDate + " > " + endDate);
        }

        MessageFilter filter = new MessageFilter();

        if (startDate != null) {
            filter.withStartDate(startDate);
        }

        if (endDate != null) {
            filter.withEndDate(endDate);
        }

        if (isPresent(keywords)) {
            for (String kw : StringUtils.splitCsv(keywords)) {
                filter.withKeyword(kw);
            }
        }

        if (isPresent(excludeKeywords)) {
            for (String kw : StringUtils.splitCsv(excludeKeywords)) {
                filter.withExcludeKeyword(kw);
            }
        }

        return filter;
    }

    
    public static MessageFilter fromParameters(String startDate, String endDate,
                                             String keywords, String excludeKeywords) {
        LocalDate parsedStart = isPresent(startDate) ? LocalDate.parse(startDate) : null;
        LocalDate parsedEnd = isPresent(endDate) ? LocalDate.parse(endDate) : null;
        return fromParameters(parsedStart, parsedEnd, keywords, excludeKeywords);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    public MessageFilter withStartDate(LocalDate startDate) {
        this.startDate = startDate;
        return this;
    }

    public MessageFilter withEndDate(LocalDate endDate) {
        this.endDate = endDate;
        return this;
    }

    public MessageFilter withKeyword(String keyword) {
        this.keywords.add(keyword.toLowerCase());
        return this;
    }

    public MessageFilter withExcludeKeyword(String keyword) {
        this.excludeKeywords.add(keyword.toLowerCase());
        return this;
    }

    public MessageFilter withIncludeType(String type) {
        this.includeTypes.add(type);
        return this;
    }

    public MessageFilter withExcludeType(String type) {
        this.excludeTypes.add(type);
        return this;
    }

    public MessageFilter withPredicate(Predicate<JsonNode> predicate) {
        this.customPredicates.add(predicate);
        return this;
    }

    public boolean matches(JsonNode message) {
        if (message == null) {
            return false;
        }

        if (!includeTypes.isEmpty() || !excludeTypes.isEmpty()) {
            String type = MessageProcessor.getMessageType(message);

            if (!includeTypes.isEmpty() && !includeTypes.contains(type)) {
                return false;
            }

            if (excludeTypes.contains(type)) {
                return false;
            }
        }

        if (startDate != null || endDate != null) {
            String dateStr = JsonUtils.getText(message, "date");
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
            String text = MarkdownParser.parseText(message.get("text"));
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
