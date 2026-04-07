package com.tcleaner.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final String expectedKey;

    public ApiKeyFilter(@Value("${api.key:}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Проверяем ключ только для API endpoints, кроме /api/health (если нужно)
        if (path.startsWith("/api/") && !path.equals("/api/health")) {
            if (expectedKey != null && !expectedKey.isEmpty()) {
                String providedKey = request.getHeader("X-API-Key");
                if (!expectedKey.equals(providedKey)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("Invalid API Key");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
