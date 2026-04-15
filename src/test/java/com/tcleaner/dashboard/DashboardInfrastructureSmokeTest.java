package com.tcleaner.dashboard;

import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тест инфраструктуры дашборда: проверяет, что Spring-контекст
 * поднимается с подключённым SQLite-DataSource, Liquibase применяет changelog
 * и все четыре таблицы статистики доступны для запросов.
 */
@SpringBootTest
@DisplayName("Dashboard инфраструктура — smoke")
class DashboardInfrastructureSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("DataSource бин зарегистрирован")
    void dataSourceIsAvailable() {
        assertThat(dataSource).isNotNull();
    }

    @Test
    @DisplayName("Таблица bot_users создана и пуста")
    void botUsersTableExists() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bot_users", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Таблица chats создана и пуста")
    void chatsTableExists() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chats", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Таблица export_events создана и пуста")
    void exportEventsTableExists() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM export_events", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Таблица dashboard_users создана и пуста")
    void dashboardUsersTableExists() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dashboard_users", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Liquibase применил initial changeset")
    void liquibaseAppliedInitialChangeset() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID = '001-init-dashboard-schema'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
