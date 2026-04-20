package com.tcleaner.dashboard.service.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юниты для {@link StatsPeriod#previous()} — проверяем сдвиг периода в прошлое.
 */
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
}
