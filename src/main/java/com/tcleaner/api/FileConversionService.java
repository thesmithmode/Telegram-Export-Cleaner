package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class FileConversionService {

    private static final Logger log = LoggerFactory.getLogger(FileConversionService.class);

    private final TelegramExporter exporter;

    public FileConversionService(TelegramExporter exporter) {
        this.exporter = exporter;
    }

    // Результат стримится напрямую в response stream — потребление памяти не зависит от размера файла.
    public ResponseEntity<StreamingResponseBody> convert(MultipartFile file, MessageFilter filter) throws IOException {
        Path inputFile = Files.createTempFile("telegram-cleaner-", ".json");

        try {
            file.transferTo(inputFile.toFile());
        } catch (IOException e) {
            cleanupTempFile(inputFile);
            throw e;
        }

        StreamingResponseBody body = outputStream -> {
            try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                exporter.processFileStreaming(inputFile, filter, writer);
                writer.flush();
            } finally {
                cleanupTempFile(inputFile);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=output.txt")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    private static void cleanupTempFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Не удалось удалить temp file {}: {}", path, e.getMessage());
        }
    }
}
