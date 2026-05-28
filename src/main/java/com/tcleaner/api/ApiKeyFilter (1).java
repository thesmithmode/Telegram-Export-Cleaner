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
import java.security.NoSuchAlgorithmException;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final byte[] expectedKeyHash;

    public ApiKeyFilter(@Value("${api.key:}") String expectedKey) {
        if (expectedKey == null || expectedKey.isEmpty()) {
            throw new IllegalStateException(
                    "JAVA_API_KEY не установлен. Запуск без аутентификации /api/** запрещён.");
        }
        this.expectedKeyHash = sha256(expectedKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (path.startsWith("/api/") && !path.equals("/api/health")) {
            String providedKey = request.getHeader("X-API-Key");
            if (!isKeyValid(providedKey)) {
                log.warn("Попытка доступа к API с неверным ключом: path={}, header_present={}",
                        path, providedKey != null);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("Invalid API Key");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isKeyValid(String providedKey) {
        if (providedKey == null) {
            return false;
        }
        // Сравниваем SHA-256-хэши фиксированной длины — MessageDigest.isEqual короткозамыкается
        // на разной длине входа, выдавая timing-оракул на длину настоящего ключа.
        return MessageDigest.isEqual(expectedKeyHash, sha256(providedKey));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен в JRE", e);
        }
    }
}
