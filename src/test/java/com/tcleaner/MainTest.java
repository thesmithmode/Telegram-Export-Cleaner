package com.tcleaner;

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
 * Тесты для Main - CLI точка входа.
 *
 * <p>Примечание: сценарии с System.exit() (отсутствующий result.json,
 * невалидные флаги) не тестируются напрямую — System.exit() убивает
 * всю JVM Surefire независимо от daemon-потоков. Эти пути покрываются
 * интеграционными тестами TelegramExporter.</p>
 */
@DisplayName("Main - CLI точка входа")
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

    @Nested
    @DisplayName("Фильтрация через CLI-флаги")
    class CliFilters {

        @Test
        @DisplayName("--start-date фильтрует сообщения до указанной даты")
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
        @DisplayName("--end-date фильтрует сообщения после указанной даты")
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
        @DisplayName("--keyword оставляет только совпадающие сообщения")
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
            // Hello: 2025-06-24 — не проходит по дате
            // Universe: 2025-07-15 — проходит по дате, но не содержит "hello"
            Main.main(new String[]{
                "-i", tempDir.toString(), "-o", outputFile.toString(),
                "-s", "2025-07-01", "-k", "hello"
            });
            String content = Files.readString(outputFile);
            assertThat(content.trim()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Формат выходного файла")
    class OutputFormat {

        @Test
        @DisplayName("Каждое сообщение на отдельной строке")
        void eachMessageOnSeparateLine() throws IOException {
            Main.main(new String[]{"-i", tempDir.toString(), "-o", outputFile.toString()});
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
    }
}
