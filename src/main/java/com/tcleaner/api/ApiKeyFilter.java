package com.tcleaner.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final String expectedKey;

    public ApiKeyFilter(@Value("${api.key:}") String expectedKey) {
        this.expectedKey = expectedKey;
        if (expectedKey == null || expectedKey.isEmpty()) {
            log.warn("API KEY НЕ УСТАНОВЛЕН! Все запросы к /api/** проходят без аутентификации. "
                    + "Установите JAVA_API_KEY в production.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Проверяем ключ только для API endpoints, кроме /api/health
        if (path.startsWith("/api/") && !path.equals("/api/health")) {
            // Если ключ настроен в application.properties, проверяем его.
            // Если не настроен — пропускаем (режим без аутентификации).
            if (expectedKey != null && !expectedKey.isEmpty()) {
                String providedKey = request.getHeader("X-API-Key");
                if (!isKeyValid(providedKey)) {
                    // Никогда не логируем сам ключ — это полезная нагрузка для атакующего
                    // и попадает в log aggregation / SIEM. Логируем только факт попытки.
                    log.warn("Попытка доступа к API с неверным ключом: path={}, header_present={}",
                            path, providedKey != null);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("Invalid API Key");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isKeyValid(String providedKey) {
        if (providedKey == null) {
            return false;
        }
        byte[] expected = expectedKey.getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedKey.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }
}
