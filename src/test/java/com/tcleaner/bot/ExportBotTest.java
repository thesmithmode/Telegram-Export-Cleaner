package com.tcleaner.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.ChatShared;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExportBot.
 */
@DisplayName("ExportBot")
class ExportBotTest {

    private ExportBot bot;
    private ExportJobProducer jobProducerMock;

    @BeforeEach
    void setUp() {
        jobProducerMock = mock(ExportJobProducer.class);
        bot = new ExportBot("test_token", "test_bot", jobProducerMock);
    }

    @DisplayName("Should return correct bot username")
    @Test
    void testGetBotUsername() {
        assertThat(bot.getBotUsername()).isEqualTo("test_bot");
    }

    // === Прямой ввод (основной flow) ===

    @Nested
    @DisplayName("Прямой ввод идентификатора чата")
    class DirectInput {

        @DisplayName("Should handle direct username input")
        @Test
        void testDirectUsername() {
            when(jobProducerMock.enqueue(123L, 123L, "strbypass", null, null))
                    .thenReturn("export_task_123");

            // Отправляем username → переходим в AWAITING_DATE_CHOICE
            bot.onUpdateReceived(createUpdate("strbypass"));
            // Нажимаем "Весь чат" → запуск экспорта
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(123L, 123L, "strbypass", null, null);
        }

        @DisplayName("Should handle direct @username input")
        @Test
        void testDirectAtUsername() {
            when(jobProducerMock.enqueue(123L, 123L, "durov", null, null))
                    .thenReturn("export_task_123");

            bot.onUpdateReceived(createUpdate("@durov"));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(123L, 123L, "durov", null, null);
        }

        @DisplayName("Should handle direct t.me link input")
        @Test
        void testDirectTmeLink() {
            when(jobProducerMock.enqueue(123L, 123L, "strbypass", null, null))
                    .thenReturn("export_task_123");

            bot.onUpdateReceived(createUpdate("https://t.me/strbypass"));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(123L, 123L, "strbypass", null, null);
        }

        @DisplayName("Should handle direct numeric chat ID")
        @Test
        void testDirectNumericId() {
            when(jobProducerMock.enqueue(123L, 123L, -1001234567890L, null, null))
                    .thenReturn("export_task_123");

            bot.onUpdateReceived(createUpdate("-1001234567890"));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(123L, 123L, -1001234567890L, null, null);
        }
    }

    // === /export (обратная совместимость) ===

    @Nested
    @DisplayName("/export команда")
    class ExportCommand {

        @DisplayName("Should handle /export with valid chat ID")
        @Test
        void testExportCommandWithValidId() {
            when(jobProducerMock.enqueue(123L, 123L, -1001234567890L, null, null))
                    .thenReturn("export_task_123");

            bot.onUpdateReceived(createUpdate("/export -1001234567890"));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(123L, 123L, -1001234567890L, null, null);
        }

