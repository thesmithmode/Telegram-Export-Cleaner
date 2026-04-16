package com.tcleaner.dashboard.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что пустые/null-значения в конструкторе record-а подменяются
 * на безопасные дефолты — ENV не задан ⇒ код всё равно работает.
 */
@DisplayName("StatsStreamProperties — дефолты")
class StatsStreamPropertiesTest {

    @Test
    @DisplayName("null/пустые строки подменяются на дефолты")
    void nullsFallBackToDefaults() {
        StatsStreamProperties props = new StatsStreamProperties(null, "", "  ", 0, true);

        assertThat(props.key()).isEqualTo("stats:events");
        assertThat(props.group()).isEqualTo("dashboard-writer");
        assertThat(props.consumer()).isEqualTo("java-bot-1");
        assertThat(props.maxlen()).isEqualTo(100_000L);
        assertThat(props.enabled()).isTrue();
    }

    @Test
    @DisplayName("заданные значения не перетираются")
    void explicitValuesPreserved() {
        StatsStreamProperties props = new StatsStreamProperties(
                "custom:stream", "grp", "cons", 42, false);

        assertThat(props.key()).isEqualTo("custom:stream");
        assertThat(props.group()).isEqualTo("grp");
        assertThat(props.consumer()).isEqualTo("cons");
        assertThat(props.maxlen()).isEqualTo(42);
        assertThat(props.enabled()).isFalse();
    }
}
