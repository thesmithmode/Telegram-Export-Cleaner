package com.tcleaner.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarkdownParser")
class MarkdownParserTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("Парсинг text_link с валидацией URL")
    class ParseTextLinkWithValidation {

        @Test
        @DisplayName("Парсит безопасный text_link")
        void parsesSecureTextLink() throws Exception {
            String json = "{\"type\": \"text_link\", \"text\": \"click me\", \"href\": \"https://example.com\"}";
            JsonNode entity = objectMapper.readTree(json);

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("[click me](https://example.com)");
        }

        @Test
        @DisplayName("Блокирует javascript: XSS")
        void blocksJavascriptXss() throws Exception {
            String json = "{\"type\": \"text_link\", \"text\": \"click\", \"href\": \"javascript:alert('xss')\"}";
            JsonNode entity = objectMapper.readTree(json);

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("[click](#)");
        }

        @Test
        @DisplayName("Блокирует data: XSS")
        void blocksDataXss() throws Exception {
            String json = "{\"type\": \"text_link\", \"text\": \"malicious\", \"href\": \"data:text/html,<script>alert(1)</script>\"}";
            JsonNode entity = objectMapper.readTree(json);

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("[malicious](#)");
        }

        @Test
        @DisplayName("Блокирует vbscript: XSS")
        void blocksVbscriptXss() throws Exception {
            String json = "{\"type\": \"text_link\", \"text\": \"link\", \"href\": \"vbscript:msgbox('xss')\"}";
            JsonNode entity = objectMapper.readTree(json);

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("[link](#)");
        }

        @Test
        @DisplayName("Разрешает относительные ссылки")
        void allowsRelativeLinks() throws Exception {
            String json = "{\"type\": \"text_link\", \"text\": \"relative\", \"href\": \"/path/to/page\"}";
            JsonNode entity = objectMapper.readTree(json);

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("[relative](/path/to/page)");
        }

        @Test
        @DisplayName("Разрешает mailto: ссылки")
        void allowsMailtoLinks() throws Exception {
            String json = "{\"type\": \"text_link\", \"text\": \"email\", \"href\": \"mailto:test@example.com\"}";
            JsonNode entity = objectMapper.readTree(json);

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("[email](mailto:test@example.com)");
        }

        @Test
        @DisplayName("Разрешает Telegram ссылки")
        void allowsTelegramLinks() throws Exception {
            String json = "{\"type\": \"text_link\", \"text\": \"tg\", \"href\": \"tg://user?id=123\"}";
            JsonNode entity = objectMapper.readTree(json);

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("[tg](tg://user?id=123)");
        }
    }

    @Nested
    @DisplayName("Парсинг других типов сущностей")
    class ParseOtherEntities {

        @Test
        void parsesBold() throws Exception {
            String json = "{\"type\": \"bold\", \"text\": \"bold text\"}";
            JsonNode entity = objectMapper.readTree(json);
            assertThat(MarkdownParser.parseEntity(entity)).isEqualTo("**bold text**");
        }

        @Test
        void parsesItalic() throws Exception {
            String json = "{\"type\": \"italic\", \"text\": \"italic text\"}";
            JsonNode entity = objectMapper.readTree(json);
            assertThat(MarkdownParser.parseEntity(entity)).isEqualTo("*italic text*");
        }

        @Test
        void parsesCode() throws Exception {
            String json = "{\"type\": \"code\", \"text\": \"code\"}";
            JsonNode entity = objectMapper.readTree(json);
            assertThat(MarkdownParser.parseEntity(entity)).isEqualTo("`code`");
        }

        @Test
        void parsesPlain() throws Exception {
            String json = "{\"type\": \"plain\", \"text\": \"plain text\"}";
            JsonNode entity = objectMapper.readTree(json);
            assertThat(MarkdownParser.parseEntity(entity)).isEqualTo("plain text");
        }
    }

    @Nested
    @DisplayName("Парсинг null и пустых значений")
    class ParseNullAndEmpty {

        @Test
        void handlesNullEntity() {
            String result = MarkdownParser.parseEntity(null);
            assertThat(result).isEmpty();
        }

        @Test
        void handlesNullText() throws Exception {
            String json = "{\"type\": \"bold\"}";
            JsonNode entity = objectMapper.readTree(json);
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("****");
        }
    }
}
