package com.tcleaner;

import com.tcleaner.core.MessageProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для обработки null, пустых и невалидных данных в MessageProcessor.
 * Критично для защиты от NPE на этапе CI.
 */
@DisplayName("MessageProcessor - Обработка null и edge cases")
class MessageProcessorNullHandlingTest {

    private MessageProcessor processor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        processor = new MessageProcessor();
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("Обработка null и отсутствующих полей")
    class NullAndMissingFields {

        @Test
        @DisplayName("Возвращает null для null message")
        void handlesNullMessage() {
            String result = processor.processMessage(null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Обрабатывает сообщение без type (дефолт 'message')")
        void handlesMessageWithoutType() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "date": "2025-06-24T10:00:00",
                    "text": "Hello"
                }
                """);
            String result = processor.processMessage(message);
            // Без type используется дефолт "message", поэтому обрабатывается
            assertThat(result).isEqualTo("20250624 Hello");
        }

        @Test
        @DisplayName("Возвращает null для сообщения без date")
        void handlesMessageWithoutDate() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "text": "Hello"
                }
                """);
            String result = processor.processMessage(message);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Возвращает null для сообщения без text")
        void handlesMessageWithoutText() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-06-24T10:00:00"
                }
                """);
            String result = processor.processMessage(message);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Возвращает null для сообщения с null text полем")
        void handlesMessageWithNullTextField() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-06-24T10:00:00",
                    "text": null
                }
                """);
            String result = processor.processMessage(message);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Возвращает null для сообщения с пустым text полем")
        void handlesMessageWithEmptyTextField() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-06-24T10:00:00",
                    "text": ""
                }
                """);
            String result = processor.processMessage(message);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Возвращает null для сообщения с пробелами в text")
        void handlesMessageWithOnlyWhitespace() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-06-24T10:00:00",
                    "text": "   "
                }
                """);
            String result = processor.processMessage(message);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Возвращает null для невалидного date формата")
        void handlesInvalidDateFormat() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "invalid-date",
                    "text": "Hello"
                }
                """);
            String result = processor.processMessage(message);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Обрабатывает null в text массиве")
        void handlesNullInTextArray() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-06-24T10:00:00",
                    "text": [
                        {"type": "plain", "text": "Hello"},
                        null,
                        {"type": "plain", "text": "World"}
                    ]
                }
                """);
            String result = processor.processMessage(message);
            // Должен обработать и пропустить null элемент
            assertThat(result).contains("Hello").contains("World");
        }
    }

    @Nested
    @DisplayName("Обработка пустого/null списка сообщений")
    class NullMessageList {

        @Test
        @DisplayName("processMessages(null) вернёт пустой список, не null")
        void processMessagesNullReturnsEmpty() {
            var result = processor.processMessages(null);
            assertThat(result).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("processMessages([]) вернёт пустой список")
        void processMessagesEmptyReturnsEmpty() {
            var result = processor.processMessages(java.util.List.of());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("processMessages не кидает NPE на смешанные данные")
        void processMessagesHandlesMixedValidInvalidData() throws Exception {
            var messages = java.util.List.of(
                objectMapper.readTree("{\"type\":\"message\",\"date\":\"2025-06-24T10:00:00\",\"text\":\"Valid\"}"),
                objectMapper.readTree("{\"type\":\"message\",\"date\":\"invalid\",\"text\":\"Invalid date\"}"),
                objectMapper.readTree("{\"type\":\"message\",\"date\":\"2025-06-24T10:00:00\",\"text\":null}"),
                objectMapper.readTree("{\"type\":\"message\",\"date\":\"2025-06-24T10:00:00\",\"text\":\"Valid 2\"}")
            );

            var result = processor.processMessages(messages);

            assertThat(result).isNotEmpty();
            assertThat(result).contains("20250624 Valid", "20250624 Valid 2");
        }
    }

    @Nested
    @DisplayName("Обработка специальных значений")
    class SpecialValues {

        @Test
        @DisplayName("Обрабатывает текст с только новыми линиями")
        void handlesTextWithOnlyNewlines() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-06-24T10:00:00",
                    "text": "\\n\\n\\n"
                }
                """);
            String result = processor.processMessage(message);
            // После нормализации новых линий это может быть пустым
            assertThat(result).isNullOrEmpty();
        }

        @Test
        @DisplayName("Обрабатывает очень длинный текст")
        void handlesVeryLongText() throws Exception {
            String longText = "x".repeat(10000);
            JsonNode message = objectMapper.readTree(String.format("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-06-24T10:00:00",
                    "text": "%s"
                }
                """, longText));

            String result = processor.processMessage(message);
            assertThat(result).isNotNull().contains(longText);
        }

        @Test
        @DisplayName("Обрабатывает текст с спецсимволами")
        void handlesSpecialCharacters() throws Exception {
            JsonNode message = objectMapper.readTree("""
                {
                    "id": 1,
                    "type": "message",
                    "date": "2025-06-24T10:00:00",
                    "text": "!@#$%^&*()_+-=[]{}|;:',.<>?/"
                }
                """);

            String result = processor.processMessage(message);
            assertThat(result).isNotNull();
        }
    }
}
