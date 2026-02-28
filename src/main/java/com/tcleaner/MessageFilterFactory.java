package com.tcleaner;

import java.time.LocalDate;

/**
 * Фабрика для создания {@link MessageFilter} из параметров командной строки или HTTP-запроса.
 *
 * <p>Устраняет дублирование логики построения фильтра между {@link Main} и
 * {@link TelegramController}. Возвращает {@code null} если ни одно условие
 * фильтрации не задано — это сигнал для вызывающей стороны пропустить
 * фильтрацию целиком.</p>
 */
public final class MessageFilterFactory {

    private MessageFilterFactory() {
    }

    /**
     * Создаёт {@link MessageFilter} из строковых параметров.
     *
     * <p>Параметры {@code keywords} и {@code excludeKeywords} принимаются в виде
     * строки, разделённой запятыми (например {@code "java,spring"}). Пробелы вокруг
     * элементов обрезаются.</p>
     *
     * @param startDate      начальная дата в формате YYYY-MM-DD, или {@code null}
     * @param endDate        конечная дата в формате YYYY-MM-DD, или {@code null}
     * @param keywords       ключевые слова для включения, через запятую, или {@code null}
     * @param excludeKeywords ключевые слова для исключения, через запятую, или {@code null}
     * @return настроенный {@link MessageFilter}, или {@code null} если ни один параметр не задан
     */
    public static MessageFilter build(String startDate, String endDate,
            String keywords, String excludeKeywords) {
        boolean hasFilters = isPresent(startDate)
                || isPresent(endDate)
                || isPresent(keywords)
                || isPresent(excludeKeywords);

        if (!hasFilters) {
            return null;
        }

        MessageFilter filter = new MessageFilter();

        if (isPresent(startDate)) {
            filter.withStartDate(LocalDate.parse(startDate));
        }

        if (isPresent(endDate)) {
            filter.withEndDate(LocalDate.parse(endDate));
        }

        if (isPresent(keywords)) {
            for (String kw : keywords.split(",")) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty()) {
                    filter.withKeyword(trimmed);
                }
            }
        }

        if (isPresent(excludeKeywords)) {
            for (String kw : excludeKeywords.split(",")) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty()) {
                    filter.withExcludeKeyword(trimmed);
                }
            }
        }

        return filter;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
