package com.tcleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты для MessageFilterFactory.
 */
@DisplayName("MessageFilterFactory")
class MessageFilterFactoryTest {

    @Nested
    @DisplayName("Возвращает null при отсутствии параметров")
    class NullWhenNoFilters {

        @Test
        @DisplayName("Все параметры null → null")
        void allNullReturnsNull() {
            assertThat(MessageFilterFactory.build(null, null, null, null)).isNull();
        }

        @Test
        @DisplayName("Все параметры пустые → null")
        void allBlankReturnsNull() {
            assertThat(MessageFilterFactory.build("", "  ", "", "")).isNull();
        }
    }

    @Nested
    @DisplayName("Создаёт фильтр по дате")
    class DateFilters {

        @Test
        @DisplayName("Задаёт startDate")
        void setsStartDate() {
            MessageFilter filter = MessageFilterFactory.build("2025-06-01", null, null, null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Задаёт endDate")
        void setsEndDate() {
            MessageFilter filter = MessageFilterFactory.build(null, "2025-06-30", null, null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Невалидная дата → исключение DateTimeParseException")
        void invalidDateThrows() {
            assertThatThrownBy(() -> MessageFilterFactory.build("not-a-date", null, null, null))
                    .isInstanceOf(java.time.format.DateTimeParseException.class);
        }
    }

    @Nested
    @DisplayName("Создаёт фильтр по ключевым словам")
    class KeywordFilters {

        @Test
        @DisplayName("Одно ключевое слово")
        void singleKeyword() {
            MessageFilter filter = MessageFilterFactory.build(null, null, "hello", null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Несколько ключевых слов через запятую")
        void multipleKeywordsCommaSeparated() {
            MessageFilter filter = MessageFilterFactory.build(null, null, "java,spring", null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Пробелы вокруг ключевых слов обрезаются")
        void keywordsAreTrimmed() {
            MessageFilter filter = MessageFilterFactory.build(null, null, " java , spring ", null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Задаёт excludeKeywords")
        void setsExcludeKeywords() {
            MessageFilter filter = MessageFilterFactory.build(null, null, null, "spam");
            assertThat(filter).isNotNull();
        }
    }

    @Nested
    @DisplayName("Комбинированные параметры")
    class CombinedParams {

        @Test
        @DisplayName("Все параметры заданы — возвращает непустой фильтр")
        void allParamsReturnFilter() {
            MessageFilter filter = MessageFilterFactory.build(
                    "2025-01-01", "2025-12-31", "java", "spam");
            assertThat(filter).isNotNull();
        }
    }

    @Nested
    @DisplayName("Валидация диапазона дат")
    class DateRangeValidation {

        @Test
        @DisplayName("startDate раньше endDate — OK")
        void startBeforeEndIsOk() {
            MessageFilter filter = MessageFilterFactory.build(
                    "2025-01-01", "2025-12-31", null, null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("startDate равен endDate — OK")
        void startEqualsEndIsOk() {
            MessageFilter filter = MessageFilterFactory.build(
                    "2025-06-15", "2025-06-15", null, null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("startDate позже endDate — исключение")
        void startAfterEndThrows() {
            assertThatThrownBy(() -> MessageFilterFactory.build(
                    "2025-12-31", "2025-01-01", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startDate")
                    .hasMessageContaining("endDate");
        }

        @Test
        @DisplayName("Только startDate задан — OK (нечего сравнивать)")
        void onlyStartDateIsOk() {
            MessageFilter filter = MessageFilterFactory.build(
                    "2025-06-01", null, null, null);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Только endDate задан — OK (нечего сравнивать)")
        void onlyEndDateIsOk() {
            MessageFilter filter = MessageFilterFactory.build(
                    null, "2025-06-30", null, null);
            assertThat(filter).isNotNull();
        }
    }
}
