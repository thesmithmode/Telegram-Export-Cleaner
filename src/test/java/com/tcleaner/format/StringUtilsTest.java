package com.tcleaner.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StringUtils")
class StringUtilsTest {

    @Nested
    @DisplayName("splitCsv()")
    class SplitCsv {

        @Test
        void splitsSingleElement() {
            List<String> result = StringUtils.splitCsv("java");
            assertThat(result).containsExactly("java");
        }

        @Test
        void splitsMultipleElements() {
            List<String> result = StringUtils.splitCsv("java,spring,boot");
            assertThat(result).containsExactly("java", "spring", "boot");
        }

        @Test
        void trimsWhitespace() {
            List<String> result = StringUtils.splitCsv(" java , spring , boot ");
            assertThat(result).containsExactly("java", "spring", "boot");
        }

        @Test
        void skipsEmptyElements() {
            List<String> result = StringUtils.splitCsv("java,,spring");
            assertThat(result).containsExactly("java", "spring");
        }

        @Test
        void skipsWhitespaceOnlyElements() {
            List<String> result = StringUtils.splitCsv("java,  ,spring");
            assertThat(result).containsExactly("java", "spring");
        }

        @Test
        void returnsEmptyListForNull() {
            List<String> result = StringUtils.splitCsv(null);
            assertThat(result).isEmpty();
        }

        @Test
        void returnsEmptyListForBlank() {
            List<String> result = StringUtils.splitCsv("   ");
            assertThat(result).isEmpty();
        }

        @Test
        void returnsEmptyListForEmpty() {
            List<String> result = StringUtils.splitCsv("");
            assertThat(result).isEmpty();
        }

        @Test
        void handlesSingleElementWithWhitespace() {
            List<String> result = StringUtils.splitCsv("  java  ");
            assertThat(result).containsExactly("java");
        }

        @Test
        void neverReturnsNull() {
            List<String> result = StringUtils.splitCsv(null);
            assertThat(result).isNotNull();
            assertThat(result).isEmpty();
        }

        @Test
        void handlesCommaAtStart() {
            List<String> result = StringUtils.splitCsv(",java,spring");
            assertThat(result).containsExactly("java", "spring");
        }

        @Test
        void handlesCommaAtEnd() {
            List<String> result = StringUtils.splitCsv("java,spring,");
            assertThat(result).containsExactly("java", "spring");
        }

        @Test
        void handlesMultipleConsecutiveCommas() {
            List<String> result = StringUtils.splitCsv("java,,,spring");
            assertThat(result).containsExactly("java", "spring");
        }
    }
}
