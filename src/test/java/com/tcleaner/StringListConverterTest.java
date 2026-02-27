package com.tcleaner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для StringListConverter.
 */
public class StringListConverterTest {

    private StringListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new StringListConverter();
    }

    @Test
    void convert_singleValue_returnsListWithOneElement() {
        List<String> result = converter.convert("value1");
        assertEquals(1, result.size());
        assertEquals("value1", result.get(0));
    }

    @Test
    void convert_multipleValuesSeparatedByComma_returnsList() {
        List<String> result = converter.convert("value1,value2,value3");
        assertEquals(3, result.size());
        assertEquals(Arrays.asList("value1", "value2", "value3"), result);
    }

    @Test
    void convert_valuesWithSpaces_trimsWhitespace() {
        List<String> result = converter.convert(" value1 , value2 , value3 ");
        assertEquals(3, result.size());
        assertEquals(Arrays.asList("value1", "value2", "value3"), result);
    }

    @Test
    void convert_emptyString_returnsEmptyList() {
        List<String> result = converter.convert("");
        assertTrue(result.isEmpty());
    }

    @Test
    void convert_blankString_returnsEmptyList() {
        List<String> result = converter.convert("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void convert_nullValue_returnsEmptyList() {
        List<String> result = converter.convert(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void convert_emptyBetweenCommas_filtersEmptyParts() {
        List<String> result = converter.convert("value1,,value2");
        assertEquals(2, result.size());
        assertEquals(Arrays.asList("value1", "value2"), result);
    }
}
