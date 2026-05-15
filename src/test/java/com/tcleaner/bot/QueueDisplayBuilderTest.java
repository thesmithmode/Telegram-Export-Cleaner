package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.telegram.telegrambots.meta.api.objects.User;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QueueDisplayBuilder — pure formatters for queue/date/displayName")
class QueueDisplayBuilderTest {

    private QueueDisplayBuilder builder;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:bot_messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        ms.setDefaultLocale(java.util.Locale.ENGLISH);
        BotI18n i18n = new BotI18n(ms);
        builder = new QueueDisplayBuilder(i18n);
    }

    @Nested
    @DisplayName("build — позиция в очереди")
    class BuildQueueText {

        @Test
        @DisplayName("fromCache=true — рендерит cached-сообщение независимо от очереди")
        void cached() {
            String result = builder.build(BotLanguage.RU, true, 5, true);
            assertThat(result).isNotBlank();
            // Контракт: cached-сообщение не содержит числовых позиций
            assertThat(result).doesNotContain("позиц");
        }

        @Test
        @DisplayName("pending=1, hasActive=false — текущая jobs одна, стартуем")
        void startingWhenAlone() {
            String result = builder.build(BotLanguage.RU, false, 1, false);
            assertThat(result).isNotBlank();
        }

        @Test
        @DisplayName("pending=3, hasActive=true — впереди 3 (2 in queue + 1 active), сам 4-й")
        void positionCalculation() {
            String result = builder.build(BotLanguage.RU, false, 3, true);
            // i18n позиция формата "{0}-я / впереди {1}" — проверяем что числа в строке
            assertThat(result).contains("4").contains("3");
        }
    }

    @Nested
    @DisplayName("dateInfo — рендер выбранного периода")
    class DateInfoText {

        @Test
        @DisplayName("оба null — пустая строка (нет prefix)")
        void emptyWhenBothNull() {
            UserSession session = new UserSession();
            assertThat(builder.dateInfo(BotLanguage.RU, session)).isEmpty();
        }

        @Test
        @DisplayName("from задан, to=null — формат 'от X до сегодня'")
        void fromSetToOpen() {
            UserSession session = new UserSession();
            session.setFromDate("2025-01-15T00:00:00");
            String result = builder.dateInfo(BotLanguage.RU, session);
            assertThat(result).isNotBlank();
            assertThat(result).contains("15");
        }

        @Test
        @DisplayName("to задан, from=null — формат 'от начала чата до Y'")
        void fromOpenToSet() {
            UserSession session = new UserSession();
            session.setToDate("2025-06-30T23:59:59");
            String result = builder.dateInfo(BotLanguage.RU, session);
            assertThat(result).isNotBlank();
            assertThat(result).contains("30");
        }

        @Test
        @DisplayName("оба заданы — оба дня в строке")
        void bothSet() {
            UserSession session = new UserSession();
            session.setFromDate("2025-01-15T00:00:00");
            session.setToDate("2025-06-30T23:59:59");
            String result = builder.dateInfo(BotLanguage.RU, session);
            assertThat(result).contains("15").contains("30");
        }
    }

    @Nested
    @DisplayName("displayName — first+last с пробелом, null если оба пустые")
    class DisplayNameStatic {

        @Test
        void bothPresent() {
            User u = mockUser("Анна", "Иванова");
            assertThat(QueueDisplayBuilder.displayName(u)).isEqualTo("Анна Иванова");
        }

        @Test
        void firstOnly() {
            User u = mockUser("Анна", null);
            assertThat(QueueDisplayBuilder.displayName(u)).isEqualTo("Анна");
        }

        @Test
        void lastOnly() {
            User u = mockUser(null, "Иванова");
            assertThat(QueueDisplayBuilder.displayName(u)).isEqualTo("Иванова");
        }

        @Test
        void bothNull() {
            User u = mockUser(null, null);
            assertThat(QueueDisplayBuilder.displayName(u)).isNull();
        }

        @Test
        @DisplayName("blank first игнорируется (защита от пустых строк из API)")
        void blankFirstIgnored() {
            User u = mockUser("  ", "Иванова");
            assertThat(QueueDisplayBuilder.displayName(u)).isEqualTo("Иванова");
        }

        @Test
        @DisplayName("blank last игнорируется без trailing-space")
        void blankLastIgnored() {
            User u = mockUser("Анна", "   ");
            assertThat(QueueDisplayBuilder.displayName(u)).isEqualTo("Анна");
        }

        private User mockUser(String first, String last) {
            // telegrambots 9.5.0 — у User нет no-arg constructor, только Lombok @SuperBuilder.
            // firstName помечен @NonNull в RequiredArgsConstructor, но Builder допускает null
            // (передадим пустую строку для null-кейса чтобы не сломать build, потом setFirstName(null)).
            User u = User.builder()
                    .id(1L)
                    .firstName(first != null ? first : "")
                    .isBot(false)
                    .build();
            // Возвращаем точное значение first (включая null) — production-флоу видит реальный null.
            u.setFirstName(first);
            u.setLastName(last);
            return u;
        }
    }
}
