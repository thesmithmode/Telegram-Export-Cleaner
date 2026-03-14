package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TelegramController {

    private final TelegramExporter exporter;
    private final ObjectMapper objectMapper;

    public TelegramController() {
        this.exporter = new TelegramExporter();
        this.objectMapper = new ObjectMapper();
    }

    @PostMapping("/convert")
    public ResponseEntity<?> convert(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Файл пустой"));
        }

        if (!file.getOriginalFilename().endsWith(".json")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Ожидается JSON файл"));
        }

        try {
            Path tempDir = Files.createTempDirectory("telegram-cleaner");
            Path inputFile = tempDir.resolve("result.json");
            Path outputFile = tempDir.resolve("output.txt");

            file.transferTo(inputFile.toFile());

            exporter.processFileToFile(inputFile, outputFile);

            String result = Files.readString(outputFile);

            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
            Files.delete(tempDir);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=output.txt")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(result);

        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка обработки: " + e.getMessage()));
        }
    }

    @PostMapping("/convert/json")
    public ResponseEntity<?> convertJson(@RequestBody String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Пустое содержимое"));
        }

        try {
            Path tempDir = Files.createTempDirectory("telegram-cleaner");
            Path inputFile = tempDir.resolve("result.json");
            Path outputFile = tempDir.resolve("output.txt");

            Files.writeString(inputFile, jsonContent);

            exporter.processFileToFile(inputFile, outputFile);

            String result = Files.readString(outputFile);

            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
            Files.delete(tempDir);

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(result);

        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка обработки: " + e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
