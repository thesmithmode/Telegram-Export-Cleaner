package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для MessageProcessor.
 * 
 * Ожидаемое поведение:
 * - processMessage() преобразует message в строку формата "YYYYMMDDtext"
 * - Пропускает service-сообщения
 * - Обрабатывает различные типы сообщений
 */
@DisplayName("MessageProcessor")
class MessageProcessorTest {

    private ObjectMapper objectMapper;
    private MessageProcessor processor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        processor = new MessageProcessor();
    }

    @Nested
    @DisplayName("processMessage() - обработка сообщений")
    class ProcessMessage {

        @Test
        @DisplayName("Обрабатывает простое текстовое сообщение")
        void processesSimpleTextMessage() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-06-24T15:29:46",
                    "from": "John",
                    "text": "Hello world"
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 Hello world");
        }

        @Test
        @DisplayName("Обрабатывает сообщение с bold форматированием")
        void processesMessageWithBold() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 2,
                    "type": "message",
                    "date": "2025-06-24T15:30:00",
                    "from": "John",
                    "text": [
                        {"type": "plain", "text": "This is "},
                        {"type": "bold", "text": "important"}
                    ]
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 This is **important**");
        }

        @Test
        @DisplayName("Обрабатывает сообщение с ссылкой")
        void processesMessageWithLink() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 3,
                    "type": "message",
                    "date": "2025-06-24T16:00:00",
                    "from": "John",
                    "text": [
                        {"type": "plain", "text": "Check "},
                        {"type": "link", "text": "https://example.com"}
                    ]
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 Check https://example.com");
        }

        @Test
        @DisplayName("Пропускает service-сообщения")
        void skipsServiceMessages() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 4,
                    "type": "service",
                    "date": "2025-06-24T10:00:00",
                    "action": "create_channel",
                    "title": "Test Channel"
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Обрабатывает сообщение с несколькими строками")
        void processesMultiLineMessage() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 5,
                    "type": "message",
                    "date": "2025-06-24T12:00:00",
                    "from": "John",
                    "text": "Line 1\\nLine 2\\nLine 3"
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 Line 1 Line 2 Line 3");
        }

        @Test
        @DisplayName("Обрабатывает сообщение с emoji")
        void processesMessageWithEmoji() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 6,
                    "type": "message",
                    "date": "2025-06-24T14:00:00",
                    "from": "John",
                    "text": "Hello! 👋"
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 Hello! 👋");
        }

        @Test
        @DisplayName("Обрабатывает сообщение с кодом")
        void processesMessageWithCode() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 7,
                    "type": "message",
                    "date": "2025-06-24T15:00:00",
                    "from": "John",
                    "text": [
                        {"type": "plain", "text": "Use "},
                        {"type": "code", "text": "console.log()"},
                        {"type": "plain", "text": " for debugging"}
                    ]
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 Use `console.log()` for debugging");
        }

        @Test
        @DisplayName("Обрабатывает сообщение со spoiler")
        void processesMessageWithSpoiler() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 8,
                    "type": "message",
                    "date": "2025-06-24T16:00:00",
                    "from": "John",
                    "text": [
                        {"type": "plain", "text": "Secret: "},
                        {"type": "spoiler", "text": "password123"}
                    ]
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 Secret: ||password123||");
        }

        @Test
        @DisplayName("Возвращает null для пустого текста")
        void returnsNullForEmptyText() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 9,
                    "type": "message",
                    "date": "2025-06-24T17:00:00",
                    "from": "John",
                    "text": ""
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Обрабатывает сообщение с forward")
        void processesForwardedMessage() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 10,
                    "type": "message",
                    "date": "2025-06-24T18:00:00",
                    "from": "John",
                    "forwarded_from": "Original Author",
                    "text": "Forwarded message text"
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 Forwarded message text");
        }

        @Test
        @DisplayName("Обрабатывает сообщение с reply")
        void processesReplyMessage() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 11,
                    "type": "message",
                    "date": "2025-06-24T19:00:00",
                    "from": "John",
                    "reply_to_message_id": 5,
                    "text": "This is a reply"
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 This is a reply");
        }

        @Test
        @DisplayName("Обрабатывает медиа-сообщение с подписью")
        void processesMediaWithCaption() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 12,
                    "type": "message",
                    "date": "2025-06-24T20:00:00",
                    "from": "John",
                    "photo": "(File not included. Change data exporting settings to download.)",
                    "text": "Check this photo!"
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).isEqualTo("20250624 Check this photo!");
        }
    }

    @Nested
    @DisplayName("processMessages() - обработка списка сообщений")
    class ProcessMessages {

        @Test
        @DisplayName("Обрабатывает список сообщений")
        void processesMessageList() throws Exception {
            List<JsonNode> messages = new ArrayList<>();
            
            messages.add(objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "First"}
                """));
            messages.add(objectMapper.readTree("""
                {"id": 2, "type": "service", "date": "2025-06-24T10:01:00", "action": "join"}
                """));
            messages.add(objectMapper.readTree("""
                {"id": 3, "type": "message", "date": "2025-06-24T10:02:00", "text": "Second"}
                """));
            
            List<String> result = processor.processMessages(messages);
            
            assertThat(result).containsExactly(
                "20250624 First",
                "20250624 Second"
            );
        }

        @Test
        @DisplayName("Возвращает пустой список для null входных данных")
        void returnsEmptyListForNull() {
            List<String> result = processor.processMessages(null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Возвращает пустой список для пустого списка")
        void returnsEmptyListForEmptyList() {
            List<String> result = processor.processMessages(List.of());
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Проверка формата вывода")
    class OutputFormat {

        @Test
        @DisplayName("Формат вывода: YYYY + MM + DD + text")
        void outputFormatIsCorrect() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-12-31T23:59:59",
                    "text": "Test"
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).startsWith("20251231");
            assertThat(result).isEqualTo("20251231 Test");
        }

        @Test
        @DisplayName("Дата не содержит разделителей")
        void dateHasNoSeparators() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-01-01T00:00:00",
                    "text": "New Year"
                }
                """);
            
            String result = processor.processMessage(message);
            
            assertThat(result).startsWith("20250101");
            assertThat(result).doesNotContain("-");
        }
    }
}
