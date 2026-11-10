package com.tcleaner.dashboard.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackMigrationTest {

    @Test
    void upgradesExistingDatabaseAndPreservesBanAcrossRestart(@TempDir Path temp) throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("dashboard.db");
        migrate(url, "db/changelog/db.changelog-master.sql");
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO bot_users (bot_user_id, first_seen, last_seen) "
                    + "VALUES (42, '2026-11-10T00:00:00Z', '2026-11-10T00:00:00Z')");
        }
        migrate(url, "db/changelog/db.changelog-root.yaml");
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT feedback_blocked FROM bot_users WHERE bot_user_id=42")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isFalse();
            }
            statement.executeUpdate("UPDATE bot_users SET feedback_blocked=TRUE WHERE bot_user_id=42");
        }
        migrate(url, "db/changelog/db.changelog-root.yaml");
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT feedback_blocked FROM bot_users WHERE bot_user_id=42")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).isTrue();
        }
    }

    private void migrate(String url, String changelog) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var resources = new ClassLoaderResourceAccessor()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new Liquibase(changelog, resources, database).update(new Contexts(), new LabelExpression());
        }
    }
}
