package com.tcleaner.bot;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Юнит-тесты для {@link ExportBot}.
 *
 * <p>Покрывает функциональность обработки сообщений пользователей, управления состоянием
 * сессии (AWAITING_CHAT_IDENTIFIER, AWAITING_TO_DATE и т.д.), и взаимодействие с
 * {@link ExportJobProducer} для добавления задач в очередь.</p>
 *
 * <p>Используются моки для {@link ExportJobProducer} и {@link BotMessenger}, чтобы
 * изолировать логику бота от внешних зависимостей.</p>
 */
class ExportBotTest {

    private ExportJobProducer jobProducerMock;
    private BotMessenger messengerMock;
    private ExportBot bot;

    @BeforeEach
    void setUp() throws Exception {
        jobProducerMock = mock(ExportJobProducer.class);
        messengerMock = mock(BotMessenger.class);

        // По умолчанию активных экспортов нет
        when(jobProducerMock.getActiveExport(anyLong())).thenReturn(null);
        when(jobProducerMock.enqueue(anyLong(), anyLong(), any(String.class), isNull(), isNull()))
                .thenReturn("export_test_id");
        when(jobProducerMock.enqueue(anyLong(), anyLong(), any(String.class), any(String.class), any(String.class)))
                .thenReturn("export_test_id");
        when(jobProducerMock.isLikelyCached(any())).thenReturn(false);
        when(jobProducerMock.getQueueLength()).thenReturn(0L);
        when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);

