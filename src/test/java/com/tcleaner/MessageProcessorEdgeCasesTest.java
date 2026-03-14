package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Расширенные тесты для MessageProcessor - edge cases и граничные условия.
 */
@DisplayName("MessageProcessor - Edge Cases")
class MessageProcessorEdgeCasesTest {

    private ObjectMapper objectMapper;
    private MessageProcessor processor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        processor = new MessageProcessor();
    }

    @Nested
    @DisplayName("Обработка null и пустых значений")
    class NullAndEmptyHandling {

        @Test
        @DisplayName("processMessage возвращает null для null сообщения")
        void returnsNullForNullMessage() {
            assertThat(processor.processMessage(null)).isNull();
        }

        @Test
        @DisplayName("processMessage возвращает null когда нет поля date")
        void returnsNullWhenNoDate() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "text": "Hello"}
                """);
            assertThat(processor.processMessage(message)).isNull();
        }

        @Test
        @DisplayName("processMessage возвращает null для пустого текста")
        void returnsNullForEmptyText() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": ""}
                """);
            assertThat(processor.processMessage(message)).isNull();
        }

        @Test
        @DisplayName("processMessage возвращает null для пробельного текста")
        void returnsNullForWhitespaceText() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "   "}
                """);
            assertThat(processor.processMessage(message)).isNull();
        }

        @Test
        @DisplayName("processMessage возвращает null когда text = null")
        void returnsNullWhenTextIsNull() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00"}
                """);
            assertThat(processor.processMessage(message)).isNull();
        }

        @Test
        @DisplayName("processMessages возвращает пустой список для null")
        void returnsEmptyListForNullInput() {
            assertThat(processor.processMessages(null)).isEmpty();
        }

        @Test
        @DisplayName("processMessages возвращает пустой список для пустого списка")
        void returnsEmptyListForEmptyList() {
            assertThat(processor.processMessages(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Обработка различных типов сообщений")
    class MessageTypesHandling {

        @Test
        @DisplayName("Пропускает service сообщения")
        void skipsServiceMessages() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "service",
                    "date": "2025-06-24T10:00:00",
                    "action": "join",
                    "actor": "User"
                }
                """);
            assertThat(processor.processMessage(message)).isNull();
        }

        @Test
        @DisplayName("Обрабатывает сообщение без поля type (по умолчанию message)")
        void handlesMessageWithoutType() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "date": "2025-06-24T10:00:00", "text": "Hello"}
                """);
            String result = processor.processMessage(message);
            assertThat(result).isNotNull();
            assertThat(result).contains("Hello");
        }

        @Test
        @DisplayName("Обрабатывает сообщение с неизвестным типом как обычное")
        void handlesUnknownTypeAsMessage() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "unknown", "date": "2025-06-24T10:00:00", "text": "Hello"}
                """);
            String result = processor.processMessage(message);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Обработка текстового поля различного формата")
    class TextFieldVariations {

        @Test
        @DisplayName("Обрабатывает text как строку")
        void handlesTextAsString() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Simple text"}
                """);
            String result = processor.processMessage(message);
            assertThat(result).isEqualTo("20250624 Simple text");
        }

        @Test
        @DisplayName("Обрабатывает text как массив объектов")
        void handlesTextAsArrayOfObjects() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", 
                 "text": [
                    {"type": "plain", "text": "Hello "},
                    {"type": "bold", "text": "World"}
                ]}
                """);
            String result = processor.processMessage(message);
            assertThat(result).isEqualTo("20250624 Hello **World**");
        }

        @Test
        @DisplayName("Обрабатывает text как массив со строками и объектами")
        void handlesTextAsMixedArray() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", 
                 "text": [
                    "Start: ",
                    {"type": "code", "text": "test"},
                    " and ",
                    {"type": "italic", "text": "end"}
                ]}
                """);
            String result = processor.processMessage(message);
            assertThat(result).isEqualTo("20250624 Start: `test` and *end*");
        }

        @Test
        @DisplayName("Обрабатывает text с emoji")
        void handlesEmojiInText() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Hello 👋 World 🌍"}
                """);
            String result = processor.processMessage(message);
            assertThat(result).isEqualTo("20250624 Hello 👋 World 🌍");
        }

        @Test
        @DisplayName("Обрабатывает текст с множественными переносами строк")
        void handlesMultipleNewlines() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Line1\\n\\nLine2\\n\\nLine3"}
                """);
            String result = processor.processMessage(message);
            assertThat(result).isEqualTo("20250624 Line1  Line2  Line3");
        }
    }

    @Nested
    @DisplayName("Обработка различных форматов даты")
    class DateFormatsHandling {

        @Test
        @DisplayName("Обрабатывает валидную ISO дату")
        void handlesValidIsoDate() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T15:30:45", "text": "Test"}
                """);
            String result = processor.processMessage(message);
            assertThat(result).startsWith("20250624");
        }

        @Test
        @DisplayName("Пропускает сообщение с датой без времени (ISO datetime обязателен)")
        void handlesDateWithoutTime() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24", "text": "Test"}
                """);
            String result = processor.processMessage(message);
            // Дата без времени не является валидным ISO datetime — сообщение пропускается
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Пропускает сообщение с невалидной датой")
        void handlesInvalidDate() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "invalid-date", "text": "Test"}
                """);
            String result = processor.processMessage(message);
            // Невалидная дата — сообщение пропускается
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Обрабатывает дату с пустым значением")
        void handlesEmptyDate() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "", "text": "Test"}
                """);
            String result = processor.processMessage(message);
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Обработка больших объёмов данных")
    class LargeDataHandling {

        @Test
        @DisplayName("Обрабатывает 1000 сообщений корректно")
        void handlesLargeMessageList() {
            List<JsonNode> messages = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                String json = String.format("""
                    {"id": %d, "type": "message", "date": "2025-06-24T10:00:%02d", "text": "Message %d"}
                    """, i, i % 60, i);
                try {
                    messages.add(objectMapper.readTree(json));
                } catch (Exception e) {
                    org.junit.jupiter.api.Assertions.fail(
                        "Неожиданный сбой парсинга JSON для i=" + i + ": " + e.getMessage());
                }
            }

            List<String> result = processor.processMessages(messages);
            assertThat(result).hasSize(1000);
        }
    }
}
