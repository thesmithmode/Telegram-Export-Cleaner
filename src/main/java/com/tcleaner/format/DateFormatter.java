package com.tcleaner.format;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class DateFormatter {

    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter OUTPUT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter OUTPUT_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private DateFormatter() {
    }

    
    public static LocalDate parseDateToLocalDate(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return null;
        }

        try {
            LocalDateTime dateTime = LocalDateTime.parse(isoDateTime, INPUT_FORMAT);
            return dateTime.toLocalDate();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    
    public static String parseDate(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return "";
        }

        try {
            LocalDateTime dateTime = LocalDateTime.parse(isoDateTime, INPUT_FORMAT);
            return dateTime.format(OUTPUT_DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            return "";
        }
    }

    
    public static String parseDateTime(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return "";
        }

        try {
            LocalDateTime dateTime = LocalDateTime.parse(isoDateTime, INPUT_FORMAT);
            return dateTime.format(OUTPUT_DATETIME_FORMAT);
        } catch (DateTimeParseException ex) {
            return "";
        }
    }
}
