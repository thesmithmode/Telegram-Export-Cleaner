package com.tcleaner.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Utility methods for JSON node operations.
 */
public class JsonUtils {

    private JsonUtils() {
    }

    /**
     * Safely extract text from a JSON node field.
     *
     * @param node the JSON node to extract from
     * @param field the field name
     * @return the field value as text, or empty string if field does not exist or is null
     */
    public static String getText(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : "";
    }
}
