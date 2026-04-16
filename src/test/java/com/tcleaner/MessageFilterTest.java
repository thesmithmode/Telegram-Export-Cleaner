package com.tcleaner;

import com.tcleaner.core.MessageFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты для MessageFilter.
 */
@DisplayName("MessageFilter")
class MessageFilterTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    private JsonNode msg(String date, String text) throws Exception {
        return objectMapper.readTree(
            String.format("{\"id\":1,\"type\":\"message\",\"date\":\"%s\",\"text\":\"%s\"}", date, text)
        );
    }

    @Nested
    @DisplayName("Метод fromParameters возвращает null при отсутствии параметров")
    class NullWhenNoFilters {

        @Test
        @DisplayName("Все параметры null → null")
        void allNullReturnsNull() {
            assertThat(MessageFilter.fromParameters(null, null, null, null)).isNull();
        }

        @Test
        @DisplayName("Все параметры пустые → null")
        void allBlankReturnsNull() {
            assertThat(MessageFilter.fromParameters("", "  ", "", "")).isNull();
        }
    }

    @Nested
    @DisplayName("Создаёт фильтр по дате")
    class DateFilters {

        @Test
        @DisplayName("startDate — сообщения до даты отсеиваются")
        void setsStartDate() throws Exception {
            MessageFilter filter = MessageFilter.fromParameters("2025-07-01", null, null, null);
            assertThat(filter.matches(msg("2025-06-30T23:59:59", "before"))).isFalse();
            assertThat(filter.matches(msg("2025-07-01T00:00:00", "on start"))).isTrue();
            assertThat(filter.matches(msg("2025-08-01T00:00:00", "after"))).isTrue();
        }

        @Test
        @DisplayName("endDate — сообщения после даты отсеиваются")
        void setsEndDate() throws Exception {
            MessageFilter filter = MessageFilter.fromParameters(null, "2025-06-30", null, null);
            assertThat(filter.matches(msg("2025-06-30T23:59:59", "on end"))).isTrue();
            assertThat(filter.matches(msg("2025-07-01T00:00:00", "after"))).isFalse();
        }

        @Test
        @DisplayName("Невалидная дата → исключение DateTimeParseException")
        void invalidDateThrows() {
            assertThatThrownBy(() -> MessageFilter.fromParameters("not-a-date", null, null, null))
                    .isInstanceOf(java.time.format.DateTimeParseException.class);
        }
    }

    @Nested
    @DisplayName("Создаёт фильтр по ключевым словам")
    class KeywordFilters {

        @Test
        @DisplayName("Одно ключевое слово — проходят совпадающие, отсеиваются несовпадающие")
        void singleKeyword() throws Exception {
            MessageFilter filter = MessageFilter.fromParameters(null, null, "hello", null);
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "Hello world"))).isTrue();
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "Goodbye"))).isFalse();
        }

        @Test
        @DisplayName("Несколько ключевых слов через запятую — совпадение по любому из них (OR)")
        void multipleKeywordsCommaSeparated() throws Exception {
            MessageFilter filter = MessageFilter.fromParameters(null, null, "java,spring", null);
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "java rocks"))).isTrue();
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "spring boot"))).isTrue();
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "python rules"))).isFalse();
        }

        @Test
        @DisplayName("Пробелы вокруг ключевых слов обрезаются — фильтрация работает корректно")
        void keywordsAreTrimmed() throws Exception {
            MessageFilter filter = MessageFilter.fromParameters(null, null, " java , spring ", null);
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "java code"))).isTrue();
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "spring framework"))).isTrue();
        }

        @Test
        @DisplayName("excludeKeywords — сообщения с исключённым словом отсеиваются")
        void setsExcludeKeywords() throws Exception {
            MessageFilter filter = MessageFilter.fromParameters(null, null, null, "spam");
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "buy cheap spam now"))).isFalse();
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "normal message"))).isTrue();
        }
    }

    @Nested
    @DisplayName("Комбинированные параметры")
    class CombinedParams {

        @Test
        @DisplayName("Дата + ключевое слово — оба условия применяются (AND)")
        void dateAndKeywordCombineAsAnd() throws Exception {
            MessageFilter filter = MessageFilter.fromParameters(
                    "2025-06-01", "2025-06-30", "java", null);
            // Дата в диапазоне + keyword совпадает → проходит
            assertThat(filter.matches(msg("2025-06-15T00:00:00", "learning java"))).isTrue();
            // Дата в диапазоне, но keyword не совпадает → не проходит
            assertThat(filter.matches(msg("2025-06-15T00:00:00", "learning python"))).isFalse();
            // Keyword совпадает, но дата вне диапазона → не проходит
            assertThat(filter.matches(msg("2025-07-15T00:00:00", "learning java"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Валидация диапазона дат")
    class DateRangeValidation {

        @Test
        @DisplayName("startDate раньше endDate — OK")
        void startBeforeEndIsOk() {
            MessageFilter filter = MessageFilter.fromParameters(
                    "2025-01-01", "2025-12-31", null, null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("startDate равен endDate — OK")
        void startEqualsEndIsOk() {
            MessageFilter filter = MessageFilter.fromParameters(
                    "2025-06-15", "2025-06-15", null, null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("startDate позже endDate — исключение")
        void startAfterEndThrows() {
            assertThatThrownBy(() -> MessageFilter.fromParameters(
                    "2025-12-31", "2025-01-01", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startDate")
                    .hasMessageContaining("endDate");
        }

        @Test
        @DisplayName("Только startDate задан — OK (нечего сравнивать)")
        void onlyStartDateIsOk() {
            MessageFilter filter = MessageFilter.fromParameters(
                    "2025-06-01", null, null, null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Только endDate задан — OK (нечего сравнивать)")
        void onlyEndDateIsOk() {
            MessageFilter filter = MessageFilter.fromParameters(
                    null, "2025-06-30", null, null);
            assertThat(filter).isNotNull();
        }
    }
}
