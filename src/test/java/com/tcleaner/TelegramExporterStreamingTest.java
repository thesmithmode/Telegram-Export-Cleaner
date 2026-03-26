package com.tcleaner;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.core.TelegramExporterException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты для {@link TelegramExporter#processFileStreaming} — Jackson Streaming API.
 *
 * <p>Проверяет:</p>
 * <ul>
 *   <li>результат идентичен {@link TelegramExporter#processFile} (Tree Model)</li>
 *   <li>корректную обработку граничных случаев (нет файла, невалидный JSON, нет messages)</li>
 *   <li>работу фильтрации</li>
 *   <li>конкурентную запись нескольких потоков</li>
 * </ul>
 */
@DisplayName("TelegramExporter - Streaming API")
class TelegramExporterStreamingTest {

    @TempDir
    Path tempDir;

    private final TelegramExporter exporter = new TelegramExporter();

    // ─── Корректность результата ──────────────────────────────────────────────

    @Nested
    @DisplayName("Результат идентичен Tree Model")
    class Correctness {

        @Test
        @DisplayName("Одно сообщение — streaming и Tree Model дают одинаковый результат")
        void singleMessage_matchesTreeModel() throws IOException {
            String json = """
                    {"messages": [
                        {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Hello"}
                    ]}
                    """;
            Path file = write(json);

            List<String> treeResult = exporter.processFile(file);
            StringWriter sw = new StringWriter();
            exporter.processFileStreaming(file, sw);

            assertThat(sw.toString()).isEqualTo(String.join("\n", treeResult) + "\n");
        }

        @Test
        @DisplayName("Несколько сообщений — streaming и Tree Model дают одинаковый результат")
        void multipleMessages_matchesTreeModel() throws IOException {
            StringBuilder sb = new StringBuilder("{\"messages\": [");
            for (int i = 0; i < 50; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format(
                        "{\"id\": %d, \"type\": \"message\", \"date\": \"2025-06-%02dT10:00:00\", \"text\": \"Msg %d\"}",
                        i, (i % 28) + 1, i));
            }
            sb.append("]}");
            Path file = write(sb.toString());

            List<String> treeResult = exporter.processFile(file);
            StringWriter sw = new StringWriter();
            int count = exporter.processFileStreaming(file, sw);

            assertThat(count).isEqualTo(treeResult.size());
            assertThat(sw.toString()).isEqualTo(String.join("\n", treeResult) + "\n");
        }

        @Test
        @DisplayName("Сервисные сообщения пропускаются (как в Tree Model)")
        void serviceMessagesSkipped() throws IOException {
            String json = """
                    {"messages": [
                        {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Keep"},
                        {"id": 2, "type": "service", "date": "2025-06-24T11:00:00", "action": "join"},
                        {"id": 3, "type": "message", "date": "2025-06-24T12:00:00", "text": "Also keep"}
                    ]}
                    """;
            Path file = write(json);

            StringWriter sw = new StringWriter();
            int count = exporter.processFileStreaming(file, sw);

            assertThat(count).isEqualTo(2);
            assertThat(sw.toString()).contains("Keep");
            assertThat(sw.toString()).contains("Also keep");
            assertThat(sw.toString()).doesNotContain("join");
        }

        @Test
        @DisplayName("Русский текст и emoji обрабатываются корректно")
        void russianTextAndEmoji() throws IOException {
            String json = """
                    {"messages": [
                        {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Привет мир! 🎉"}
                    ]}
                    """;
            Path file = write(json);

            StringWriter sw = new StringWriter();
            exporter.processFileStreaming(file, sw);

            assertThat(sw.toString()).isEqualTo("20250624 Привет мир! 🎉\n");
        }
    }

    // ─── Граничные условия ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Граничные условия")
    class EdgeCases {

        @Test
        @DisplayName("Файл не найден → TelegramExporterException(FILE_NOT_FOUND)")
        void fileNotFound_throwsException() {
            Path missing = tempDir.resolve("missing.json");
            StringWriter sw = new StringWriter();

            assertThatThrownBy(() -> exporter.processFileStreaming(missing, sw))
                    .isInstanceOf(TelegramExporterException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "FILE_NOT_FOUND");
        }

        @Test
        @DisplayName("Невалидный JSON → TelegramExporterException(INVALID_JSON)")
        void invalidJson_throwsException() throws IOException {
            Path file = write("{ not valid json }");
            StringWriter sw = new StringWriter();

            assertThatThrownBy(() -> exporter.processFileStreaming(file, sw))
                    .isInstanceOf(TelegramExporterException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_JSON");
        }

        @Test
        @DisplayName("Нет поля messages → возвращает 0, Writer пуст")
        void noMessagesField_returnsZero() throws IOException {
            Path file = write("{\"name\": \"Test\", \"type\": \"private\"}");
            StringWriter sw = new StringWriter();

            int count = exporter.processFileStreaming(file, sw);

            assertThat(count).isEqualTo(0);
            assertThat(sw.toString()).isEmpty();
        }

        @Test
        @DisplayName("Пустой массив messages → возвращает 0")
        void emptyMessagesArray_returnsZero() throws IOException {
            Path file = write("{\"messages\": []}");
            StringWriter sw = new StringWriter();

            int count = exporter.processFileStreaming(file, sw);

            assertThat(count).isEqualTo(0);
        }
    }

    // ─── Фильтрация ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Фильтрация")
    class Filtering {

        @Test
        @DisplayName("Фильтр по дате работает идентично Tree Model")
        void dateFilter_matchesTreeModel() throws IOException {
            String json = """
                    {"messages": [
                        {"id": 1, "type": "message", "date": "2025-01-10T10:00:00", "text": "January"},
                        {"id": 2, "type": "message", "date": "2025-06-24T10:00:00", "text": "June"},
                        {"id": 3, "type": "message", "date": "2025-12-31T10:00:00", "text": "December"}
                    ]}
                    """;
            Path file = write(json);
            MessageFilter filter = new MessageFilter()
                    .withStartDate(LocalDate.of(2025, 6, 1))
                    .withEndDate(LocalDate.of(2025, 6, 30));

            List<String> treeResult = exporter.processFile(file, filter);
            StringWriter sw = new StringWriter();
            int count = exporter.processFileStreaming(file, filter, sw);

            assertThat(count).isEqualTo(treeResult.size()).isEqualTo(1);
            assertThat(sw.toString()).contains("June");
            assertThat(sw.toString()).doesNotContain("January");
            assertThat(sw.toString()).doesNotContain("December");
        }

        @Test
        @DisplayName("null фильтр эквивалентен отсутствию фильтра")
        void nullFilter_processesAll() throws IOException {
            String json = """
                    {"messages": [
                        {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "A"},
                        {"id": 2, "type": "message", "date": "2025-06-25T10:00:00", "text": "B"}
                    ]}
                    """;
            Path file = write(json);

            StringWriter sw = new StringWriter();
            int count = exporter.processFileStreaming(file, null, sw);

            assertThat(count).isEqualTo(2);
        }
    }

    // ─── Конкурентность ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Конкурентность")
    class Concurrency {

        @Test
        @DisplayName("10 потоков читают один файл одновременно — результаты идентичны")
        void concurrentRead_sameResult() throws Exception {
            StringBuilder sb = new StringBuilder("{\"messages\": [");
            for (int i = 0; i < 30; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format(
                        "{\"id\": %d, \"type\": \"message\", \"date\": \"2025-06-24T10:00:%02d\", \"text\": \"Msg %d\"}",
                        i, i % 60, i));
            }
            sb.append("]}");
            Path file = write(sb.toString());

            StringWriter ref = new StringWriter();
            exporter.processFileStreaming(file, ref);
            String expected = ref.toString();

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    latch.await();
                    StringWriter sw = new StringWriter();
                    exporter.processFileStreaming(file, sw);
                    return sw.toString();
                }));
            }

            latch.countDown();
            executor.shutdown();

            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get());
            }

            for (String result : results) {
                assertThat(result).isEqualTo(expected);
            }
        }
    }

    // ─── Вспомогательные методы ───────────────────────────────────────────────

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("result_" + System.nanoTime() + ".json");
        Files.writeString(file, content);
        return file;
    }
}
