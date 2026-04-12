package com.tcleaner;

import com.tcleaner.core.MessageFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MessageFilter")
class MessageFilterTest {

    private ObjectMapper objectMapper;
    private JsonNode message1;
    private JsonNode message2;
    private JsonNode serviceMessage;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        
        message1 = objectMapper.readTree("""
            {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Hello world"}
            """);
        
        message2 = objectMapper.readTree("""
            {"id": 2, "type": "message", "date": "2025-07-15T15:30:00", "text": "Goodbye world"}
            """);
        
        serviceMessage = objectMapper.readTree("""
            {"id": 3, "type": "service", "date": "2025-06-25T10:00:00", "text": "User left the group"}
            """);
    }

    private JsonNode msg(String date, String text) throws Exception {
        return objectMapper.readTree(
            String.format("{\"id\":1,\"type\":\"message\",\"date\":\"%s\",\"text\":\"%s\"}", date, text)
        );
    }

    @Nested
    @DisplayName("Метод fromParameters")
    class FromParametersTests {

        @Test
        @DisplayName("Все параметры null или пустые → null")
        void allNullOrBlankReturnsNull() {
            assertThat(MessageFilter.fromParameters((String)null, (String)null, null, null)).isNull();
            assertThat(MessageFilter.fromParameters("", "  ", "", "")).isNull();
        }

        @Test
        @DisplayName("Корректно создает фильтр по дате")
        void createsDateFilter() throws Exception {
            MessageFilter filter = MessageFilter.fromParameters("2025-07-01", "2025-08-01", null, null);
            assertThat(filter.matches(msg("2025-06-30T23:59:59", "before"))).isFalse();
            assertThat(filter.matches(msg("2025-07-15T00:00:00", "inside"))).isTrue();
            assertThat(filter.matches(msg("2025-08-02T00:00:00", "after"))).isFalse();
        }

        @Test
        @DisplayName("Невалидная дата → исключение DateTimeParseException")
        void invalidDateThrows() {
            assertThatThrownBy(() -> MessageFilter.fromParameters("not-a-date", null, null, null))
                    .isInstanceOf(java.time.format.DateTimeParseException.class);
        }

        @Test
        @DisplayName("startDate позже endDate → исключение IllegalArgumentException")
        void startDateAfterEndDateThrows() {
            assertThatThrownBy(() -> MessageFilter.fromParameters("2025-12-31", "2025-01-01", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startDate не может быть позже endDate");
        }

        @Test
        @DisplayName("Корректно парсит ключевые слова (CSV)")
        void parsesKeywords() throws Exception {
            MessageFilter filter = MessageFilter.fromParameters((String)null, (String)null, " java , spring ", " spam ");
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "java rocks"))).isTrue();
            assertThat(filter.matches(msg("2025-01-01T00:00:00", "buy spam"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Фильтрация по дате (Fluent API)")
    class DateFilterTests {

        @Test
        @DisplayName("Фильтрует по начальной дате")
        void filterByStartDate() {
            MessageFilter filter = new MessageFilter()
                    .withStartDate(LocalDate.of(2025, 7, 1));

            assertThat(filter.matches(message1)).isFalse();
            assertThat(filter.matches(message2)).isTrue();
        }

        @Test
        @DisplayName("Фильтрует по конечной дате")
        void filterByEndDate() {
            MessageFilter filter = new MessageFilter()
                    .withEndDate(LocalDate.of(2025, 6, 30));

            assertThat(filter.matches(message1)).isTrue();
            assertThat(filter.matches(message2)).isFalse();
        }
    }

    @Nested
    @DisplayName("Фильтрация по ключевым словам (Fluent API)")
    class KeywordFilterTests {

        @Test
        @DisplayName("Фильтрует по ключевому слову (включая)")
        void filterByKeyword() {
            MessageFilter filter = new MessageFilter()
                    .withKeyword("hello");

            assertThat(filter.matches(message1)).isTrue();
            assertThat(filter.matches(message2)).isFalse();
        }

        @Test
        @DisplayName("Фильтрует по ключевым словам (исключая)")
        void filterByExcludeKeyword() {
            MessageFilter filter = new MessageFilter()
                    .withExcludeKeyword("goodbye");

            assertThat(filter.matches(message1)).isTrue();
            assertThat(filter.matches(message2)).isFalse();
        }

        @Test
        @DisplayName("Фильтрует без учета регистра")
        void filterCaseInsensitive() {
            MessageFilter filter = new MessageFilter()
                    .withKeyword("HELLO");

            assertThat(filter.matches(message1)).isTrue();
        }
    }

    @Nested
    @DisplayName("Фильтрация по типу сообщения")
    class TypeFilterTests {

        @Test
        @DisplayName("Фильтрует по типу (включая)")
        void filterByIncludeType() {
            MessageFilter filter = new MessageFilter()
                    .withIncludeType("service");

            assertThat(filter.matches(serviceMessage)).isTrue();
            assertThat(filter.matches(message1)).isFalse();
        }

        @Test
        @DisplayName("Фильтрует по типу (исключая)")
        void filterByExcludeType() {
            MessageFilter filter = new MessageFilter()
                    .withExcludeType("service");

            assertThat(filter.matches(serviceMessage)).isFalse();
            assertThat(filter.matches(message1)).isTrue();
        }
    }

    @Nested
    @DisplayName("Кастомные предикаты (OCP)")
    class CustomPredicates {

        @Test
        @DisplayName("Пропускает сообщения по кастомному предикату (ID > 1)")
        void filterByCustomPredicate() {
            MessageFilter filter = new MessageFilter()
                    .withPredicate(node -> node.get("id").asInt() > 1);

            assertThat(filter.matches(message1)).isFalse();
            assertThat(filter.matches(message2)).isTrue();
        }

        @Test
        @DisplayName("Несколько предикатов комбинируются через AND")
        void multiplePredicates() {
            MessageFilter filter = new MessageFilter()
                    .withPredicate(node -> node.get("id").asInt() > 1)
                    .withPredicate(node -> node.get("text").asText().contains("Goodbye"));

            assertThat(filter.matches(message2)).isTrue();
            
            JsonNode msg3 = objectMapper.createObjectNode()
                    .put("id", 3)
                    .put("text", "Just hello");
            assertThat(filter.matches(msg3)).isFalse();
        }
    }

    @Test
    @DisplayName("Фильтрует список сообщений")
    void filterMessageList() {
        List<JsonNode> messages = List.of(message1, message2, serviceMessage);
        
        MessageFilter filter = new MessageFilter()
                .withStartDate(LocalDate.of(2025, 7, 1));

        List<JsonNode> result = filter.filter(messages);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id").asInt()).isEqualTo(2);
    }
}
