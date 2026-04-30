package com.tcleaner.dashboard.service.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StatsPeriod")
class StatsPeriodTest {

    @Test
    @DisplayName("previous() семидневного периода сдвигает его на 7 дней назад")
    void previousOfSevenDayPeriodShiftsBackSevenDays() {
        LocalDate from = LocalDate.of(2024, 3, 8);
        LocalDate to = LocalDate.of(2024, 3, 14);
        StatsPeriod period = new StatsPeriod(from, to, StatsPeriod.Granularity.WEEK);

        StatsPeriod prev = period.previous();

        assertThat(prev.from()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(prev.to()).isEqualTo(LocalDate.of(2024, 3, 7));
        assertThat(prev.granularity()).isEqualTo(StatsPeriod.Granularity.WEEK);
    }

    @Test
    @DisplayName("previous() однодневного периода возвращает предыдущий день")
    void previousOfSingleDayPeriod() {
        LocalDate date = LocalDate.of(2024, 3, 15);
        StatsPeriod period = new StatsPeriod(date, date, StatsPeriod.Granularity.DAY);

        StatsPeriod prev = period.previous();

        assertThat(prev.from()).isEqualTo(LocalDate.of(2024, 3, 14));
        assertThat(prev.to()).isEqualTo(LocalDate.of(2024, 3, 14));
        assertThat(prev.granularity()).isEqualTo(StatsPeriod.Granularity.DAY);
    }

    @Test
    @DisplayName("previous() сохраняет гранулярность")
    void previousPreservesGranularity() {
        LocalDate from = LocalDate.of(2024, 3, 1);
        LocalDate to = LocalDate.of(2024, 3, 31);
        StatsPeriod period = new StatsPeriod(from, to, StatsPeriod.Granularity.MONTH);

        StatsPeriod prev = period.previous();

        assertThat(prev.granularity()).isEqualTo(StatsPeriod.Granularity.MONTH);
        assertThat(prev.from()).isEqualTo(LocalDate.of(2024, 1, 30));
        assertThat(prev.to()).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    // ─── allPeriodKeys ────────────────────────────────────────────────────────

    @Test
    @DisplayName("allPeriodKeys DAY: генерирует все дни включительно")
    void allPeriodKeysDayRange() {
        StatsPeriod period = new StatsPeriod(
                LocalDate.of(2026, 4, 8), LocalDate.of(2026, 4, 15),
                StatsPeriod.Granularity.DAY);

        List<String> keys = period.allPeriodKeys();

        assertThat(keys).hasSize(8);
        assertThat(keys).containsExactly(
                "2026-04-08", "2026-04-09", "2026-04-10", "2026-04-11",
                "2026-04-12", "2026-04-13", "2026-04-14", "2026-04-15");
    }

    @Test
    @DisplayName("allPeriodKeys DAY: один день")
    void allPeriodKeysSingleDay() {
        StatsPeriod period = new StatsPeriod(
                LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 10),
                StatsPeriod.Granularity.DAY);

        List<String> keys = period.allPeriodKeys();

        assertThat(keys).containsExactly("2026-04-10");
    }

    @Test
    @DisplayName("allPeriodKeys MONTH: генерирует все месяцы включительно")
    void allPeriodKeysMonthRange() {
        StatsPeriod period = new StatsPeriod(
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 5),
                StatsPeriod.Granularity.MONTH);

        List<String> keys = period.allPeriodKeys();

        assertThat(keys).containsExactly("2026-01", "2026-02", "2026-03", "2026-04");
    }

    @Test
    @DisplayName("allPeriodKeys MONTH: пересечение года")
    void allPeriodKeysMonthCrossYear() {
        StatsPeriod period = new StatsPeriod(
                LocalDate.of(2025, 11, 1), LocalDate.of(2026, 2, 28),
                StatsPeriod.Granularity.MONTH);

        List<String> keys = period.allPeriodKeys();

        assertThat(keys).containsExactly("2025-11", "2025-12", "2026-01", "2026-02");
    }

    @Test
    @DisplayName("allPeriodKeys WEEK: SQLite %W-совместимые ключи для диапазона")
    void allPeriodKeysWeekRange() {
        // 2026-04-08 (среда) .. 2026-04-29 (среда) — 4 недели
        StatsPeriod period = new StatsPeriod(
                LocalDate.of(2026, 4, 8), LocalDate.of(2026, 4, 29),
                StatsPeriod.Granularity.WEEK);

        List<String> keys = period.allPeriodKeys();

        assertThat(keys).hasSize(4);
        // Все ключи формата YYYY-Wnn
        assertThat(keys).allMatch(k -> k.matches("\\d{4}-W\\d{2}"));
        assertThat(keys.get(0)).isLessThan(keys.get(1));
    }
}
