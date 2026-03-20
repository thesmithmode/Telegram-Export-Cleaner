package com.tcleaner;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.core.MessageFilter;
import com.tcleaner.cli.Main;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Тесты для {@link Main} — CLI-точки входа.
 *
 * <p><strong>Примечание о System.exit():</strong> сценарии, где CLI вызывает
 * {@code System.exit(1)} (отсутствующий {@code result.json}, невалидные флаги),
 * не тестируются напрямую — {@code System.exit()} завершает JVM Surefire.
 * Такие пути покрыты тестами {@code TelegramExporter}.</p>
 *
 * <p>Запись в выходной файл делегируется в
 * {@link TelegramExporter#processFileToFile(Path, Path, MessageFilter)},
 * который использует {@code Files.write(path, lines)} без промежуточного
 * {@code String.join}. Тесты проверяют корректность итогового файла.</p>
 */
@DisplayName("Main — CLI точка входа")
class MainTest {

    @TempDir
    Path tempDir;

    private static final String MINIMAL_JSON = """
        {
            "messages": [
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Hello"},
                {"id": 2, "type": "message", "date": "2025-07-15T12:00:00", "text": "Universe"}
            ]
        }
        """;

    private Path outputFile;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("result.json"), MINIMAL_JSON);
        outputFile = tempDir.resolve("output.txt");
    }

    // ─── Базовый запуск ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Базовый запуск CLI")
    class BasicRun {

        @Test
        @DisplayName("Запускается без ошибок с валидным input-каталогом")
        void runsWithValidInputDir() {
            assertThatCode(() ->
                Main.main(new String[]{"-i", tempDir.toString(), "-o", outputFile.toString()})
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Создаёт выходной файл")
        void createsOutputFile() {
            Main.main(new String[]{"-i", tempDir.toString(), "-o", outputFile.toString()});
            assertThat(outputFile).exists();
        }

        @Test
        @DisplayName("Выходной файл содержит оба сообщения")
        void outputContainsBothMessages() throws IOException {
            Main.main(new String[]{"-i", tempDir.toString(), "-o", outputFile.toString()});
            String content = Files.readString(outputFile);
            assertThat(content).contains("Hello");
            assertThat(content).contains("Universe");
        }

        @Test
        @DisplayName("Verbose-режим не ломает выполнение")
        void verboseModeWorks() {
            assertThatCode(() ->
                Main.main(new String[]{"-i", tempDir.toString(), "-o", outputFile.toString(), "-v"})
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("--help выводит справку и не создаёт выходной файл")
        void helpFlagPrintsUsage() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream saved = System.out;
            System.setOut(new PrintStream(out));
            try {
                Main.main(new String[]{"--help"});
            } finally {
                System.setOut(saved);
            }
            assertThat(out.toString()).isNotEmpty();
            assertThat(outputFile).doesNotExist();
        }
    }

    // ─── Фильтрация ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Фильтрация через CLI-флаги")
    class CliFilters {

        @Test
        @DisplayName("--start-date исключает сообщения до указанной даты")
        void startDateFilterExcludesOlderMessages() throws IOException {
            Main.main(new String[]{
                "-i", tempDir.toString(), "-o", outputFile.toString(),
                "-s", "2025-07-01"
            });
            String content = Files.readString(outputFile);
            assertThat(content).doesNotContain("Hello");    // 2025-06-24 — раньше фильтра
            assertThat(content).contains("Universe");       // 2025-07-15 — позже
        }

        @Test
        @DisplayName("--end-date исключает сообщения после указанной даты")
        void endDateFilterExcludesNewerMessages() throws IOException {
            Main.main(new String[]{
                "-i", tempDir.toString(), "-o", outputFile.toString(),
                "-e", "2025-06-30"
            });
            String content = Files.readString(outputFile);
            assertThat(content).contains("Hello");
            assertThat(content).doesNotContain("Universe");
        }

        @Test
        @DisplayName("--keyword оставляет только сообщения с ключевым словом")
        void keywordFilterKeepsOnlyMatching() throws IOException {
            Main.main(new String[]{
                "-i", tempDir.toString(), "-o", outputFile.toString(),
                "-k", "hello"
            });
            String content = Files.readString(outputFile);
            assertThat(content).contains("Hello");
            assertThat(content).doesNotContain("Universe");
        }

        @Test
        @DisplayName("--exclude исключает сообщения с ключевым словом")
        void excludeKeywordRemovesMatchingMessages() throws IOException {
            Main.main(new String[]{
                "-i", tempDir.toString(), "-o", outputFile.toString(),
                "-x", "universe"
            });
            String content = Files.readString(outputFile);
            assertThat(content).contains("Hello");
            assertThat(content).doesNotContain("Universe");
        }

        @Test
        @DisplayName("Комбинация --start-date и --keyword: ни одно сообщение не проходит")
        void combinedStartDateAndKeywordYieldsEmpty() throws IOException {
            // Hello: 2025-06-24 — не проходит по дате (до 2025-07-01)
            // Universe: 2025-07-15 — проходит по дате, но не содержит "hello"
            Main.main(new String[]{
                "-i", tempDir.toString(), "-o", outputFile.toString(),
                "-s", "2025-07-01", "-k", "hello"
            });
            String content = Files.readString(outputFile);
            assertThat(content.trim()).isEmpty();
        }
    }

    // ─── Формат вывода ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Формат выходного файла")
    class OutputFormat {

        @Test
        @DisplayName("Каждое сообщение на отдельной строке (Files.write построчно)")
        void eachMessageOnSeparateLine() throws IOException {
            Main.main(new String[]{"-i", tempDir.toString(), "-o", outputFile.toString()});
            // Files.write пишет строки через системный разделитель;
            // strip().split("\n") даёт ровно 2 строки для 2 сообщений
            String[] lines = Files.readString(outputFile).strip().split("\n");
            assertThat(lines).hasSize(2);
        }

        @Test
        @DisplayName("Формат строки: YYYYMMDD пробел текст")
        void lineFormatIsDateSpaceText() throws IOException {
            Main.main(new String[]{"-i", tempDir.toString(), "-o", outputFile.toString()});
            String content = Files.readString(outputFile);
            assertThat(content).containsPattern("\\d{8} .+");
            assertThat(content).contains("20250624 Hello");
            assertThat(content).contains("20250715 Universe");
        }

        @Test
        @DisplayName("Пустой список сообщений даёт пустой файл")
        void emptyMessagesYieldsEmptyFile() throws IOException {
            Files.writeString(tempDir.resolve("result.json"), "{\"messages\": []}");
            Main.main(new String[]{"-i", tempDir.toString(), "-o", outputFile.toString()});
            assertThat(outputFile).exists();
            assertThat(Files.readString(outputFile).trim()).isEmpty();
        }
    }
}
