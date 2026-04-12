package com.tcleaner.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportBotTest {

    private ExportJobProducer jobProducerMock;
    private BotMessenger messengerMock;
    private ExportBot bot;

    @BeforeEach
    void setUp() {
        jobProducerMock = mock(ExportJobProducer.class);
        messengerMock = mock(BotMessenger.class);

        when(jobProducerMock.getActiveExport(anyLong())).thenReturn(null);
        when(jobProducerMock.enqueue(anyLong(), anyLong(), any(String.class), isNull(), isNull()))
                .thenReturn("export_test_id");
        when(jobProducerMock.enqueue(anyLong(), anyLong(), any(String.class), any(String.class), any(String.class)))
                .thenReturn("export_test_id");
        when(jobProducerMock.isLikelyCached(any())).thenReturn(false);
        when(jobProducerMock.getQueueLength()).thenReturn(0L);
        when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
        when(messengerMock.sendWithKeyboardGetId(anyLong(), anyString(), any())).thenReturn(42);

        bot = new ExportBot("token", jobProducerMock, messengerMock);
    }

    @Test
    @DisplayName("При старте регистрируются slash-команды Telegram")
    void testRegistersSlashCommandsOnStartup() {
        bot.registerBotCommands();
        verify(messengerMock).setMyCommands(any());
    }

    @Nested
    @DisplayName("Ввод идентификатора чата")
    class ChatIdentifierInput {

        @Test
        @DisplayName("@username переводит в AWAITING_DATE_CHOICE и показывает inline-меню")
        void testAtUsername() {
            bot.consume(createTextMessageUpdate(123L, "@test_chat"));

            verify(messengerMock).sendWithKeyboard(
                    eq(123L),
                    contains("Чат: @test_chat"),
                    any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("Ссылка t.me показывает inline-меню выбора диапазона")
        void testTmeLink() {
            bot.consume(createTextMessageUpdate(123L, "https://t.me/public_channel"));

            verify(messengerMock).sendWithKeyboard(
                    eq(123L),
                    contains("@public_channel"),
                    any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("Числовой ID отклоняется")
        void testNumericRejected() {
            bot.consume(createTextMessageUpdate(123L, "-1001234567890"));

            verify(messengerMock).send(eq(123L), contains("Неверный формат"));
        }

        @Test
        @DisplayName("Username без @ отклоняется")
        void testUsernameWithoutAtRejected() {
            bot.consume(createTextMessageUpdate(123L, "public_channel"));

            verify(messengerMock).send(eq(123L), contains("Неверный формат"));
        }
    }

    @Nested
    @DisplayName("Wizard выбора диапазона через callback-кнопки")
    class CallbackWizard {

        @Test
        @DisplayName("CB_EXPORT_ALL запускает экспорт всего чата")
        void testExportAll() {
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(123L, 123L, "my_channel", null, null);
            verify(messengerMock).editMessage(
                    eq(123L), anyInt(), contains("Задача принята"), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("Сообщение о принятии задачи содержит имя чата, а не null")
        void testResultShowsChatDisplay() {
            bot.consume(createTextMessageUpdate(123L, "@crypto_hd"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_EXPORT_ALL));

            verify(messengerMock).editMessage(
                    eq(123L), anyInt(), contains("@crypto_hd"), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("CB_DATE_RANGE переводит в AWAITING_FROM_DATE")
        void testDateRange() {
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_DATE_RANGE));

            verify(messengerMock).editMessage(
                    eq(123L), anyInt(), contains("начальную дату"), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("CB_FROM_START → ввод конечной даты")
        void testFromStart() {
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_DATE_RANGE));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_FROM_START));

            verify(messengerMock).editMessage(
                    eq(123L), anyInt(), contains("конечную дату"), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("CB_TO_TODAY запускает экспорт с fromDate, toDate=null")
        void testToToday() {
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_DATE_RANGE));
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_TO_TODAY));

            verify(jobProducerMock).enqueue(
                    eq(123L), eq(123L), eq("my_channel"), contains("2024-01-01"), isNull());
        }

        @Test
        @DisplayName("CB_CANCEL_EXPORT отменяет экспорт")
        void testCancelCallback() {
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_CANCEL_EXPORT));

            verify(jobProducerMock).cancelExport(123L);
            verify(messengerMock).editMessage(
                    eq(123L), anyInt(), contains("отменён"), isNull());
        }
    }

    @Nested
    @DisplayName("Ручной ввод дат (после CB_DATE_RANGE)")
    class ManualDateInput {

        @Test
        @DisplayName("Полный flow: @chan → CB_DATE_RANGE → 01.01.2024 → 31.12.2024 → enqueue")
        void testManualDateFlow() {
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_DATE_RANGE));
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));
            bot.consume(createTextMessageUpdate(123L, "31.12.2024"));

            verify(jobProducerMock).enqueue(
                    eq(123L), eq(123L), eq("my_channel"),
                    contains("2024-01-01"), contains("2024-12-31"));
        }

        @Test
        @DisplayName("Неверный формат начальной даты отклоняется")
        void testInvalidFromDate() {
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_DATE_RANGE));
            bot.consume(createTextMessageUpdate(123L, "2024-01-01"));

            verify(messengerMock, atLeastOnce()).send(eq(123L), contains("Неверный формат"));
        }

        @Test
        @DisplayName("Неверный формат конечной даты отклоняется")
        void testInvalidToDate() {
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_DATE_RANGE));
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));
            bot.consume(createTextMessageUpdate(123L, "31-12-2024"));

            verify(messengerMock, atLeastOnce()).send(eq(123L), contains("Неверный формат"));
        }
    }

    @Nested
    @DisplayName("Команды управления и сессии")
    class CommandsAndSessions {

        @Test
        @DisplayName("/start снимает устаревшую reply-клавиатуру")
        void testStartRemovesReplyKeyboard() {
            bot.consume(createTextMessageUpdate(123L, "/start"));

            verify(messengerMock).sendRemoveReplyKeyboard(eq(123L), contains("Этот бот экспортирует"));
        }

        @Test
        @DisplayName("/cancel отменяет активный экспорт")
        void testCancelActive() {
            when(jobProducerMock.getActiveExport(123L)).thenReturn("export_xyz");

            bot.consume(createTextMessageUpdate(123L, "/cancel"));

            verify(jobProducerMock).cancelExport(123L);
            verify(messengerMock).send(eq(123L), contains("отменён"));
        }

        @Test
        @DisplayName("/cancel без активного экспорта — сообщение об отсутствии")
        void testCancelNoActive() {
            bot.consume(createTextMessageUpdate(123L, "/cancel"));

            verify(jobProducerMock, never()).cancelExport(anyLong());
            verify(messengerMock).send(eq(123L), contains("Нет активного экспорта"));
        }

        @Test
        @DisplayName("Дублирующий экспорт блокируется на этапе identifier")
        void testDuplicateBlocked() {
            when(jobProducerMock.getActiveExport(123L)).thenReturn("export_existing");

            bot.consume(createTextMessageUpdate(123L, "@my_channel"));

            verify(messengerMock).send(eq(123L), contains("уже есть активный экспорт"));
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Сообщение из группы игнорируется")
        void testGroupMessageIgnored() {
            Update update = new Update();
            update.setUpdateId(1);
            Message message = new Message();
            message.setMessageId(1);
            message.setText("test");
            Chat chat = Chat.builder().id(123L).type("group").build();
            message.setChat(chat);
            User user = User.builder().id(456L).firstName("Test").isBot(false).build();
            message.setFrom(user);
            update.setMessage(message);

            bot.consume(update);

            verify(messengerMock, never()).send(anyLong(), any());
            verify(messengerMock, never()).sendWithKeyboard(anyLong(), any(), any());
        }

        @Test
        @DisplayName("Успешный экспорт сохраняет queue msg id для progress-трекинга")
        void testStoreQueueMsgId() {
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_EXPORT_ALL));

            // editMessageId from callback > 0, т.е. storeQueueMsgId вызовется с этим id
            verify(jobProducerMock, atLeast(1))
                    .storeQueueMsgId(eq("export_test_id"), eq(123L), anyInt());
        }
    }

    // ============ Helpers ============

    private Update createTextMessageUpdate(long userId, String text) {
        Update update = new Update();
        update.setUpdateId(1);
        Message message = new Message();
        message.setMessageId(1);
        message.setText(text);
        Chat chat = Chat.builder().id(userId).type("private").build();
        message.setChat(chat);
        User user = User.builder().id(userId).firstName("Test").isBot(false).build();
        message.setFrom(user);
        update.setMessage(message);
        return update;
    }

    private Update createCallbackUpdate(long userId, String data) {
        Update update = new Update();
        update.setUpdateId(2);

        Message callbackMessage = new Message();
        callbackMessage.setMessageId(777);
        Chat chat = Chat.builder().id(userId).type("private").build();
        callbackMessage.setChat(chat);

        User user = User.builder().id(userId).firstName("Test").isBot(false).build();

        CallbackQuery cb = new CallbackQuery();
        cb.setId("cb_" + data);
        cb.setFrom(user);
        cb.setData(data);
        cb.setMessage(callbackMessage);

        update.setCallbackQuery(cb);
        return update;
    }
}
