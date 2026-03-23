package com.tcleaner.format;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Валидатор URL для защиты от XSS атак.
 *
 * Разрешает только безопасные схемы (http, https, ftp, mailto, tg).
 * Блокирует опасные схемы (javascript, data, vbscript и т.д.).
 */
public class UrlValidator {

    private static final Set<String> SAFE_SCHEMES = Set.of(
            "http",
            "https",
            "ftp",
            "ftps",
            "mailto",
            "tel",
            "tg",      // Telegram links
            "geo",     // Geo links
            "magnet"   // Magnet links
    );

    private static final Set<String> DANGEROUS_SCHEMES = Set.of(
            "javascript",
            "data",
            "vbscript",
            "file",
            "about",
            "blob"
    );

    private UrlValidator() {
    }

    /**
     * Проверяет, является ли URL безопасным для использования в markdown ссылках.
     *
     * @param url URL для проверки
     * @return true если URL безопасен, false если опасен или невалиден
     */
    public static boolean isSafeUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String trimmed = url.trim();

        if (trimmed.equals("#")) {
            return true;
        }

        // Относительные ссылки безопасны
        if (trimmed.startsWith("/") || trimmed.startsWith("./")
                || trimmed.startsWith("../") || trimmed.startsWith("#")) {
            return true;
        }

        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();

            if (scheme == null) {
                return true;
            }

            scheme = scheme.toLowerCase();

            if (DANGEROUS_SCHEMES.contains(scheme)) {
                return false;
            }

            return SAFE_SCHEMES.contains(scheme);

        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Возвращает безопасный URL или дефолтный якорь если URL опасен.
     *
     * @param url проверяемый URL
     * @param defaultUrl дефолтный URL при невалидности
     * @return безопасный URL
     */
    public static String sanitizeUrl(String url, String defaultUrl) {
        return isSafeUrl(url) ? url : defaultUrl;
    }
}
