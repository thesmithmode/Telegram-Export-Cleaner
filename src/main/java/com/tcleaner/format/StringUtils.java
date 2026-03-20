package com.tcleaner.format;

import java.util.ArrayList;
import java.util.List;

/**
 * Утилиты для работы со строками.
 */
public final class StringUtils {

    private StringUtils() {
    }

    /**
     * Разбивает CSV-строку на список элементов.
     *
     * <p>Разделитель — запятая. Пробелы вокруг элементов обрезаются.
     * Пустые элементы пропускаются.</p>
     *
     * @param csv строка вида "java,spring,boot" (или null/пустая)
     * @return список непустых элементов, никогда не null
     */
    public static List<String> splitCsv(String csv) {
        List<String> result = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }
}
