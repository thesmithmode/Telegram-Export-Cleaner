package com.tcleaner.dashboard.service.stats;

import java.time.LocalDate;

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
}
