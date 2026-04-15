package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.events.StatsStreamPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TelegramController {

    private static final Logger log = LoggerFactory.getLogger(TelegramController.class);
    private final TelegramExporter exporter;
    private final ObjectProvider<StatsStreamPublisher> statsPublisherProvider;

    public TelegramController(
            TelegramExporter exporter,
            ObjectProvider<StatsStreamPublisher> statsPublisherProvider
    ) {
        this.exporter = exporter;
        this.statsPublisherProvider = statsPublisherProvider;
    }

    @PostMapping("/convert")
    public ResponseEntity<StreamingResponseBody> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "keywords", required = false) String keywords,
            @RequestParam(value = "excludeKeywords", required = false) String excludeKeywords,
            // Опциональные статистические поля, заполняемые Python-воркером
            @RequestParam(value = "taskId", required = false) String taskId,
            @RequestParam(value = "botUserId", required = false) Long botUserId,
            @RequestParam(value = "chatTitle", required = false) String chatTitle,
            @RequestParam(value = "messagesCount", required = false) Long messagesCount
    ) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        // Фильтр готовим СРАЗУ, чтобы ошибки валидации вылетели до работы с файлами
        MessageFilter filter = MessageFilter.fromParameters(startDate, endDate, keywords, excludeKeywords);

        final Path tempFile = Files.createTempFile("tgc-", ".json");
        try (InputStream is = file.getInputStream()) {
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        final String capturedTaskId = taskId;
        final Long capturedBotUserId = botUserId;
        final Long capturedMessages = messagesCount;

        StreamingResponseBody responseBody = outputStream -> {
            CountingOutputStream counting = new CountingOutputStream(outputStream);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(counting, StandardCharsets.UTF_8))) {
                exporter.processFileStreaming(tempFile, filter, writer);
                writer.flush();
            } catch (Exception e) {
                log.error("ASYNCHRONOUS ERROR in streaming response", e);
            } finally {
                long bytesWritten = counting.getByteCount();
                publishBytesAndCompleted(capturedTaskId, capturedBotUserId, capturedMessages, bytesWritten);
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ex) {
                    log.warn("Failed to delete temp file {}: {}", tempFile, ex.getMessage());
                }
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=output.txt")
                .contentType(MediaType.TEXT_PLAIN)
                .body(responseBody);
    }

    private void publishBytesAndCompleted(String taskId, Long botUserId,
                                          Long messagesCount, long bytesWritten) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        StatsStreamPublisher publisher = statsPublisherProvider.getIfAvailable();
        if (publisher == null) {
            return;
        }
        Instant now = Instant.now();
        try {
            publisher.publish(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_BYTES_MEASURED)
                    .taskId(taskId)
                    .bytesCount(bytesWritten)
                    .ts(now)
                    .build());
        } catch (Exception ex) {
            log.debug("bytes_measured не опубликовано: {}", ex.getMessage());
        }
        try {
            publisher.publish(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_COMPLETED)
                    .taskId(taskId)
                    .botUserId(botUserId)
                    .messagesCount(messagesCount)
                    .bytesCount(bytesWritten)
                    .status("completed")
                    .source("bot")
                    .ts(now)
                    .build());
        } catch (Exception ex) {
            log.debug("export.completed не опубликовано: {}", ex.getMessage());
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Collections.singletonMap("status", "UP"));
    }

    /**
     * Простой счётчик записанных байт — без доп. зависимостей.
     * {@link org.apache.commons.io.output.CountingOutputStream} не в classpath,
     * поэтому реализация inline.
     */
    private static final class CountingOutputStream extends OutputStream {

        private final OutputStream delegate;
        private long count;

        CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        long getByteCount() {
            return count;
        }
    }
}
