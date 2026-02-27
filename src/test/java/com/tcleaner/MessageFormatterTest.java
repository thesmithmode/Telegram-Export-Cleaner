package com.tcleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для MessageFormatter.
 */
@DisplayName("MessageFormatter")
class MessageFormatterTest {

    @Test
    @DisplayName("format() объединяет дату и текст через пробел")
    void formatCombinesDateAndText() {
        assertThat(MessageFormatter.format("20250624", "Hello world"))
                .isEqualTo("20250624 Hello world");
    }

    @Test
    @DisplayName("format() работает с пустым текстом")
    void formatWorksWithEmptyText() {
        assertThat(MessageFormatter.format("20250624", ""))
                .isEqualTo("20250624 ");
    }

    @Test
    @DisplayName("normalizeNewlines() заменяет \\n на пробел")
    void normalizeNewlinesReplacesLf() {
        assertThat(MessageFormatter.normalizeNewlines("line1\nline2"))
                .isEqualTo("line1 line2");
    }

    @Test
    @DisplayName("normalizeNewlines() заменяет \\r на пробел")
    void normalizeNewlinesReplacesCr() {
        assertThat(MessageFormatter.normalizeNewlines("line1\rline2"))
                .isEqualTo("line1 line2");
    }

    @Test
    @DisplayName("normalizeNewlines() заменяет множественные переносы")
    void normalizeNewlinesReplacesMultiple() {
        assertThat(MessageFormatter.normalizeNewlines("a\n\nb\r\nc"))
                .isEqualTo("a  b  c");
    }

    @Test
    @DisplayName("normalizeNewlines() не меняет строку без переносов")
    void normalizeNewlinesLeavesNormalTextUnchanged() {
        assertThat(MessageFormatter.normalizeNewlines("no newlines here"))
                .isEqualTo("no newlines here");
    }
}
