package com.tcleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для DateFormatter.
 * 
 * Ожидаемое поведение:
 * - parseDate("2025-06-24T15:29:46") → "20250624"
 * - parseDate("2025-01-01T00:00:00") → "20250101"
 * - parseDate(null) → ""
 * - parseDate("") → ""
 * - parseDate("invalid") → "invalid"
 */
@DisplayName("DateFormatter")
class DateFormatterTest {

    @Nested
    @DisplayName("parseDate() - парсинг только даты (YYYYMMDD)")
    class ParseDateOnly {
        
        @Test
        @DisplayName("Парсит стандартную ISO дату")
        void parsesStandardIsoDate() {
            String result = DateFormatter.parseDate("2025-06-24T15:29:46");
            assertThat(result).isEqualTo("20250624");
        }
        
        @Test
        @DisplayName("Парсит дату с ведущими нулями")
        void parsesDateWithLeadingZeros() {
            assertThat(DateFormatter.parseDate("2025-01-05T09:03:07")).isEqualTo("20250105");
        }
        
        @Test
        @DisplayName("Парсит дату в первый день года")
        void parsesNewYearDate() {
            assertThat(DateFormatter.parseDate("2025-01-01T00:00:00")).isEqualTo("20250101");
        }
        
        @Test
        @DisplayName("Парсит дату в последний день года")
        void parsesEndOfYearDate() {
            assertThat(DateFormatter.parseDate("2025-12-31T23:59:59")).isEqualTo("20251231");
        }
        
        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Возвращает пустую строку для null/empty")
        void returnsEmptyForNullOrEmpty(String input) {
            assertThat(DateFormatter.parseDate(input)).isEmpty();
        }
        
        @Test
        @DisplayName("Возвращает исходную строку для невалидного формата")
        void returnsOriginalForInvalidFormat() {
            assertThat(DateFormatter.parseDate("invalid-date")).isEqualTo("invalid-date");
        }
        
        @Test
        @DisplayName("Возвращает исходную строку для неполной даты")
        void returnsOriginalForPartialDate() {
            assertThat(DateFormatter.parseDate("2025-06")).isEqualTo("2025-06");
        }
    }
    
    @Nested
    @DisplayName("parseDateTime() - парсинг даты и времени (YYYYMMDDHHmm)")
    class ParseDateTime {
        
        @Test
        @DisplayName("Парсит дату и время")
        void parsesDateWithTime() {
            String result = DateFormatter.parseDateTime("2025-06-24T15:29:46");
            assertThat(result).isEqualTo("202506241529");
        }
        
        @Test
        @DisplayName("Парсит дату с полуночи")
        void parsesDateWithMidnight() {
            assertThat(DateFormatter.parseDateTime("2025-06-24T00:00:00")).isEqualTo("202506240000");
        }
        
        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Возвращает пустую строку для null/empty")
        void returnsEmptyForNullOrEmpty(String input) {
            assertThat(DateFormatter.parseDateTime(input)).isEmpty();
        }
    }
}
