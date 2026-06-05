package com.tcleaner.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ApiErrorWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ApiErrorWriter() {
    }

    static void writeJson(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        try {
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(new ApiError(code, message)));
        } catch (JsonProcessingException ex) {
            throw new IOException("Failed to serialize API error payload", ex);
        }
    }

    private record ApiError(String code, String message) {}
}
