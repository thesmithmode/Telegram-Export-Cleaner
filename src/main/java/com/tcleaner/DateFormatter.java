package com.tcleaner;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Утилита для форматирования даты из Telegram export.
 * 
 * Формат входных данных: ISO 8601 datetime (например, "2025-06-24T15:29:46")
 * Формат выходных данных: YYYYMMDD или YYYYMMDDHHmm
 */
public class DateFormatter {

    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter OUTPUT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter OUTPUT_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private DateFormatter() {
    }

    /**
     * Парсит ISO дату и возвращает LocalDate.
     * 
     * @param isoDateTime строка в формате ISO 8601 (например, "2025-06-24T15:29:46")
     * @return LocalDate или null если парсинг не удался
     */
    public static LocalDate parseDateToLocalDate(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return null;
        }

        try {
            LocalDateTime dateTime = LocalDateTime.parse(isoDateTime, INPUT_FORMAT);
            return dateTime.toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Парсит ISO дату и возвращает только дату в формате YYYYMMDD.
     * 
     * @param isoDateTime строка в формате ISO 8601 (например, "2025-06-24T15:29:46")
     * @return дата в формате YYYYMMDD, или пустая строка для null/empty/ошибки парсинга
     */
    public static String parseDate(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return "";
        }

        try {
            LocalDateTime dateTime = LocalDateTime.parse(isoDateTime, INPUT_FORMAT);
            return dateTime.format(OUTPUT_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return isoDateTime;
        }
    }

    /**
     * Парсит ISO дату и возвращает дату со временем в формате YYYYMMDDHHmm.
     * 
     * @param isoDateTime строка в формате ISO 8601 (например, "2025-06-24T15:29:46")
     * @return дата и время в формате YYYYMMDDHHmm, или пустая строка для null/empty/ошибки парсинга
     */
    public static String parseDateTime(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return "";
        }

        try {
            LocalDateTime dateTime = LocalDateTime.parse(isoDateTime, INPUT_FORMAT);
            return dateTime.format(OUTPUT_DATETIME_FORMAT);
        } catch (DateTimeParseException e) {
            return "";
        }
    }
}
