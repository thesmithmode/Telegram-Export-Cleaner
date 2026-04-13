package com.tcleaner.api;

import com.tcleaner.core.MessageFilter;
import com.tcleaner.core.TelegramExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TelegramController {

    private static final Logger log = LoggerFactory.getLogger(TelegramController.class);
    private final TelegramExporter exporter;

    
    public TelegramController(TelegramExporter exporter) {
        this.exporter = exporter;
    }

    
    @PostMapping("/convert")
    public ResponseEntity<StreamingResponseBody> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "keywords", required = false) String keywords,
            @RequestParam(value = "excludeKeywords", required = false) String excludeKeywords) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        // Фильтр готовим СРАЗУ, чтобы ошибки валидации вылетели до работы с файлами
        MessageFilter filter = MessageFilter.fromParameters(startDate, endDate, keywords, excludeKeywords);

        final Path tempFile = Files.createTempFile("tgc-", ".json");
        try (InputStream is = file.getInputStream()) {
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        StreamingResponseBody responseBody = outputStream -> {
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                exporter.processFileStreaming(tempFile, filter, writer);
                writer.flush();
            } catch (Exception e) {
                log.error("ASYNCHRONOUS ERROR in streaming response", e);
            } finally {
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

    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Collections.singletonMap("status", "UP"));
    }
}
