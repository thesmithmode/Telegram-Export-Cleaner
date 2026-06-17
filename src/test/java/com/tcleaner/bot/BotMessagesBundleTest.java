package com.tcleaner.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BotMessagesBundleTest {

    @Test
    @DisplayName("bot_messages bundles do not contain duplicate keys")
    void botMessagesBundlesHaveNoDuplicateKeys() throws IOException {
        Path resources = Path.of("src", "main", "resources");

        try (Stream<Path> files = Files.list(resources)) {
            Map<Path, Map<String, Integer>> duplicates = new LinkedHashMap<>();
            files.filter(path -> path.getFileName().toString().matches("bot_messages(_[A-Za-z_]+)?\\.properties"))
                    .sorted()
                    .forEach(path -> collectDuplicateKeys(path, duplicates));

            assertThat(duplicates).isEmpty();
        }
    }

    private static void collectDuplicateKeys(Path path, Map<Path, Map<String, Integer>> duplicates) {
        Map<String, Integer> firstSeenLines = new LinkedHashMap<>();
        try {
            var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }

                int separator = line.indexOf('=');
                if (separator < 0) {
                    separator = line.indexOf(':');
                }
                if (separator < 0) {
                    continue;
                }

                String key = line.substring(0, separator).trim();
                Integer previousLine = firstSeenLines.putIfAbsent(key, i + 1);
                if (previousLine != null) {
                    duplicates.computeIfAbsent(path, ignored -> new LinkedHashMap<>())
                            .put(key + " first seen at line " + previousLine, i + 1);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
