package com.tcleaner.format;

import java.util.regex.Pattern;

public class MessageFormatter {

    private static final Pattern NEWLINE_PATTERN = Pattern.compile("\\r\\n|\\r|\\n");

    private MessageFormatter() {
    }

    public static String format(String date, String text) {
        return date + " " + text;
    }

    public static String normalizeNewlines(String text) {
        return NEWLINE_PATTERN.matcher(text).replaceAll(" ");
    }
}
