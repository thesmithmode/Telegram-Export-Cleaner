package com.tcleaner.dashboard.service.stats;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Value object: временной диапазон + гранулярность для агрегации.
 * Создаётся {@link PeriodResolver#resolve} по query-параметрам запроса.
 */
public record StatsPeriod(LocalDate from, LocalDate to, Granularity granularity) {

    public enum Granularity {
        DAY,
        WEEK,
        MONTH
    }

    /** Форматная строка SQLite {@code strftime} под текущую гранулярность. */
    public String strftimeFormat() {
        return switch (granularity) {
            case DAY -> "%Y-%m-%d";
            case WEEK -> "%Y-W%W";
            case MONTH -> "%Y-%m";
        };
    }

    /** ISO-строка начала для SQL-сравнения: "YYYY-MM-DD". */
    public String fromSql() { return from.toString(); }

    /** ISO-строка конца для SQL-сравнения: "YYYY-MM-DDT23:59:59Z" (включительно). */
    public String toSql() { return to.toString() + "T23:59:59Z"; }

    /**
     * Все ожидаемые bucket-ключи в диапазоне [from, to] для текущей гранулярности.
     * Формат совпадает с SQLite strftime: DAY="YYYY-MM-DD", MONTH="YYYY-MM", WEEK="YYYY-Wnn".
     * Используется для заполнения пустых периодов нулями.
     */
    public List<String> allPeriodKeys() {
        List<String> keys = new ArrayList<>();
        switch (granularity) {
            case DAY -> {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    keys.add(d.format(fmt));
                }
            }
            case MONTH -> {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
                for (LocalDate d = from.withDayOfMonth(1); !d.isAfter(to); d = d.plusMonths(1)) {
                    keys.add(d.format(fmt));
                }
            }
            case WEEK -> {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusWeeks(1)) {
                    keys.add(sqliteWeekKey(d));
                }
            }
        }
        return keys;
    }

    // SQLite strftime('%Y-W%W', date): неделя 00-53, понедельник — первый день.
    private static String sqliteWeekKey(LocalDate d) {
        LocalDate jan1 = LocalDate.of(d.getYear(), 1, 1);
        int jan1Dow = jan1.getDayOfWeek().getValue(); // 1=Mon..7=Sun
        int daysToFirstMonday = jan1Dow == 1 ? 0 : (8 - jan1Dow);
        int dayOfYear0 = d.getDayOfYear() - 1;
        int week = dayOfYear0 < daysToFirstMonday ? 0 : (dayOfYear0 - daysToFirstMonday) / 7 + 1;
        return String.format("%04d-W%02d", d.getYear(), week);
    }

    /**
     * Возвращает предыдущий период той же длины, расположенный строго перед {@code from}.
     * Пример: период [2024-03-01, 2024-03-31] → [2024-01-30, 2024-02-29].
     * Гранулярность сохраняется.
     */
    public StatsPeriod previous() {
        long days = ChronoUnit.DAYS.between(from, to);
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days);
        return new StatsPeriod(prevFrom, prevTo, granularity);
    }
}
