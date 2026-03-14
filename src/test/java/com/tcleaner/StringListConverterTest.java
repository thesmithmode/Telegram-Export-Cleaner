package com.tcleaner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для StringListConverter.
 */
@DisplayName("StringListConverter")
class StringListConverterTest {

    private StringListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new StringListConverter();
    }

    @Test
    @DisplayName("Одно значение возвращает список из одного элемента")
    void convertSingleValueReturnsListWithOneElement() {
        List<String> result = converter.convert("value1");
        assertThat(result).containsExactly("value1");
    }

    @Test
    @DisplayName("Несколько значений через запятую возвращает список")
    void convertMultipleValuesSeparatedByCommaReturnsList() {
        List<String> result = converter.convert("value1,value2,value3");
        assertThat(result).containsExactly("value1", "value2", "value3");
    }

    @Test
    @DisplayName("Пробелы вокруг значений обрезаются")
    void convertValuesWithSpacesTrimsWhitespace() {
        List<String> result = converter.convert(" value1 , value2 , value3 ");
        assertThat(result).containsExactly("value1", "value2", "value3");
    }

    @Test
    @DisplayName("Пустая строка возвращает пустой список")
    void convertEmptyStringReturnsEmptyList() {
        assertThat(converter.convert("")).isEmpty();
    }

    @Test
    @DisplayName("Строка из пробелов возвращает пустой список")
    void convertBlankStringReturnsEmptyList() {
        assertThat(converter.convert("   ")).isEmpty();
    }

    @Test
    @DisplayName("null возвращает пустой список")
    void convertNullValueReturnsEmptyList() {
        assertThat(converter.convert(null)).isEmpty();
    }

    @Test
    @DisplayName("Пустые части между запятыми фильтруются")
    void convertEmptyBetweenCommasFiltersEmptyParts() {
        List<String> result = converter.convert("value1,,value2");
        assertThat(result).containsExactly("value1", "value2");
    }
}
