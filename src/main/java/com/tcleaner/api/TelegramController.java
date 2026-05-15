package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.events.StatsStreamPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
import java.util.Map;

@RestController
@RequestMapping("/api")
@Validated
public class TelegramController {

    private static final Logger log = LoggerFactory.getLogger(TelegramController.class);
    // Sentinel-маркер целостности streaming-response. Pyrogram-worker
    // (java_client._upload_file_to_java) проверяет endswith и считает
    // отсутствие sentinel как truncated stream.
    private static final String SENTINEL = "\n##OK##";
    private final TelegramExporter exporter;
    private final ObjectProvider<StatsStreamPublisher> statsPublisherProvider;
    private final Counter publishErrorsCounter;

    public TelegramController(
            TelegramExporter exporter,
            ObjectProvider<StatsStreamPublisher> statsPublisherProvider,
            MeterRegistry meterRegistry
    ) {
        this.exporter = exporter;
        this.statsPublisherProvider = statsPublisherProvider;
        this.publishErrorsCounter = Counter.builder("stats.publish.errors").register(meterRegistry);
    }

    @PostMapping("/convert")
    public ResponseEntity<StreamingResponseBody> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "keywords", required = false) @Size(max = 4096) String keywords,
            @RequestParam(value = "excludeKeywords", required = false) @Size(max = 4096) String excludeKeywords,
            // Опциональные статистические поля, заполняемые Python-воркером
            @RequestParam(value = "taskId", required = false) @Size(max = 128) String taskId,
            @RequestParam(value = "botUserId", required = false) @Positive Long botUserId,
            @RequestParam(value = "chatTitle", required = false) @Size(max = 1024) String chatTitle,
            @RequestParam(value = "messagesCount", required = false) @PositiveOrZero Long messagesCount,
            @RequestParam(value = "subscriptionId", required = false) @Positive Long subscriptionId
    ) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        // Фильтр готовим СРАЗУ, чтобы ошибки валидации вылетели до работы с файлами
        MessageFilter filter = MessageFilter.fromParameters(startDate, endDate, keywords, excludeKeywords);

        final Path tempFile = Files.createTempFile("tgc-", ".json");
        try (InputStream is = file.getInputStream()) {
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Если copy упал — StreamingResponseBody НЕ выполнится, его finally
            // с deleteIfExists не сработает. Чистим temp здесь.
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupEx) {
                log.warn("Failed to delete temp file after copy error {}: {}", tempFile, cleanupEx.getMessage());
            }
            throw e;
        }

        final String capturedTaskId = taskId;
        final Long capturedBotUserId = botUserId;
        final Long capturedMessages = messagesCount;
        final Long capturedSubscriptionId = subscriptionId;

        StreamingResponseBody responseBody = outputStream -> {
            CountingOutputStream counting = new CountingOutputStream(outputStream);
            boolean[] succeeded = {false};
            String[] failureReason = {null};
            // Размер sentinel в байтах — учтён в counting, но НЕ часть payload юзера.
            // bytesWritten для analytics вычитает sentinel ниже.
            final int SENTINEL_BYTES = SENTINEL.getBytes(StandardCharsets.UTF_8).length;
            // succeeded[0]=true ставится ПОСЛЕ try-with-resources close() —
            // если BufferedWriter.close() (последний flush в OutputStream)
            // выкинет IOException, мы не должны опубликовать EXPORT_COMPLETED.
            // Раньше succeeded=true стоял внутри try {} → close() throw
            // ловился catch, но finally видел true и публиковал completed.
            try {
                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(counting, StandardCharsets.UTF_8))) {
                    exporter.processFileStreaming(tempFile, filter, writer);
                    // Sentinel ##OK## в конце стрима — единственный надёжный способ
                    // отличить truncated response от полного. HTTP 200 + headers
                    // уходят ДО фактической записи в outputStream; если stream
                    // оборвётся в середине (timeout, broken pipe, exception в
                    // processFileStreaming), клиент всё равно увидит status=200 и
                    // частичный body. Python java_client проверяет endswith и
                    // strip'ает sentinel перед использованием контента.
                    //
                    // Trade-off: при network truncate после publish'а — Python
                    // ретраит, COMPLETED публикуется повторно (~двойной счётчик
                    // для редкого task_id). Это допустимо: окно узкое (post-flush
                    // до полного TCP-ACK), и net-benefit > silent bad-files.
                    writer.write(SENTINEL);
                    writer.flush();
                }
                // close() прошёл успешно — payload реально доставлен.
                succeeded[0] = true;
            } catch (Exception e) {
                // 200 + headers уже отправлены — клиент получает truncated output;
                // publishFailed обязателен, иначе ExportEvent застрянет в STARTED.
                log.error("ASYNCHRONOUS ERROR in streaming response", e);
                failureReason[0] = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
            } finally {
                long bytesWritten = counting.getByteCount();
                if (succeeded[0]) {
                    // Sentinel — служебный маркер, не часть user-видимого payload.
                    // Вычитаем его из метрики, чтобы EXPORT_BYTES_MEASURED == размер,
                    // который реально получает пользователь после strip'а.
                    long payloadBytes = Math.max(0L, bytesWritten - SENTINEL_BYTES);
                    publishBytesAndCompleted(capturedTaskId, capturedBotUserId,
                            capturedMessages, payloadBytes, capturedSubscriptionId);
                } else {
                    publishFailed(capturedTaskId, capturedBotUserId,
                            capturedSubscriptionId, failureReason[0]);
                }
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
                                          Long messagesCount, long bytesWritten,
                                          Long subscriptionId) {
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
            publishErrorsCounter.increment();
            log.error("bytes_measured не опубликовано taskId={}: {}", taskId, ex.getMessage(), ex);
        }
        try {
            publisher.publish(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_COMPLETED)
                    .taskId(taskId)
                    .botUserId(botUserId)
                    .messagesCount(messagesCount)
                    .bytesCount(bytesWritten)
                    .subscriptionId(subscriptionId)
                    .status("completed")
                    .source("bot")
                    .ts(now)
                    .build());
        } catch (Exception ex) {
            publishErrorsCounter.increment();
            log.error("export.completed не опубликовано taskId={}: {}", taskId, ex.getMessage(), ex);
        }
    }

    private void publishFailed(String taskId, Long botUserId, Long subscriptionId, String reason) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        StatsStreamPublisher publisher = statsPublisherProvider.getIfAvailable();
        if (publisher == null) {
            return;
        }
        try {
            publisher.publish(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_FAILED)
                    .taskId(taskId)
                    .botUserId(botUserId)
                    .subscriptionId(subscriptionId)
                    .status("failed")
                    .source("bot")
                    .error(reason != null ? reason : "unknown_streaming_error")
                    .ts(Instant.now())
                    .build());
        } catch (Exception ex) {
            publishErrorsCounter.increment();
            log.error("export.failed не опубликовано taskId={}: {}", taskId, ex.getMessage(), ex);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
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

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        long getByteCount() {
            return count;
        }
    }
}
