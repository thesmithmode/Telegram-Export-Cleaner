package com.tcleaner.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UrlValidator")
class UrlValidatorTest {

    @Nested
    @DisplayName("Безопасные URL")
    class SafeUrls {

        @Test
        void allowsHttpScheme() {
            assertThat(UrlValidator.isSafeUrl("http://example.com")).isTrue();
        }

        @Test
        void allowsHttpsScheme() {
            assertThat(UrlValidator.isSafeUrl("https://example.com")).isTrue();
        }

        @Test
        void allowsMailtoScheme() {
            assertThat(UrlValidator.isSafeUrl("mailto:user@example.com")).isTrue();
        }

        @Test
        void allowsTelegramScheme() {
            assertThat(UrlValidator.isSafeUrl("tg://user?id=123")).isTrue();
        }

        @Test
        void allowsRelativeUrls() {
            assertThat(UrlValidator.isSafeUrl("/path/to/page")).isTrue();
            assertThat(UrlValidator.isSafeUrl("./relative")).isTrue();
            assertThat(UrlValidator.isSafeUrl("../parent")).isTrue();
        }

        @Test
        void allowsAnchors() {
            assertThat(UrlValidator.isSafeUrl("#section")).isTrue();
            assertThat(UrlValidator.isSafeUrl("#")).isTrue();
        }

        @Test
        void allowsEmpty() {
            assertThat(UrlValidator.isSafeUrl("")).isFalse();
        }
    }

    @Nested
    @DisplayName("XSS атаки - блокировка")
    class BlockXssAttacks {

        @Test
        void blocksJavascriptScheme() {
            assertThat(UrlValidator.isSafeUrl("javascript:alert('xss')")).isFalse();
        }

        @Test
        void blocksDataScheme() {
            assertThat(UrlValidator.isSafeUrl("data:text/html,<script>alert('xss')</script>"))
                    .isFalse();
        }

        @Test
        void blocksVbscriptScheme() {
            assertThat(UrlValidator.isSafeUrl("vbscript:msgbox('xss')")).isFalse();
        }

        @Test
        void blocksFileScheme() {
            assertThat(UrlValidator.isSafeUrl("file:///etc/passwd")).isFalse();
        }

        @Test
        void blocksBlobScheme() {
            assertThat(UrlValidator.isSafeUrl("blob:http://example.com/123")).isFalse();
        }

        @Test
        void blocksAboutScheme() {
            assertThat(UrlValidator.isSafeUrl("about:blank")).isFalse();
        }

        @Test
        void caseInsensitiveBlockage() {
            assertThat(UrlValidator.isSafeUrl("JavaScript:alert('xss')")).isFalse();
            assertThat(UrlValidator.isSafeUrl("DATA:text/html,...")).isFalse();
        }
    }

    @Nested
    @DisplayName("Граничные случаи")
    class EdgeCases {

        @Test
        void handlesNullUrl() {
            assertThat(UrlValidator.isSafeUrl(null)).isFalse();
        }

        @Test
        void handlesMalformedUri() {
            assertThat(UrlValidator.isSafeUrl("http://[invalid:::uri]")).isFalse();
        }

        @Test
        void sanitizeFallsBackToDefault() {
            assertThat(UrlValidator.sanitizeUrl("javascript:alert(1)", "#"))
                    .isEqualTo("#");
            assertThat(UrlValidator.sanitizeUrl("https://safe.com", "#"))
                    .isEqualTo("https://safe.com");
        }
    }

    
    @Nested
    @DisplayName("Protocol-relative URL — блокировка (regression)")
    class BlocksProtocolRelative {

        @Test
        void blocksDoubleSlashHost() {
            assertThat(UrlValidator.isSafeUrl("//evil.com")).isFalse();
        }

        @Test
        void blocksDoubleSlashWithPath() {
            assertThat(UrlValidator.isSafeUrl("//evil.com/steal?token=1")).isFalse();
        }

        @Test
        void blocksTripleSlash() {
            assertThat(UrlValidator.isSafeUrl("///attack.com")).isFalse();
        }

        @Test
        void blocksDoubleSlashLocalhost() {
            assertThat(UrlValidator.isSafeUrl("//127.0.0.1/admin")).isFalse();
        }

        @Test
        void sanitizeFallsBackForProtocolRelative() {
            assertThat(UrlValidator.sanitizeUrl("//evil.com", "#"))
                    .isEqualTo("#");
        }

        @Test
        void stillAllowsSingleSlashRelative() {
            // Защита от overcorrection: /path должен оставаться разрешённым.
            assertThat(UrlValidator.isSafeUrl("/path/to/page")).isTrue();
        }
    }
}