        @DisplayName("Should handle /export without argument — show help")
        @Test
        void testExportCommandWithoutId() {
            bot.onUpdateReceived(createUpdate("/export"));

            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString());
        }
    }

    // === Ограничение: только private chat ===

    @Nested
    @DisplayName("Ограничение приватным чатом")
    class PrivateChatRestriction {

        @DisplayName("Should ignore messages from group chats")
        @Test
        void testIgnoreGroupChat() {
            bot.onUpdateReceived(createUpdate("strbypass", "group"));

            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString());
        }

        @DisplayName("Should ignore messages from supergroup chats")
        @Test
        void testIgnoreSupergroupChat() {
            bot.onUpdateReceived(createUpdate("strbypass", "supergroup"));

            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString());
        }
    }

    // === Wizard: выбор дат ===

    @Nested
    @DisplayName("Wizard: диапазон дат")
    class DateRangeWizard {

        @DisplayName("Should handle date range flow")
        @Test
        void testDateRangeFlow() {
            // from_date = "2024-01-01T00:00", to_date = "2025-12-31T23:59:59"
            when(jobProducerMock.enqueue(eq(123L), eq(123L), eq("durov"),
                    eq("2024-01-01T00:00"), eq("2025-12-31T23:59:59")))
                    .thenReturn("export_task_123");

            // 1. Выбрать чат
            bot.onUpdateReceived(createUpdate("durov"));
            // 2. Нажать "Указать диапазон дат"
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_DATE_RANGE));
            // 3. Ввести начальную дату
            bot.onUpdateReceived(createUpdate("01.01.2024"));
            // 4. Ввести конечную дату
            bot.onUpdateReceived(createUpdate("31.12.2025"));

            verify(jobProducerMock).enqueue(eq(123L), eq(123L), eq("durov"),
                    eq("2024-01-01T00:00"), eq("2025-12-31T23:59:59"));
        }

        @DisplayName("Should handle 'С начала чата' + 'До сегодня'")
        @Test
        void testFromStartToToday() {
            when(jobProducerMock.enqueue(123L, 123L, "durov", null, null))
                    .thenReturn("export_task_123");

            bot.onUpdateReceived(createUpdate("durov"));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_DATE_RANGE));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_FROM_START));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_TO_TODAY));

            verify(jobProducerMock).enqueue(123L, 123L, "durov", null, null);
        }
    }

    // === Валидация дат ===

    @Nested
    @DisplayName("Валидация дат")
    class DateValidation {

        @DisplayName("parseDate — valid date")
        @Test
        void testParseDateValid() {
            LocalDate date = ExportBot.parseDate("15.06.2025");
            assertThat(date).isNotNull();
            assertThat(date.getYear()).isEqualTo(2025);
            assertThat(date.getMonthValue()).isEqualTo(6);
            assertThat(date.getDayOfMonth()).isEqualTo(15);
        }

        @DisplayName("parseDate — invalid format")
        @Test
        void testParseDateInvalid() {
            assertThat(ExportBot.parseDate("2025-06-15")).isNull();
            assertThat(ExportBot.parseDate("not a date")).isNull();
            assertThat(ExportBot.parseDate("32.13.2025")).isNull();
        }

        @DisplayName("Should reject invalid date during wizard and not crash")
        @Test
        void testInvalidDateDuringWizard() {
            bot.onUpdateReceived(createUpdate("durov"));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_DATE_RANGE));
            // Неверная дата
            bot.onUpdateReceived(createUpdate("not-a-date"));

            // Не должен вызвать enqueue
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString(),
                    anyString(), anyString());
        }
    }

    // === Кнопка "Назад" ===

    @Nested
    @DisplayName("Кнопка Назад")
    class BackButton {

        @DisplayName("Back from date choice resets to main")
        @Test
        void testBackFromDateChoice() {
            bot.onUpdateReceived(createUpdate("durov"));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_BACK_TO_MAIN));

            // После "Назад" — ввод нового чата должен работать
            when(jobProducerMock.enqueue(123L, 123L, "telegram", null, null))
                    .thenReturn("task_2");

            bot.onUpdateReceived(createUpdate("telegram"));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(123L, 123L, "telegram", null, null);
        }

        @DisplayName("Back from from_date returns to date choice")
        @Test
        void testBackFromFromDate() {
            bot.onUpdateReceived(createUpdate("durov"));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_DATE_RANGE));
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_BACK_TO_DATE_CHOICE));

            // Теперь можно нажать "Весь чат"
            when(jobProducerMock.enqueue(123L, 123L, "durov", null, null))
                    .thenReturn("task_3");
            bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(123L, 123L, "durov", null, null);
        }
    }

    // === extractUsername ===

    @Nested
    @DisplayName("extractUsername")
    class ExtractUsername {

        @DisplayName("t.me link")
        @Test
        void testExtractFromLink() {
            assertThat(ExportBot.extractUsername("https://t.me/strbypass")).isEqualTo("strbypass");
            assertThat(ExportBot.extractUsername("http://t.me/durov")).isEqualTo("durov");
        }

        @DisplayName("@username")
        @Test
        void testExtractFromAt() {
            assertThat(ExportBot.extractUsername("@durov")).isEqualTo("durov");
        }

        @DisplayName("числовой ID возвращает null")
        @Test
        void testExtractFromNumericId() {
            assertThat(ExportBot.extractUsername("-1001234567890")).isNull();
            assertThat(ExportBot.extractUsername("123456789")).isNull();
        }

        @DisplayName("простой username")
        @Test
        void testExtractFromPlainUsername() {
            assertThat(ExportBot.extractUsername("strbypass")).isEqualTo("strbypass");
        }
    }

    // === Прочие кейсы ===

    @DisplayName("Should handle /start command")
    @Test
    void testStartCommand() {
        bot.onUpdateReceived(createUpdate("/start"));
        // Should not throw
    }

    @DisplayName("Should handle unknown slash command")
    @Test
    void testUnknownCommand() {
        bot.onUpdateReceived(createUpdate("/unknown"));
        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
    }

    @DisplayName("Should handle message without text")
    @Test
    void testMessageWithoutText() {
        Update update = new Update();
        Message message = new Message();
        message.setChat(createChat("private"));
        message.setFrom(createUser());
        update.setMessage(message);

        bot.onUpdateReceived(update);
        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
    }

    @DisplayName("Should handle update without message")
    @Test
    void testUpdateWithoutMessage() {
        Update update = new Update();
        bot.onUpdateReceived(update);
        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
    }

    // === ChatShared ===

    @DisplayName("Should handle ChatShared from picker")
    @Test
    void testChatSharedHandling() {
        when(jobProducerMock.enqueue(123L, 123L, -1009999999L, null, null))
                .thenReturn("export_task_shared");

        bot.onUpdateReceived(createChatSharedUpdate(-1009999999L));
        bot.onUpdateReceived(createCallbackUpdate(ExportBot.CB_EXPORT_ALL));

        verify(jobProducerMock).enqueue(123L, 123L, -1009999999L, null, null);
    }

    // === Helpers ===

    private Update createUpdate(String text) {
        return createUpdate(text, "private");
    }

    private Update createUpdate(String text, String chatType) {
        Update update = new Update();
        Message message = new Message();
        message.setFrom(createUser());
        message.setChat(createChat(chatType));
        message.setText(text);
        message.setMessageId(1);
        update.setMessage(message);
        return update;
    }

    private Update createCallbackUpdate(String callbackData) {
        Update update = new Update();
        CallbackQuery callback = new CallbackQuery();
        callback.setId("cb_1");
        callback.setData(callbackData);
        callback.setFrom(createUser());

        Message message = new Message();
        message.setMessageId(100);
        message.setChat(createChat("private"));
        callback.setMessage(message);

        update.setCallbackQuery(callback);
        return update;
    }

    private Update createChatSharedUpdate(long sharedChatId) {
        Update update = new Update();
        Message message = new Message();
        message.setFrom(createUser());
        message.setChat(createChat("private"));
        message.setMessageId(1);

        ChatShared chatShared = new ChatShared();
        chatShared.setRequestId(String.valueOf(1));
        chatShared.setChatId(sharedChatId);
        message.setChatShared(chatShared);

        update.setMessage(message);
        return update;
    }

    private User createUser() {
        User user = new User();
        user.setId(123L);
        user.setIsBot(false);
        return user;
    }

    private Chat createChat(String chatType) {
        Chat chat = new Chat();
        chat.setId(123L);
        chat.setType(chatType);
        return chat;
    }
}
