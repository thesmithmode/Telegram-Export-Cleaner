package com.tcleaner.dashboard.service.stats;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

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
