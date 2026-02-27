package com.tcleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты на потокобезопасность TelegramExporter.
 */
@DisplayName("TelegramExporter - Thread Safety")
class TelegramExporterThreadSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Параллельная обработка одного файла несколькими потоками даёт одинаковый результат")
    void concurrentProcessingProducesSameResult() throws Exception {
        String json = """
                {
                    "messages": [
                        {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Hello"},
                        {"id": 2, "type": "message", "date": "2025-06-24T11:00:00", "text": "World"},
                        {"id": 3, "type": "service", "date": "2025-06-24T12:00:00", "action": "join"}
                    ]
                }
                """;

        Path inputFile = tempDir.resolve("result.json");
        Files.writeString(inputFile, json);

        TelegramExporter exporter = new TelegramExporter();
        List<String> referenceResult = exporter.processFile(inputFile);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();

        for (int ii = 0; ii < threadCount; ii++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return exporter.processFile(inputFile);
            }));
        }

        latch.countDown();
        executor.shutdown();

        List<List<String>> results = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            results.add(future.get());
        }

        for (List<String> result : results) {
            assertThat(result).isEqualTo(referenceResult);
        }
    }

    @Test
    @DisplayName("Параллельная обработка разных файлов не вызывает коллизий")
    void concurrentProcessingOfDifferentFilesIsIsolated() throws Exception {
        TelegramExporter exporter = new TelegramExporter();
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int ii = 0; ii < threadCount; ii++) {
            final int idx = ii;
            futures.add(executor.submit(() -> {
                String json = String.format("""
                        {"messages": [
                            {"id": %d, "type": "message",
                             "date": "2025-06-24T10:00:00", "text": "Message %d"}
                        ]}
                        """, idx, idx);
                Path file = tempDir.resolve("result_" + idx + ".json");
                Files.writeString(file, json);
                return exporter.processFile(file).size();
            }));
        }

        executor.shutdown();
        for (Future<Integer> future : futures) {
            assertThat(future.get()).isEqualTo(1);
        }
    }
}
