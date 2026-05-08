package com.tcleaner.dashboard.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IndexMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    // Leading column = started_at — иначе план запроса деградирует на range-фильтре по started_at.
    @ParameterizedTest(name = "{0} — leading column = started_at")
    @ValueSource(strings = {
            "idx_events_overview_covering",
            "idx_events_topchats_covering",
            "idx_events_status_covering"
    })
    void coveringIndexHasStartedAtLeadingColumn(String indexName) {
        List<String> sql = jdbc.queryForList(
                "SELECT sql FROM sqlite_master WHERE type='index' AND name=?",
                String.class,
                indexName);
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).containsPattern("\\(\\s*started_at");
    }

    @Test
    @DisplayName("uk_chats_canonical_topic — UNIQUE-защита от silent collision в dbChatMap")
    void chatsUniqueIndexExists() {
        List<String> sql = jdbc.queryForList(
                "SELECT sql FROM sqlite_master WHERE type='index' AND name=?",
                String.class,
                "uk_chats_canonical_topic");
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("UNIQUE");
    }
}
