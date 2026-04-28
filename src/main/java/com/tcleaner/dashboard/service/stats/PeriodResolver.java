package com.tcleaner.dashboard.service.stats;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Преобразует строку периода (query-параметр {@code period}) в {@link StatsPeriod}.
 * <p>
 * Поддерживаемые значения: {@code all}, {@code year}, {@code month}, {@code week},
 * {@code day}, {@code custom} (требует {@code from} + {@code to}).
 * Все даты интерпретируются в переданной {@link ZoneId} (по умолчанию UTC).
 */
@Component
public class PeriodResolver {

    public StatsPeriod resolve(String period, LocalDate customFrom, LocalDate customTo, ZoneId tz) {
        ZoneId zone = tz != null ? tz : ZoneId.of("UTC");
        LocalDate today = LocalDate.now(zone);

        return switch (period == null ? "all" : period.toLowerCase()) {
            case "year" -> new StatsPeriod(
                    today.minusYears(1).plusDays(1), today, StatsPeriod.Granularity.MONTH);
            case "month" -> new StatsPeriod(
                    today.minusDays(29), today, StatsPeriod.Granularity.DAY);
            case "week" -> new StatsPeriod(
                    today.minusDays(6), today, StatsPeriod.Granularity.DAY);
            case "day" -> new StatsPeriod(
                    today, today, StatsPeriod.Granularity.DAY);
            case "custom" -> resolveCustom(customFrom, customTo, today);
            default -> new StatsPeriod(
                    LocalDate.of(2020, 1, 1), today, StatsPeriod.Granularity.MONTH);
        };
    }

    /** Без zone — UTC. Удобно для тестов и JSON API. */
    public StatsPeriod resolve(String period, LocalDate customFrom, LocalDate customTo) {
        return resolve(period, customFrom, customTo, ZoneId.of("UTC"));
    }

    private StatsPeriod resolveCustom(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate f = from != null ? from : today.minusDays(30);
        LocalDate t = to != null ? to : today;
        if (t.isBefore(f)) {
            t = f;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(f, t);
        StatsPeriod.Granularity granularity = days <= 31
                ? StatsPeriod.Granularity.DAY
                : days <= 365 ? StatsPeriod.Granularity.WEEK
                : StatsPeriod.Granularity.MONTH;
        return new StatsPeriod(f, t, granularity);
    }
}
