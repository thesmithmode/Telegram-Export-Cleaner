package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporterInterface;
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

/**
 * Сервис конвертации Telegram JSON export в текстовый формат.
 *
 * <p>Использует Jackson Streaming API для эффективной обработки файлов любого размера.
 * Результат записывается напрямую в HTTP response stream — без буферизации в памяти.
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
     * <p>Результат стримится напрямую в {@code HttpServletResponse.getOutputStream()}
     * через {@link StreamingResponseBody} — потребление памяти не зависит от размера
     * файла, что предотвращает OOM при больших экспортах.</p>
     *
     * @param file   загруженный файл {@code result.json}
     * @param filter фильтр сообщений (nullable)
     * @return ResponseEntity с StreamingResponseBody для потоковой записи
     * @throws IOException при ошибках ввода/вывода
     */
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
            // Temp file cleanup failure is non-critical
        }
    }
}
