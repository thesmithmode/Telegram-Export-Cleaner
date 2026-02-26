package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MessageFilter - Фильтрация сообщений")
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

    @Nested
    @DisplayName("Фильтрация по дате")
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

        @Test
        @DisplayName("Фильтрует по диапазону дат")
        void filterByDateRange() {
            MessageFilter filter = new MessageFilter()
                    .withStartDate(LocalDate.of(2025, 6, 1))
                    .withEndDate(LocalDate.of(2025, 6, 30));

            assertThat(filter.matches(message1)).isTrue();
            assertThat(filter.matches(message2)).isFalse();
        }
    }

    @Nested
    @DisplayName("Фильтрация по ключевым словам")
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
        @DisplayName("Фильтрует по ключевым словам (несколько)")
        void filterByMultipleKeywords() {
            MessageFilter filter = new MessageFilter()
                    .withKeyword("hello")
                    .withKeyword("goodbye");

            assertThat(filter.matches(message1)).isTrue();
            assertThat(filter.matches(message2)).isTrue();
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
    @DisplayName("Комбинированные фильтры")
    class CombinedFilterTests {

        @Test
        @DisplayName("Комбинирует дату и ключевые слова")
        void combineDateAndKeyword() {
            MessageFilter filter = new MessageFilter()
                    .withStartDate(LocalDate.of(2025, 6, 1))
                    .withKeyword("hello");

            assertThat(filter.matches(message1)).isTrue();
            assertThat(filter.matches(message2)).isFalse();
        }

        @Test
        @DisplayName("Фильтрует список сообщений")
        void filterMessageList() throws Exception {
            JsonNode msg1 = objectMapper.readTree("""
                {"id": 1, "type": "message", "date": "2025-06-24T10:00:00", "text": "Hello"}
                """);
            JsonNode msg2 = objectMapper.readTree("""
                {"id": 2, "type": "message", "date": "2025-07-01T10:00:00", "text": "World"}
                """);
            JsonNode msg3 = objectMapper.readTree("""
                {"id": 3, "type": "message", "date": "2025-07-15T10:00:00", "text": "Test"}
                """);

            List<JsonNode> messages = List.of(msg1, msg2, msg3);
            
            MessageFilter filter = new MessageFilter()
                    .withStartDate(LocalDate.of(2025, 7, 1))
                    .withEndDate(LocalDate.of(2025, 7, 10));

            List<JsonNode> result = filter.filter(messages);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("id").asInt()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Граничные случаи")
    class EdgeCases {

        @Test
        @DisplayName("Возвращает false для null сообщения")
        void returnsFalseForNull() {
            MessageFilter filter = new MessageFilter();
            assertThat(filter.matches(null)).isFalse();
        }

        @Test
        @DisplayName("Возвращает пустой список для null входных данных")
        void returnsEmptyForNullInput() {
            MessageFilter filter = new MessageFilter();
            assertThat(filter.filter(null)).isEmpty();
        }

        @Test
        @DisplayName("Пустой фильтр пропускает все сообщения")
        void emptyFilterPassesAll() {
            MessageFilter filter = new MessageFilter();
            assertThat(filter.matches(message1)).isTrue();
            assertThat(filter.matches(message2)).isTrue();
            assertThat(filter.matches(serviceMessage)).isTrue();
        }
    }
}
