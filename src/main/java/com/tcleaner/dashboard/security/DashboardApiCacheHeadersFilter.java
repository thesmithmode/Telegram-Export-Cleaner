package com.tcleaner.dashboard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Запрещает кэширование ответов {@code /dashboard/api/**}: JSON с персональными данными
 * пользователя (botUserId, username, история экспортов, метрики /me/**). Без этих заголовков
 * shared browser / корпоративный proxy / CDN могут закэшировать ответ user-A и отдать его
 * user-B — утечка PII.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DashboardApiCacheHeadersFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/dashboard/api/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (request.getRequestURI().startsWith(API_PREFIX)) {
            // no-store покрывает no-cache + must-revalidate + max-age=0 для современных
            // клиентов. Pragma/Expires оставлены ради древних HTTP/1.0 прокси.
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
        }

        chain.doFilter(request, response);
    }
}
