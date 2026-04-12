package com.tcleaner.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

@Service
public class TelegramExporter {

    private static final Logger log = LoggerFactory.getLogger(TelegramExporter.class);

    private final ObjectMapper objectMapper;
    private final MessageProcessor messageProcessor;

    public TelegramExporter(ObjectMapper objectMapper, MessageProcessor messageProcessor) {
        this.objectMapper = objectMapper;
        this.messageProcessor = messageProcessor;
    }

    public List<String> processFile(Path inputPath) throws IOException {
        return processFile(inputPath, null);
    }

    public List<String> processFile(Path inputPath, MessageFilter filter) throws IOException {
        log.debug("Начало обработки файла: {}", inputPath);
        validateInputFile(inputPath);

        JsonNode root;
        try {
            root = objectMapper.readTree(inputPath.toFile());
        } catch (Exception ex) {
            log.error("Ошибка парсинга JSON: {}", ex.getMessage());
            throw new TelegramExporterException("INVALID_JSON", "Невалидный JSON: " + ex.getMessage(), ex);
        }

        JsonNode messagesNode = root.get("messages");

        if (messagesNode == null || !messagesNode.isArray()) {
            log.warn("В файле отсутствует массив messages или он имеет неверный формат: {}", inputPath);
            return List.of();
        }

        List<JsonNode> messages = StreamSupport
                .stream(messagesNode.spliterator(), false)
                .filter(n -> filter == null || filter.matches(n))
                .collect(java.util.stream.Collectors.toList());

        log.debug("Найдено {} сообщений для обработки", messages.size());

        List<String> result = messageProcessor.processMessages(messages);
        log.info("Обработано {} сообщений из файла {}", result.size(), inputPath.getFileName());

        return result;
    }

    public int processFileStreaming(Path inputPath, Writer out) throws IOException {
        return processFileStreaming(inputPath, null, out);
    }

    public int processFileStreaming(Path inputPath, MessageFilter filter, Writer out)
            throws IOException {
        log.debug("Streaming-обработка файла: {}", inputPath);
        validateInputFile(inputPath);

        int written = 0;
        try (JsonParser parser = objectMapper.getFactory().createParser(inputPath.toFile())) {
            // Ищем поле "messages" на верхнем уровне
            if (!advanceToMessagesArray(parser, inputPath)) {
                log.warn("В файле отсутствует массив messages: {}", inputPath);
                return 0;
            }

            // Итерируем элементы массива по одному
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                JsonNode message = objectMapper.readTree(parser);
                if (filter != null && !filter.matches(message)) {
                    continue;
                }
                String line = messageProcessor.processMessage(message);
                if (line != null) {
                    out.write(line);
                    out.write('\n');
                    written++;
                }
            }
        } catch (TelegramExporterException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Ошибка парсинга JSON (streaming): {}", ex.getMessage());
            throw new TelegramExporterException("INVALID_JSON", "Невалидный JSON: " + ex.getMessage(), ex);
        }

        log.info("Streaming: записано {} строк из файла {}", written, inputPath.getFileName());
        return written;
    }

    private boolean advanceToMessagesArray(JsonParser parser, Path inputPath) throws IOException {
        while (parser.nextToken() != null) {
            if (JsonToken.FIELD_NAME.equals(parser.currentToken())
                    && "messages".equals(parser.getCurrentName())) {
                JsonToken next = parser.nextToken();
                if (JsonToken.START_ARRAY.equals(next)) {
                    return true;
                }
                log.warn("Поле messages не является массивом в файле: {}", inputPath);
                return false;
            }
        }
        return false;
    }

    private void validateInputFile(Path inputPath) {
        if (!Files.exists(inputPath)) {
            log.error("Файл не найден: {}", inputPath);
            throw new TelegramExporterException("FILE_NOT_FOUND", "Файл не найден: " + inputPath);
        }
    }

}
