package com.tcleaner.dashboard.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Шлюз против Liquibase checksum drift: ловит ValidationFailedException на dev,
 * чтобы прод не падал на старте после очередного push в changelog.
 *
 * Симулирует прод-старт в две фазы:
 *   1. Накатывает changelog из origin/main на пустую SQLite (= "состояние прода",
 *      все changesets записаны в DATABASECHANGELOG со своими хешами).
 *   2. Накатывает changelog с HEAD ветки на ТУ ЖЕ базу. Liquibase повторно
 *      валидирует хеши уже накатанных changesets; если правка в HEAD сместила
 *      границу checksum-области старого changeset, выкидывает
 *      ValidationFailedException — ровно так же как падает прод.
 *
 * Тест читает оба changelog через `git show <ref>:<path>` (raw blob = LF, CRLF
 * на Windows-working-dir не влияет). При отсутствии git выключается через
 * @EnabledIf, чтобы не ломать сборку в средах без VCS.
 */
@DisplayName("Liquibase drift gate")
@EnabledIf("isGitAvailable")
class LiquibaseDriftGateTest {

    private static final String CHANGELOG_PATH = "src/main/resources/db/changelog/db.changelog-master.sql";
    private static final String CHANGELOG_FILE = "db.changelog-master.sql";

    private static byte[] mainChangelog;
    private static byte[] headChangelog;

    @BeforeAll
    static void fetchChangelogs() throws Exception {
        mainChangelog = gitShow("origin/main:" + CHANGELOG_PATH);
        headChangelog = gitShow("HEAD:" + CHANGELOG_PATH);
    }

    @Test
    @DisplayName("HEAD не пересчитывает хеши уже накатанных changesets из origin/main")
    void noChecksumDriftAgainstMain(@TempDir Path tmp) throws Exception {
        Path mainDir = Files.createDirectory(tmp.resolve("main"));
        Path headDir = Files.createDirectory(tmp.resolve("head"));
        Path dbFile = tmp.resolve("shadow.db");

        Files.write(mainDir.resolve(CHANGELOG_FILE), mainChangelog);
        Files.write(headDir.resolve(CHANGELOG_FILE), headChangelog);

        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        applyChangelog(url, mainDir);

        assertThatCode(() -> applyChangelog(url, headDir))
                .as("HEAD меняет хеши existing changesets — добавь --validCheckSum")
                .doesNotThrowAnyException();
    }

    private static void applyChangelog(String url, Path dir) throws Exception {
        try (Connection conn = DriverManager.getConnection(url);
             DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(dir)) {
            Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            Liquibase lb = new Liquibase(CHANGELOG_FILE, accessor, db);
            lb.update(new Contexts(), new LabelExpression());
        }
    }

    private static byte[] gitShow(String ref) throws Exception {
        Process p = new ProcessBuilder("git", "show", ref).redirectErrorStream(false).start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git show " + ref + " exit=" + code);
        }
        return out;
    }

    static boolean isGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
