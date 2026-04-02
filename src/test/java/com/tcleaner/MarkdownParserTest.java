package com.tcleaner;
import com.tcleaner.format.MarkdownParser;

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
 * Тесты для MarkdownParser.
 * 
 * Ожидаемое поведение:
 * - plain → text
 * - bold → **text**
 * - italic → *text*
 * - code → `text`
 * - pre → ```\ntext\n```
 * - link → text
 * - mention → @username
 * - spoiler → ||text||
 * - underline → <u>text</u>
 */
@DisplayName("MarkdownParser")
class MarkdownParserTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("Парсинг простых типов сущностей")
    class ParseSimpleEntities {

        @Test
        @DisplayName("Парсит plain text без изменений")
        void parsesPlainText() throws Exception {
            String text = "Hello world";
            JsonNode entity = createEntity("plain", text);
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("Hello world");
        }

        @Test
        @DisplayName("Парсит bold")
        void parsesBold() throws Exception {
            JsonNode entity = createEntity("bold", "important");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("**important**");
        }

        @Test
        @DisplayName("Парсит italic")
        void parsesItalic() throws Exception {
            JsonNode entity = createEntity("italic", "emphasis");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("*emphasis*");
        }

        @Test
        @DisplayName("Парсит strikethrough")
        void parsesStrikethrough() throws Exception {
            JsonNode entity = createEntity("strikethrough", "deleted");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("~~deleted~~");
        }

        @Test
        @DisplayName("Парсит code (inline)")
        void parsesCode() throws Exception {
            JsonNode entity = createEntity("code", "console.log()");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("`console.log()`");
        }

        @Test
        @DisplayName("Парсит spoiler")
        void parsesSpoiler() throws Exception {
            JsonNode entity = createEntity("spoiler", "secret");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("||secret||");
        }

        @Test
        @DisplayName("Парсит underline")
        void parsesUnderline() throws Exception {
            JsonNode entity = createEntity("underline", "underlined");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("<u>underlined</u>");
        }

        @Test
        @DisplayName("Парсит blockquote")
        void parsesBlockquote() throws Exception {
            JsonNode entity = createEntity("blockquote", "quote");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("> quote");
        }
    }

    @Nested
    @DisplayName("Парсинг ссылок и упоминаний")
    class ParseLinksAndMentions {

        @Test
        @DisplayName("Парсит link")
        void parsesLink() throws Exception {
            JsonNode entity = createEntity("link", "https://example.com");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("https://example.com");
        }

        @Test
        @DisplayName("Парсит mention без @ в тексте (редкий случай)")
        void parsesMentionWithoutAt() throws Exception {
            JsonNode entity = createEntity("mention", "username");

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("@username");
        }

        @Test
        @DisplayName("Парсит mention с @ в тексте — Telegram Desktop уже включает @")
        void parsesMentionWithAt() throws Exception {
            // В реальном Telegram Desktop export поле text уже содержит @username
            JsonNode entity = createEntity("mention", "@sprut_ai");

            String result = MarkdownParser.parseEntity(entity);
            // Не должно быть @@sprut_ai
            assertThat(result).isEqualTo("@sprut_ai");
        }

        @Test
        @DisplayName("Парсит hashtag без # в тексте (редкий случай)")
        void parsesHashtagWithoutHash() throws Exception {
            JsonNode entity = createEntity("hashtag", "java");

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("#java");
        }

        @Test
        @DisplayName("Парсит hashtag с # в тексте (реальный Telegram Desktop export)")
        void parsesHashtagWithHash() throws Exception {
            // Telegram Desktop export уже включает # в text: {"type":"hashtag","text":"#java"}
            JsonNode entity = createEntity("hashtag", "#java");

            String result = MarkdownParser.parseEntity(entity);
            // Не должно быть ##java
            assertThat(result).isEqualTo("#java");
        }

        @Test
        @DisplayName("Парсит cashtag с $ в тексте — не дублирует")
        void parsesCashtagWithDollar() throws Exception {
            JsonNode entity = createEntity("cashtag", "$BTC");

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("$BTC");
        }

        @Test
        @DisplayName("Парсит cashtag без $ — добавляет")
        void parsesCashtagWithoutDollar() throws Exception {
            JsonNode entity = createEntity("cashtag", "BTC");

            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("$BTC");
        }

        @Test
        @DisplayName("Парсит bot_command")
        void parsesBotCommand() throws Exception {
            JsonNode entity = createEntity("bot_command", "/start");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("/start");
        }
    }

    @Nested
    @DisplayName("Fallback и специальные типы")
    class FallbackAndSpecialTypes {

        @Test
        @DisplayName("bank_card маскируется как [CARD]")
        void parsesBankCard() throws Exception {
            JsonNode entity = createEntity("bank_card", "1234567890123456");
            assertThat(MarkdownParser.parseEntity(entity)).isEqualTo("[CARD]");
        }

        @Test
        @DisplayName("Неизвестный тип — возвращает text как есть (fallback)")
        void parsesUnknownTypeAsFallback() throws Exception {
            JsonNode entity = createEntity("future_unknown_type", "some text");
            assertThat(MarkdownParser.parseEntity(entity)).isEqualTo("some text");
        }

        @Test
        @DisplayName("mention_name — возвращает текст без префикса @")
        void parsesMentionName() throws Exception {
            JsonNode entity = createEntity("mention_name", "John Doe");
            assertThat(MarkdownParser.parseEntity(entity)).isEqualTo("John Doe");
        }
    }

    @Nested
    @DisplayName("Парсинг сложных сущностей")
    class ParseComplexEntities {

        @Test
        @DisplayName("Парсит pre (code block) с языком")
        void parsesPreWithLanguage() throws Exception {
            JsonNode entity = objectMapper.readTree("""
                {"type": "pre", "text": "function test() {}", "language": "javascript"}
                """);
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("```javascript\nfunction test() {}\n```");
        }

        @Test
        @DisplayName("Парсит pre (code block) без языка")
        void parsesPreWithoutLanguage() throws Exception {
            JsonNode entity = objectMapper.readTree("""
                {"type": "pre", "text": "some code"}
                """);
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("```\nsome code\n```");
        }

        @Test
        @DisplayName("Парсит text_link")
        void parsesTextLink() throws Exception {
            JsonNode entity = objectMapper.readTree("""
                {"type": "text_link", "text": "click here", "href": "https://example.com"}
                """);
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("[click here](https://example.com)");
        }

        @Test
        @DisplayName("Парсит email")
        void parsesEmail() throws Exception {
            JsonNode entity = createEntity("email", "test@example.com");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("Парсит phone")
        void parsesPhone() throws Exception {
            JsonNode entity = createEntity("phone", "+1234567890");
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("+1234567890");
        }

        @Test
        @DisplayName("Парсит custom_emoji")
        void parsesCustomEmoji() throws Exception {
            JsonNode entity = objectMapper.readTree("""
                {"type": "custom_emoji", "text": "👍", "document_id": "123456789"}
                """);
            
            String result = MarkdownParser.parseEntity(entity);
            assertThat(result).isEqualTo("[emoji_123456789]");
        }
    }

    @Nested
    @DisplayName("Парсинг списка сущностей")
    class ParseEntityList {

        @Test
        @DisplayName("Парсит список с mixed content (текст + ссылка)")
        void parsesMixedContentList() throws Exception {
            List<JsonNode> entities = new ArrayList<>();
            entities.add(createEntity("plain", "Check this: "));
            entities.add(createEntity("link", "https://example.com"));
            
            String result = MarkdownParser.parseEntityList(entities);
            assertThat(result).isEqualTo("Check this: https://example.com");
        }

        @Test
        @DisplayName("Парсит список с bold и plain")
        void parsesBoldAndPlain() throws Exception {
            List<JsonNode> entities = new ArrayList<>();
            entities.add(createEntity("plain", "This is "));
            entities.add(createEntity("bold", "important"));
            entities.add(createEntity("plain", " text"));
            
            String result = MarkdownParser.parseEntityList(entities);
            assertThat(result).isEqualTo("This is **important** text");
        }

        @Test
        @DisplayName("Возвращает пустую строку для пустого списка")
        void returnsEmptyForEmptyList() {
            String result = MarkdownParser.parseEntityList(List.of());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Возвращает пустую строку для null")
        void returnsEmptyForNull() {
            String result = MarkdownParser.parseEntityList(null);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Парсинг текста (text field)")
    class ParseTextField {

        @Test
        @DisplayName("Если text - строка, возвращает её как есть")
        void returnsStringAsIs() throws Exception {
            JsonNode node = objectMapper.readTree("\"Simple text message\"");
            String result = MarkdownParser.parseText(node);
            assertThat(result).isEqualTo("Simple text message");
        }

        @Test
        @DisplayName("Если text - массив, парсит через parseEntityList")
        void parsesArrayAsEntityList() throws Exception {
            JsonNode node = objectMapper.readTree("""
                [
                  {"type": "plain", "text": "Hello "},
                  {"type": "bold", "text": "World"}
                ]
                """);
            
            String result = MarkdownParser.parseText(node);
            assertThat(result).isEqualTo("Hello **World**");
        }

        @Test
        @DisplayName("Если text - null узел, возвращает пустую строку")
        void returnsEmptyForNullNode() {
            JsonNode node = objectMapper.nullNode();
            String result = MarkdownParser.parseText(node);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Если text - null, возвращает пустую строку")
        void returnsEmptyForNull() {
            String result = MarkdownParser.parseText((JsonNode) null);
            assertThat(result).isEmpty();
        }
    }

    private JsonNode createEntity(String type, String text) {
        return objectMapper.createObjectNode()
            .put("type", type)
            .put("text", text);
    }
}
