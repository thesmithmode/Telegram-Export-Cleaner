package com.tcleaner.format;

import java.util.ArrayList;
import java.util.List;

public final class StringUtils {

    private StringUtils() {
    }

    
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
