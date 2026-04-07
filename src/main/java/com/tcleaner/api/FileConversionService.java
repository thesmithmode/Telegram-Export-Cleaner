package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporterInterface;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Сервис конвертации Telegram JSON export в текстовый формат.
 *
 * <p>Использует Jackson Streaming API для эффективной обработки файлов любого размера.
 * Временная директория автоматически очищается после обработки.</p>
 */
@Service
public class FileConversionService {

    private final TelegramExporterInterface exporter;

    public FileConversionService(TelegramExporterInterface exporter) {
        this.exporter = exporter;
    }

    /**
     * Конвертирует multipart JSON-файл в текстовый формат.
     *
     * @param file   загруженный файл {@code result.json}
     * @param filter фильтр сообщений (nullable)
     * @return ResponseEntity с текстовым файлом в теле ответа
     * @throws IOException при ошибках ввода/вывода
     */
    public ResponseEntity<?> convert(MultipartFile file, MessageFilter filter) throws IOException {
        try (TempDirectory tempDir = new TempDirectory()) {
            Path inputFile = tempDir.resolve("result.json");
            file.transferTo(inputFile.toFile());

            StringWriter sw = new StringWriter();
            try (BufferedWriter writer = new BufferedWriter(sw)) {
                exporter.processFileStreaming(inputFile, filter, writer);
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=output.txt")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(sw.toString());
        }
    }

    /**
     * Временная директория с автоматической очисткой.
     */
    private static class TempDirectory implements AutoCloseable {

        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TempDirectory.class);
        private final Path path;

        TempDirectory() throws IOException {
            this.path = Files.createTempDirectory("telegram-cleaner");
        }

        Path resolve(String fileName) {
            return path.resolve(fileName);
        }

        @Override
        public void close() throws IOException {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(pp -> {
                        try {
                            Files.deleteIfExists(pp);
                        } catch (IOException e) {
                            log.warn("Не удалось удалить временный файл: {}", pp, e);
                        }
                    });
        }
    }
}