        bot = new ExportBot("token", jobProducerMock, messengerMock);
    }

    @Nested
    @DisplayName("Прямой ввод идентификатора чата")
    class DirectChatIdentifierInput {

        @Test
        @DisplayName("Пользователь может ввести ссылку t.me")
        void testDirectLinkInput() {
            bot.consume(createTextMessageUpdate(123L, "https://t.me/public_channel"));

            // Должно быть запрос даты
            verify(messengerMock).send(eq(123L), contains("Введите дату начала"));
        }

        @Test
        @DisplayName("Пользователь может ввести @username")
        void testDirectAtUsernameInput() {
            bot.consume(createTextMessageUpdate(123L, "@public_channel"));

            // Должно быть запрос даты
            verify(messengerMock).send(eq(123L), contains("Введите дату начала"));
        }

        @Test
        @DisplayName("Числовой ID отклоняется")
        void testNumericIdRejected() {
            bot.consume(createTextMessageUpdate(123L, "-1001234567890"));

            // Должно быть сообщение об ошибке
            verify(messengerMock).send(eq(123L), contains("Неверный формат"));
        }

        @Test
        @DisplayName("Username без @ отклоняется")
        void testUsernameWithoutAtRejected() {
            bot.consume(createTextMessageUpdate(123L, "public_channel"));

            // Должно быть сообщение об ошибке
            verify(messengerMock).send(eq(123L), contains("Неверный формат"));
        }

        @Test
        @DisplayName("Неверный формат отклоняется")
        void testInvalidFormatRejected() {
            bot.consume(createTextMessageUpdate(123L, "123 456 789"));

            // Должно быть сообщение об ошибке
            verify(messengerMock).send(eq(123L), contains("Неверный формат"));
        }
    }

    @Nested
    @DisplayName("Диалог выбора дат")
    class DateRangeDialog {

        @Test
        @DisplayName("Пользователь может ввести дату начала дд.мм.гггг")
        void testFromDateValidFormat() {
            // Шаг 1: выбираем чат
            bot.consume(createTextMessageUpdate(123L, "@test_chat"));
            verify(messengerMock).send(eq(123L), contains("Введите дату начала"));

            // Шаг 2: вводим дату
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));

            // Должен перейти в AWAITING_TO_DATE и запросить дату конца
            verify(messengerMock).send(eq(123L), contains("Введите дату конца"));
        }

        @Test
        @DisplayName("Неверный формат даты отклоняется")
        void testFromDateInvalidFormat() {
            // Шаг 1: выбираем чат
            bot.consume(createTextMessageUpdate(123L, "@test_chat"));

            // Шаг 2: вводим неверный формат
            bot.consume(createTextMessageUpdate(123L, "2024-01-01"));

            // Должно быть сообщение об ошибке (среди отправленных сообщений)
            verify(messengerMock, atLeast(1)).send(eq(123L), contains("Неверный формат"));
        }

        @Test
        @DisplayName("/all переходит прямо в AWAITING_TO_DATE")
        void testFromDateAllCommand() {
            // Шаг 1: выбираем чат
            bot.consume(createTextMessageUpdate(123L, "@test_chat"));

            // Шаг 2: вводим /all (весь чат)
            bot.consume(createTextMessageUpdate(123L, "/all"));

            // Первое сообщение — "Введите дату начала", второе — "Введите дату конца"
            verify(messengerMock).send(eq(123L), contains("Введите дату начала"));
            verify(messengerMock).send(eq(123L), contains("Введите дату конца"));
        }

        @Test
        @DisplayName("/today в AWAITING_TO_DATE запускает экспорт")
        void testToDateTodayCommand() {
            // Шаг 1: выбираем чат
            bot.consume(createTextMessageUpdate(123L, "@test_chat"));

            // Шаг 2: вводим /all
            bot.consume(createTextMessageUpdate(123L, "/all"));

            // Шаг 3: вводим /today
            bot.consume(createTextMessageUpdate(123L, "/today"));

            // Должен быть вызван enqueue и сообщение о принятии задачи
            verify(jobProducerMock).enqueue(123L, 123L, "test_chat", null, null);
            verify(messengerMock).send(eq(123L), contains("Задача принята"));
        }

        @Test
        @DisplayName("Полный flow: username → от даты → до даты → экспорт")
        void testCompleteDateRangeFlow() {
            // Шаг 1: username
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));

            // Шаг 2: дата начала
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));

            // Шаг 3: дата конца
            bot.consume(createTextMessageUpdate(123L, "31.12.2024"));

            // Проверяем вызов enqueue с обеими датами
            verify(jobProducerMock).enqueue(
                    eq(123L), eq(123L), eq("my_channel"),
                    contains("2024-01-01"), contains("2024-12-31")
            );
            verify(messengerMock).send(eq(123L), contains("Задача принята"));
        }

        @Test
        @DisplayName("Неверная дата конца отклоняется")
        void testToDateInvalidFormat() {
            // Шаг 1: username
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));

            // Шаг 2: дата начала
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));

            // Шаг 3: неверный формат даты конца
            bot.consume(createTextMessageUpdate(123L, "31-12-2024"));

            // Должно быть сообщение об ошибке (среди отправленных сообщений)
            verify(messengerMock, atLeast(1)).send(eq(123L), contains("Неверный формат"));
        }
    }

    @Nested
    @DisplayName("Отмена и управление экспортом")
    class CancelExport {

        @Test
        @DisplayName("/cancel отменяет активный экспорт")
        void testCancelActiveExport() {
            // Симулируем активный экспорт
            when(jobProducerMock.getActiveExport(123L)).thenReturn("export_xyz");

            bot.consume(createTextMessageUpdate(123L, "/cancel"));

            verify(jobProducerMock).cancelExport(123L);
            verify(messengerMock).send(eq(123L), contains("отменён"));
        }

        @Test
        @DisplayName("/cancel без активного экспорта отправляет сообщение об ошибке")
        void testCancelWithoutActiveExport() {
            when(jobProducerMock.getActiveExport(123L)).thenReturn(null);

            bot.consume(createTextMessageUpdate(123L, "/cancel"));

            verify(jobProducerMock, never()).cancelExport(anyLong());
            verify(messengerMock).send(eq(123L), contains("Нет активного экспорта"));
        }

        @Test
        @DisplayName("Попытка запустить экспорт при активном показывает сообщение")
        void testDuplicateExportBlocked() {
            when(jobProducerMock.getActiveExport(123L)).thenReturn("export_existing");

            bot.consume(createTextMessageUpdate(123L, "@my_channel"));

            verify(messengerMock).send(eq(123L), contains("уже есть активный экспорт"));
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Race condition: экспорт блокируется на уровне SET NX")
        void testRaceConditionBlocked() {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), any(String.class), any(), any()))
                    .thenThrow(new IllegalStateException("Экспорт уже активен"));

            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createTextMessageUpdate(123L, "/all"));
            bot.consume(createTextMessageUpdate(123L, "/today"));

            verify(messengerMock).send(eq(123L), contains("уже есть активный экспорт"));
        }
    }

    @Nested
    @DisplayName("Сессии и стабильность")
    class SessionManagement {

        @Test
        @DisplayName("Сессия сбрасывается в IDLE после успешного экспорта")
        void testSessionResetAfterExport() {
            // Запускаем полный flow
            bot.consume(createTextMessageUpdate(123L, "@my_channel"));
            bot.consume(createTextMessageUpdate(123L, "/all"));
            bot.consume(createTextMessageUpdate(123L, "/today"));

            // После экспорта сессия должна быть сброшена
            // Проверим это следующим сообщением от пользователя
            bot.consume(createTextMessageUpdate(123L, "@another_channel"));

            // Если сессия была сброшена, то это будет IDLE → AWAITING_FROM_DATE
            verify(messengerMock, times(4)).send(eq(123L), any());
        }

        @Test
        @DisplayName("/start и /help отправляют справку")
        void testStartAndHelpCommands() {
            bot.consume(createTextMessageUpdate(123L, "/start"));

            verify(messengerMock).send(eq(123L), contains("Этот бот экспортирует"));
        }

        @Test
        @DisplayName("consume() не бросает исключение на пустом Update")
        void testConsumeWithoutMessage() {
            Update emptyUpdate = new Update();
            bot.consume(emptyUpdate);

            // Не должно быть исключения
            verify(messengerMock, never()).send(anyLong(), any());
        }

        @Test
        @DisplayName("Сообщение из группы (не private) игнорируется")
        void testGroupMessageIgnored() {
            Update update = new Update();
            update.setUpdateId(1);
            Message message = new Message();
            message.setMessageId(1);
            message.setText("test");
            Chat chat = Chat.builder()
                    .id(123L)
                    .type("group")  // ← не private
                    .build();
            message.setChat(chat);
            User user = User.builder()
                    .id(456L)
                    .firstName("Test")
                    .isBot(false)
                    .build();
            message.setFrom(user);
            update.setMessage(message);

            bot.consume(update);

            // Не должно быть сообщений
            verify(messengerMock, never()).send(anyLong(), any());
        }
    }

    // ============ Helper methods ============

    private Update createTextMessageUpdate(long userId, String text) {
        Update update = new Update();
        update.setUpdateId(1);

        Message message = new Message();
        message.setMessageId(1);
        message.setText(text);

        Chat chat = Chat.builder()
                .id(userId)
                .type("private")
                .build();
        message.setChat(chat);

        User user = User.builder()
                .id(userId)
                .firstName("Test")
                .isBot(false)
                .build();
        message.setFrom(user);

        update.setMessage(message);
        return update;
    }

    @Nested
    @DisplayName("Пересланные сообщения (forward detection)")
    class ForwardedMessageHandling {

        @Test
        @DisplayName("Пересланное сообщение из публичного канала — обрабатывается как обычное")
        void testForwardedFromPublicChannel() throws Exception {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), anyString(), any(), any()))
                    .thenReturn("task_forward");
            when(jobProducerMock.getQueueLength()).thenReturn(1L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);

            Update update = createForwardedMessageUpdate(123L, "public_channel", "Public Channel Title");

            bot.consume(update);

            // Пересланные сообщения обрабатываются как обычные — enqueue не вызывается,
            // т.к. текст "public_channel" не проходит валидацию как username/ссылка
            verify(messengerMock).send(eq(123L), contains("Неверный формат"));
        }

        @Test
        @DisplayName("Пересланное сообщение без username — отправляется ошибка формата")
        void testForwardedFromPrivateChat() throws Exception {
            User user = User.builder().id(123L).isBot(false).firstName("Test").build();
            Chat userChat = Chat.builder().id(123L).type("private").build();
            Chat sourceChat = Chat.builder()
                    .id(-100987654321L)
                    .type("group")
                    .title("Private Group")
                    .build();

            Message m = Message.builder()
                    .messageId(1)
                    .from(user)
                    .chat(userChat)
                    .text("Forwarded")
                    .forwardFromChat(sourceChat)
                    .build();

            Update update = new Update();
            update.setUpdateId(1);
            update.setMessage(m);

            bot.consume(update);

            // enqueue не должен быть вызван (нет валидного идентификатора чата)
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString(), any(), any());
            // Должно быть отправлено сообщение об ошибке формата
            verify(messengerMock, atLeast(1)).send(eq(123L), anyString());
        }

        @Test
        @DisplayName("Пересланное сообщение без информации о чате — безопасная обработка")
        void testForwardedWithoutSourceChat() throws Exception {
            User user = User.builder().id(123L).isBot(false).firstName("Test").build();
            Chat userChat = Chat.builder().id(123L).type("private").build();

            Message m = Message.builder()
                    .messageId(1)
                    .from(user)
                    .chat(userChat)
                    .text("Forwarded")
                    .forwardFromChat(null)
                    .build();

            Update update = new Update();
            update.setUpdateId(1);
            update.setMessage(m);

            // Должен НЕ выбросить исключение
            Assertions.assertDoesNotThrow(() -> {
                bot.consume(update);
            });

            // enqueue не должен быть вызван
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString(), any(), any());
        }
    }

    // ============ Forwarded message helper ============

    private Update createForwardedMessageUpdate(long userId, String channelUsername, String channelTitle) {
        Update update = new Update();
        update.setUpdateId(1);

        Message message = new Message();
        message.setMessageId(1);
        message.setText(channelUsername);

        Chat chat = Chat.builder()
                .id(userId)
                .type("private")
                .build();
        message.setChat(chat);

        User user = User.builder()
                .id(userId)
                .firstName("Test")
                .isBot(false)
                .build();
        message.setFrom(user);

        Chat forwardFromChat = Chat.builder()
                .id(-100123456789L)
                .type("channel")
                .title(channelTitle)
                .build();
        message.setForwardFromChat(forwardFromChat);

        update.setMessage(message);
        return update;
    }

    private Update createForwardedMessageUpdate(long userId, String sourceUsername, String sourceTitle) {
        User user = User.builder().id(userId).isBot(false).firstName("Test").build();
        Chat userChat = Chat.builder().id(userId).type("private").build();

        // Чат-источник (откуда переслано)
        Chat sourceChat = Chat.builder()
                .id(-100123456789L)
                .type("channel")
                .userName(sourceUsername)
                .title(sourceTitle)
                .build();

        Message m = Message.builder()
                .messageId(1)
                .from(user)
                .chat(userChat)
                .text("Forwarded message")
                .forwardFromChat(sourceChat)
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(m);
        return update;
    }

    @Nested
    @DisplayName("Пересланные сообщения (forward detection)")
    class ForwardedMessageHandling {

        @Test
        @DisplayName("Пересланное сообщение из публичного канала — используется username")
        void testForwardedFromPublicChannel() throws Exception {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), anyString(), any(), any()))
                    .thenReturn("task_forward");
            when(jobProducerMock.getQueueLength()).thenReturn(1L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);

            bot.consume(createForwardedMessageUpdate(123L, "public_channel", "Public Channel Title"));

            // Должен вызвать enqueue с username из пересланного сообщения
            verify(jobProducerMock).enqueue(eq(123L), eq(123L), eq("public_channel"), any(), any());
        }

        @Test
        @DisplayName("Пересланное сообщение без username — отправляется ошибка")
        void testForwardedFromPrivateChat() throws Exception {
            User user = User.builder().id(123L).isBot(false).firstName("Test").build();
            Chat userChat = Chat.builder().id(123L).type("private").build();
            Chat sourceChat = Chat.builder()
                    .id(-100987654321L)
                    .type("group")
                    .title("Private Group")
                    .build();

            Message m = Message.builder()
                    .messageId(1)
                    .from(user)
                    .chat(userChat)
                    .text("Forwarded")
                    .forwardFromChat(sourceChat)
                    .build();

            Update update = new Update();
            update.setUpdateId(1);
            update.setMessage(m);

            bot.consume(update);

            // enqueue не должен быть вызван (приватный чат)
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString(), any(), any());
            // Должно быть отправлено сообщение об ошибке
            verify(telegramClientMock, atLeast(1)).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("Пересланное сообщение без информации о чате — безопасная обработка")
        void testForwardedWithoutSourceChat() throws Exception {
            User user = User.builder().id(123L).isBot(false).firstName("Test").build();
            Chat userChat = Chat.builder().id(123L).type("private").build();

            Message m = Message.builder()
                    .messageId(1)
                    .from(user)
                    .chat(userChat)
                    .text("Forwarded")
                    .forwardFromChat(null)
                    .build();

            Update update = new Update();
            update.setUpdateId(1);
            update.setMessage(m);

            // Должен НЕ выбросить исключение
            Assertions.assertDoesNotThrow(() -> {
                bot.consume(update);
            });

            // enqueue не должен быть вызван
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString(), any(), any());
        }
    }
}
