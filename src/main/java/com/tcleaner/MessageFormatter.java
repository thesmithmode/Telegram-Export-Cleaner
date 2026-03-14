package com.tcleaner;

/**
 * Утилита форматирования одного сообщения в строку вывода.
 *
 * <p>Отвечает только за формат итоговой строки: "YYYYMMDD текст".
 * Отделена от MessageProcessor чтобы формат можно было менять независимо.</p>
 */
public class MessageFormatter {

    private MessageFormatter() {
    }

    /**
     * Форматирует дату и текст в итоговую строку вывода.
     *
     * @param date дата в формате YYYYMMDD
     * @param text текст сообщения (переносы строк уже заменены)
     * @return строка формата "YYYYMMDD текст"
     */
    public static String format(String date, String text) {
        return date + " " + text;
    }

    /**
     * Заменяет переносы строк пробелами — каждое сообщение на одной строке.
     *
     * @param text исходный текст
     * @return текст без переносов строк
     */
    public static String normalizeNewlines(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
    }
}
