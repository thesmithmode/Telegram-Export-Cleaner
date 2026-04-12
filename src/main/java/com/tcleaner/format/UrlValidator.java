package com.tcleaner.format;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

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

    
    public static boolean isSafeUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String trimmed = url.trim();

        if (trimmed.equals("#")) {
            return true;
        }

        // Protocol-relative URL (//evil.com) — в браузере/markdown-рендерере превращается
        // в https://evil.com. Это НЕ относительная ссылка, хотя startsWith("/") = true.
        // Явно отклоняем до проверки относительных путей.
        if (trimmed.startsWith("//")) {
            return false;
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

    
    public static String sanitizeUrl(String url, String defaultUrl) {
        return isSafeUrl(url) ? url : defaultUrl;
    }
}
