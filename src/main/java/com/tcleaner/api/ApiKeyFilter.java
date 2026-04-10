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
 * Spring Security фильтр для валидации API ключа в заголовке X-API-Key.
 *
 * Проверяет наличие и корректность API ключа для всех защищённых endpoints вида /api/**.
 * Endpoint /api/health исключена из проверки (public health check).
 *
 * Режимы работы:
 * - С ключом (production): возвращает 401 Unauthorized если ключ отсутствует или неверен
 * - Без ключа (development): логирует warning и пропускает все запросы
 *
 * Интегрируется в Spring Security filter chain до {@link UsernamePasswordAuthenticationFilter}.
 *
 * @see org.springframework.web.filter.OncePerRequestFilter
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final String expectedKey;

    /**
     * Конструктор с инъекцией API ключа из конфигурации.
     *
     * @param expectedKey значение из ${api.key} в application.properties
     *                    (пусто или null = режим без аутентификации)
     */
    public ApiKeyFilter(@Value("${api.key:}") String expectedKey) {
        this.expectedKey = expectedKey;
        if (expectedKey == null || expectedKey.isEmpty()) {
            log.warn("API KEY НЕ УСТАНОВЛЕН! Все запросы к /api/** проходят без аутентификации. "
                    + "Установите JAVA_API_KEY в production.");
        }
    }

    /**
     * Фильтрует запрос, проверяя наличие валидного API ключа.
     *
     * Перехватывает запросы к /api/**, проверяет заголовок X-API-Key и сравнивает
     * с ожидаемым ключом. Если ключ не совпадает, возвращает 401 Unauthorized.
     *
     * /api/health исключена из проверки и всегда доступна.
     *
     * @param request HTTP запрос
     * @param response HTTP ответ
     * @param filterChain цепь фильтров
     *
     * @throws ServletException если ошибка в фильтрации
     * @throws IOException если ошибка ввода/вывода
     */
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
                if (!expectedKey.equals(providedKey)) {
                    log.warn("Попытка доступа к API с неверным ключом: path={}, provided={}", path, providedKey);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("Invalid API Key");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
