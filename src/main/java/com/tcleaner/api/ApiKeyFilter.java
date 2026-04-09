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

/**
 * Фильтр для проверки API ключа в заголовке X-API-Key.
 * Если ключ настроен в api.key, он будет проверяться для всех запросов к /api/**.
 */
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
            String providedKey = request.getHeader("X-API-Key");
            
            if (expectedKey == null || expectedKey.isEmpty() || !expectedKey.equals(providedKey)) {
                log.warn("Попытка доступа к API без корректного ключа: path={}, provided={}", path, providedKey);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("Invalid or missing API Key");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
