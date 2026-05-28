package com.tcleaner.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Защита от дублирующихся мутирующих запросов (двойной клик, сетевой retry).
 * Клиент присылает {@code Idempotency-Key}; повторный запрос с тем же ключом и URI → 409 Conflict.
 * Опционально: если заголовка нет — фильтр пропускает запрос без изменений (backward-compatible).
 * In-flight lock в Redis (SET NX EX 24h), без кэширования body ответа.
 * Redis недоступен → fail-open (пропускаем, логируем), чтобы не падал sunny-path.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyFilter.class);

    static final String HEADER = "Idempotency-Key";
    static final String KEY_PREFIX = "idempotency:";
    static final Duration TTL = Duration.ofHours(24);
    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final StringRedisTemplate redis;

    public IdempotencyKeyFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!MUTATING.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (!VALID_KEY.matcher(key).matches()) {
            log.warn("Отклонён Idempotency-Key: невалидный формат, path={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_idempotency_key\"}");
            return;
        }

        String redisKey = KEY_PREFIX + request.getRequestURI() + ":" + key;
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(redisKey, "1", TTL);
        } catch (DataAccessException ex) {
            log.warn("Redis недоступен для Idempotency-Key check, fail-open: {}", ex.getMessage());
            chain.doFilter(request, response);
            return;
        }

        if (Boolean.FALSE.equals(acquired)) {
            log.info("Дублирующийся запрос отклонён по Idempotency-Key, path={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"duplicate_request\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
