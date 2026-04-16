package com.tcleaner.dashboard.service.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юниты для {@link PeriodResolver} — проверяем все именованные периоды
 * и авто-гранулярность для custom-диапазона.
 */
@DisplayName("PeriodResolver")
class PeriodResolverTest {

    private final PeriodResolver resolver = new PeriodResolver();
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    @DisplayName("'day' → from=сегодня-1d, granularity=DAY")
    void dayPeriod() {
        StatsPeriod p = resolver.resolve("day", null, null, UTC);
        LocalDate today = LocalDate.now(UTC);
        assertThat(p.from()).isEqualTo(today.minusDays(1));
        assertThat(p.to()).isEqualTo(today);
        assertThat(p.granularity()).isEqualTo(StatsPeriod.Granularity.DAY);
    }

    @Test
    @DisplayName("'week' → from=сегодня-7d, granularity=DAY")
    void weekPeriod() {
        StatsPeriod p = resolver.resolve("week", null, null, UTC);
        LocalDate today = LocalDate.now(UTC);
        assertThat(p.from()).isEqualTo(today.minusDays(7));
        assertThat(p.granularity()).isEqualTo(StatsPeriod.Granularity.DAY);
    }

    @Test
    @DisplayName("'month' → from=сегодня-30d, granularity=DAY")
    void monthPeriod() {
        StatsPeriod p = resolver.resolve("month", null, null, UTC);
        LocalDate today = LocalDate.now(UTC);
        assertThat(p.from()).isEqualTo(today.minusDays(30));
        assertThat(p.granularity()).isEqualTo(StatsPeriod.Granularity.DAY);
    }

    @Test
    @DisplayName("'year' → from=сегодня-1y, granularity=MONTH")
    void yearPeriod() {
        StatsPeriod p = resolver.resolve("year", null, null, UTC);
        LocalDate today = LocalDate.now(UTC);
        assertThat(p.from()).isEqualTo(today.minusYears(1));
        assertThat(p.granularity()).isEqualTo(StatsPeriod.Granularity.MONTH);
    }

    @Test
    @DisplayName("null/unknown → 'all' с granularity=MONTH")
    void unknownAndNullDefaultToAll() {
        StatsPeriod pNull = resolver.resolve(null, null, null, UTC);
        StatsPeriod pAll = resolver.resolve("all", null, null, UTC);
        assertThat(pNull.granularity()).isEqualTo(StatsPeriod.Granularity.MONTH);
        assertThat(pAll.granularity()).isEqualTo(StatsPeriod.Granularity.MONTH);
    }

    @Test
    @DisplayName("'custom' короткий диапазон (≤31d) → granularity=DAY")
    void customShortRangeIsDay() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 15);
        StatsPeriod p = resolver.resolve("custom", from, to, UTC);
        assertThat(p.from()).isEqualTo(from);
        assertThat(p.to()).isEqualTo(to);
        assertThat(p.granularity()).isEqualTo(StatsPeriod.Granularity.DAY);
    }

    @Test
    @DisplayName("'custom' средний диапазон (32-365d) → granularity=WEEK")
    void customMediumRangeIsWeek() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 4, 15);
        StatsPeriod p = resolver.resolve("custom", from, to, UTC);
        assertThat(p.granularity()).isEqualTo(StatsPeriod.Granularity.WEEK);
    }

    @Test
    @DisplayName("'custom' длинный диапазон (>365d) → granularity=MONTH")
    void customLongRangeIsMonth() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2026, 4, 15);
        StatsPeriod p = resolver.resolve("custom", from, to, UTC);
        assertThat(p.granularity()).isEqualTo(StatsPeriod.Granularity.MONTH);
    }

    @Test
    @DisplayName("'custom' с null-полями → fallback на 30d")
    void customNullFallback() {
        StatsPeriod p = resolver.resolve("custom", null, null, UTC);
        LocalDate today = LocalDate.now(UTC);
        assertThat(p.from()).isEqualTo(today.minusDays(30));
        assertThat(p.to()).isEqualTo(today);
    }

    @Test
    @DisplayName("'custom' to < from → to = from (не падает)")
    void customToBeforeFromClamped() {
        LocalDate from = LocalDate.of(2026, 4, 15);
        LocalDate to = LocalDate.of(2026, 4, 1);
        StatsPeriod p = resolver.resolve("custom", from, to, UTC);
        assertThat(p.to()).isAfterOrEqualTo(p.from());
    }
}
