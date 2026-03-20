package com.tcleaner.format;

import com.beust.jcommander.IStringConverter;

import java.util.List;

/**
 * Конвертер строки в список строк для JCommander.
 *
 * <p>Разделитель — запятая. Пробелы вокруг элементов обрезаются.
 * Используется для параметров {@code --keyword} и {@code --exclude} в CLI.</p>
 */
public class StringListConverter implements IStringConverter<List<String>> {

    /**
     * Конвертирует строку с разделителями-запятыми в список строк.
     *
     * @param value строка вида "java,spring,boot" (или null/пустая)
     * @return список элементов без пробелов, пустые элементы пропускаются
     */
    @Override
    public List<String> convert(String value) {
        return StringUtils.splitCsv(value);
    }
}
