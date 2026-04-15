package com.tcleaner.dashboard;

import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверяет, что миграция V1 создала индексы и UNIQUE-ограничения,
 * на которые опирается план агрегаций и идемпотентности (см. docs/DASHBOARD.md).
 */
@SpringBootTest
@DisplayName("Dashboard схема — индексы и UNIQUE")
class DashboardSchemaIndexesTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("UNIQUE по export_events.task_id (идемпотентный ключ)")
    void taskIdIsUnique() {
        jdbcTemplate.update("""
                INSERT INTO export_events (task_id, bot_user_id, chat_ref_id, started_at, status, source)
                VALUES ('task-1', 1, 1, '2026-04-15T12:00:00Z', 'queued', 'bot')
                """);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO export_events (task_id, bot_user_id, chat_ref_id, started_at, status, source)
                VALUES ('task-1', 1, 1, '2026-04-15T12:00:01Z', 'queued', 'bot')
                """))
                .hasMessageContaining("UNIQUE");
    }

    @Test
    @DisplayName("UNIQUE по dashboard_users.username")
    void dashboardUsernameIsUnique() {
        jdbcTemplate.update("""
                INSERT INTO dashboard_users (username, password_hash, role, provider, created_at, enabled)
                VALUES ('admin', 'hash', 'ADMIN', 'LOCAL', '2026-04-15T12:00:00Z', 1)
                """);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO dashboard_users (username, password_hash, role, provider, created_at, enabled)
                VALUES ('admin', 'hash2', 'USER', 'LOCAL', '2026-04-15T12:01:00Z', 1)
                """))
                .hasMessageContaining("UNIQUE");
    }

    @Test
    @DisplayName("Индексы для агрегаций по started_at и bot_user_id присутствуют")
    void aggregationIndexesExist() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='export_events'",
                String.class);

        assertThat(indexes).contains(
                "idx_events_user_started",
                "idx_events_chat_started",
                "idx_events_started",
                "idx_events_status"
        );
    }
}
