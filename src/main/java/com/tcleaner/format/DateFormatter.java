package com.tcleaner.format;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class DateFormatter {

    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter OUTPUT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private DateFormatter() {
    }

    private static LocalDateTime parseToLocalDateTime(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(isoDateTime, INPUT_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    public static LocalDate parseDateToLocalDate(String isoDateTime) {
        LocalDateTime dateTime = parseToLocalDateTime(isoDateTime);
        return dateTime != null ? dateTime.toLocalDate() : null;
    }

    public static String parseDate(String isoDateTime) {
        LocalDateTime dateTime = parseToLocalDateTime(isoDateTime);
        return dateTime != null ? dateTime.format(OUTPUT_DATE_FORMAT) : "";
    }

}
