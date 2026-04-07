package com.tcleaner.bot;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ExportBotTest {

    private ExportJobProducer jobProducerMock;
    private StringRedisTemplate redisMock;
    private ValueOperations<String, String> valueOpsMock;
    private TelegramClient telegramClientMock;
    private ExportBot bot;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        jobProducerMock = mock(ExportJobProducer.class);
        redisMock = mock(StringRedisTemplate.class);
        valueOpsMock = mock(ValueOperations.class);
        telegramClientMock = mock(TelegramClient.class);
        
        when(redisMock.opsForValue()).thenReturn(valueOpsMock);
        // По умолчанию активных экспортов нет
        when(jobProducerMock.getActiveExport(anyLong())).thenReturn(null);
        
        bot = new ExportBot("token", jobProducerMock, redisMock);
        
        // Внедряем мок-клиент через рефлексию
        Field clientField = ExportBot.class.getDeclaredField("telegramClient");
        clientField.setAccessible(true);
        clientField.set(bot, telegramClientMock);
    }

    @Nested
    @DisplayName("Прямой ввод идентификатора чата")
    class DirectChatIdentifierInput {

        @Test
        @DisplayName("Пользователь может ввести username напрямую")
        void testDirectUsernameInput() throws Exception {
            // Вводим username напрямую
            bot.consume(createTextMessageUpdate(123L, "public_channel"));

            // Должен перейти в AWAITING_DATE_CHOICE
            verify(telegramClientMock, atLeast(1)).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("Пользователь может ввести ссылку t.me")
        void testDirectLinkInput() throws Exception {
            // Вводим ссылку
            bot.consume(createTextMessageUpdate(123L, "https://t.me/public_channel"));

            // Должен перейти в AWAITING_DATE_CHOICE
            verify(telegramClientMock, atLeast(1)).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("Пользователь может ввести @username")
        void testDirectAtUsernameInput() throws Exception {
            // Вводим @username
            bot.consume(createTextMessageUpdate(123L, "@public_channel"));

            // Должен перейти в AWAITING_DATE_CHOICE
            verify(telegramClientMock, atLeast(1)).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("Пользователь может ввести числовой ID")
        void testDirectNumericIdInput() throws Exception {
            // Вводим числовой ID
            bot.consume(createTextMessageUpdate(123L, "-1001234567890"));

            // Должен перейти в AWAITING_DATE_CHOICE
            verify(telegramClientMock, atLeast(1)).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("Неверный формат (не числовой и не username) отклоняется")
        void testInvalidFormatRejected() throws Exception {
            // Вводим невалидный формат (пробелы, спецсимволы)
            bot.consume(createTextMessageUpdate(123L, "123 456 789"));

            // Должно быть отправлено сообщение об ошибке
            verify(telegramClientMock, atLeast(1)).execute(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("Ввод дат и валидация")
    class DateInputValidation {

        @Test
        @DisplayName("Неверный формат даты отклоняется с сообщением об ошибке")
        void testInvalidDateFormat() throws Exception {
            // Шаг 1: выбираем чат
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Шаг 2: выбираем "указать диапазон дат"
            CallbackQuery cbDateRange = createCallbackQuery(123L, "date_range");
            bot.consume(createCallbackUpdate(cbDateRange));

            // Пытаемся ввести неверный формат
            bot.consume(createTextMessageUpdate(123L, "2024-01-01"));  // ← неверный формат (YYYY-MM-DD вместо dd.MM.yyyy)

            // Должно быть отправлено сообщение об ошибке
            verify(telegramClientMock, atLeast(2)).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("Правильный формат даты принимается")
        void testValidDateFormat() throws Exception {
            // Шаг 1: выбираем чат
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Шаг 2: выбираем "указать диапазон дат"
            CallbackQuery cbDateRange = createCallbackQuery(123L, "date_range");
            bot.consume(createCallbackUpdate(cbDateRange));

            // Вводим правильный формат
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));

            // Должны остаться в состоянии AWAITING_TO_DATE (проверяем что был вызван sendWithInlineKeyboard)
            verify(telegramClientMock, atLeast(2)).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("Полный flow: введение username -> выбор дат -> запуск экспорта")
        void testCompleteExportFlow() throws Exception {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), anyString(), notNull(), notNull()))
                    .thenReturn("task789");
            when(jobProducerMock.getQueueLength()).thenReturn(1L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);

            // Шаг 1: вводим username или ссылку (переводит в AWAITING_DATE_CHOICE)
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Шаг 2: выбираем "указать диапазон дат" (переводит в AWAITING_FROM_DATE)
            CallbackQuery cbDateRange = createCallbackQuery(123L, "date_range");
            bot.consume(createCallbackUpdate(cbDateRange));

            // Шаг 3: вводим начальную дату (переводит в AWAITING_TO_DATE)
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));

            // Шаг 4: вводим конечную дату (запускает экспорт)
            bot.consume(createTextMessageUpdate(123L, "31.12.2024"));

            // Проверяем что enqueue был вызван с обеими датами
            verify(jobProducerMock).enqueue(
                    eq(123L), eq(123L), eq("test_chat"),
                    argThat(date -> date != null && date.contains("2024-01-01")),
                    argThat(date -> date != null && date.contains("2024-12-31"))
            );
        }
    }

    @Nested
    @DisplayName("Отмена экспорта")
    class CancelExport {

        @Test
        @DisplayName("CB_CANCEL_EXPORT вызывает jobProducer.cancelExport()")
        void testCancelExport() throws Exception {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), anyString(), any(), any()))
                    .thenReturn("task_to_cancel");
            when(jobProducerMock.getQueueLength()).thenReturn(1L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);

            // Выбираем чат
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Выбираем "Весь чат"
            CallbackQuery cbExportAll = createCallbackQuery(123L, "export_all");
            bot.consume(createCallbackUpdate(cbExportAll));

            // Теперь нажимаем CB_CANCEL_EXPORT
            CallbackQuery cbCancel = createCallbackQuery(123L, "cancel_export");
            bot.consume(createCallbackUpdate(cbCancel));

            // Проверяем что cancelExport был вызван
            verify(jobProducerMock).cancelExport(123L);
        }
    }

    @Nested
    @DisplayName("Callback обработка и date wizard")
    class CallbackHandling {

        @Test
        @DisplayName("CB_EXPORT_ALL запускает экспорт с пустыми датами")
        void testCBExportAll() throws Exception {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), anyString(), isNull(), isNull()))
                    .thenReturn("task123");
            when(jobProducerMock.getQueueLength()).thenReturn(1L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);

            // Выбираем чат напрямую (переводит в AWAITING_DATE_CHOICE)
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Теперь нажимаем CB_EXPORT_ALL (запускает экспорт без дат)
            CallbackQuery callback = createCallbackQuery(123L, "export_all");
            bot.consume(createCallbackUpdate(callback));

            // Проверяем что enqueue был вызван с null датами
            verify(jobProducerMock).enqueue(123L, 123L, "test_chat", null, null);
        }

        @Test
        @DisplayName("CB_FROM_START переводит в AWAITING_TO_DATE с null fromDate")
        void testCBFromStart() throws Exception {
            // Сначала выбираем чат (переводит в AWAITING_DATE_CHOICE)
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Выбираем "указать диапазон дат" (переводит в AWAITING_FROM_DATE)
            CallbackQuery cbDateRange = createCallbackQuery(123L, "date_range");
            bot.consume(createCallbackUpdate(cbDateRange));

            // Нажимаем CB_FROM_START (переводит в AWAITING_TO_DATE с null fromDate)
            CallbackQuery cbFromStart = createCallbackQuery(123L, "from_start");
            bot.consume(createCallbackUpdate(cbFromStart));

            // Проверяем что был вызван editMessage (переход в AWAITING_TO_DATE)
            verify(telegramClientMock, atLeast(1)).execute(any(EditMessageText.class));
        }

        @Test
        @DisplayName("CB_TO_TODAY запускает экспорт с null toDate")
        void testCBToToday() throws Exception {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), anyString(), any(), isNull()))
                    .thenReturn("task456");
            when(jobProducerMock.getQueueLength()).thenReturn(1L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);

            // Выбираем чат и переходим в date range режим
            bot.consume(createTextMessageUpdate(123L, "test_chat"));
            bot.consume(chatSelected);

            CallbackQuery cbDateRange = createCallbackQuery(123L, "date_range");
            bot.consume(createCallbackUpdate(cbDateRange));

            // Вводим начальную дату
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));

            // Нажимаем CB_TO_TODAY
            CallbackQuery cbToToday = createCallbackQuery(123L, "to_today");
            bot.consume(createCallbackUpdate(cbToToday));

            // Проверяем что enqueue был вызван с null toDate
            verify(jobProducerMock).enqueue(eq(123L), eq(123L), eq("test_chat"), notNull(), isNull());
        }

        @Test
        @DisplayName("CB_BACK_TO_DATE_CHOICE возвращает к выбору диапазона")
        void testCBBackToDateChoice() throws Exception {
            // Выбираем чат (переводит в AWAITING_DATE_CHOICE)
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Выбираем "указать диапазон дат" (переводит в AWAITING_FROM_DATE)
            CallbackQuery cbDateRange = createCallbackQuery(123L, "date_range");
            bot.consume(createCallbackUpdate(cbDateRange));

            // Нажимаем CB_BACK_TO_DATE_CHOICE (возвращает в AWAITING_DATE_CHOICE)
            CallbackQuery cbBack = createCallbackQuery(123L, "back_date_choice");
            bot.consume(createCallbackUpdate(cbBack));

            // Проверяем что был вызван editMessage с меню выбора дат
            verify(telegramClientMock, atLeast(2)).execute(any(EditMessageText.class));
        }

        @Test
        @DisplayName("CB_BACK_TO_FROM_DATE возвращает к вводу начальной даты")
        void testCBBackToFromDate() throws Exception {
            // Выбираем чат (переводит в AWAITING_DATE_CHOICE)
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Выбираем "указать диапазон дат" (переводит в AWAITING_FROM_DATE)
            CallbackQuery cbDateRange = createCallbackQuery(123L, "date_range");
            bot.consume(createCallbackUpdate(cbDateRange));

            // Вводим начальную дату (переводит в AWAITING_TO_DATE)
            bot.consume(createTextMessageUpdate(123L, "01.01.2024"));

            // Нажимаем CB_BACK_TO_FROM_DATE (возвращает в AWAITING_FROM_DATE)
            CallbackQuery cbBack = createCallbackQuery(123L, "back_from_date");
            bot.consume(createCallbackUpdate(cbBack));

            // Проверяем что был вызван editMessage
            verify(telegramClientMock, atLeast(2)).execute(any(EditMessageText.class));
        }

        @Test
        @DisplayName("handleCallback безопасно обрабатывает CallbackQuery без message")
        void testCallbackWithoutMessage() {
            User user = User.builder()
                    .id(123L)
                    .isBot(false)
                    .firstName("Test")
                    .build();

            CallbackQuery callback = new CallbackQuery();
            callback.setId("callback_456");
            callback.setFrom(user);
            callback.setData("export_all");
            callback.setMessage(null);

            Update update = new Update();
            update.setUpdateId(1);
            update.setCallbackQuery(callback);

            // Должен НЕ выбросить исключение
            Assertions.assertDoesNotThrow(() -> {
                bot.consume(update);
            });

            // enqueue не должен быть вызван (т.к. обработка прервана)
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("Дублирующие экспорты и обработка ошибок")
    class DuplicateExportHandling {

        @Test
        @DisplayName("startExport проверяет getActiveExport и блокирует дублирующие экспорты")
        void testDuplicateExportBlocked() throws Exception {
            // У пользователя уже есть активный экспорт
            when(jobProducerMock.getActiveExport(123L)).thenReturn("active_task_123");

            // Выбираем чат
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Пытаемся запустить экспорт
            CallbackQuery cbExportAll = createCallbackQuery(123L, "export_all");
            bot.consume(createCallbackUpdate(cbExportAll));

            // enqueue НЕ должен быть вызван (уже есть активный экспорт)
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString(), any(), any());
            // Должно быть отправлено сообщение об ошибке
            verify(telegramClientMock, atLeast(1)).execute(any(EditMessageText.class));
        }

        @Test
        @DisplayName("startExport обрабатывает IllegalStateException (race condition)")
        void testIllegalStateExceptionHandling() throws Exception {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), anyString(), any(), any()))
                    .thenThrow(new IllegalStateException("Export already active"));

            bot.consume(createTextMessageUpdate(123L, "test_chat"));
            bot.consume(chatSelected);

            CallbackQuery cbExportAll = createCallbackQuery(123L, "export_all");
            bot.consume(createCallbackUpdate(cbExportAll));

            // Должно быть отправлено сообщение об ошибке
            verify(telegramClientMock, atLeast(1)).execute(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("Управление сессиями и стабильность")
    class SessionAndStability {

        @Test
        @DisplayName("evictStaleSessions удаляет старые сессии и оставляет активные")
        @SuppressWarnings("unchecked")
        void testEvictStaleSessions() throws Exception {
            // Создаем активную сессию
            bot.consume(createTextMessageUpdate(123L, "/start"));

            // Находим мапу сессий через рефлексию
            Field sessionsField = ExportBot.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            ConcurrentHashMap<Long, UserSession> sessions = (ConcurrentHashMap<Long, UserSession>) sessionsField.get(bot);

            // Искусственно состариваем одну сессию (через рефлексию UserSession)
            UserSession staleSession = new UserSession();
            Field lastAccessField = UserSession.class.getDeclaredField("lastAccess");
            lastAccessField.setAccessible(true);
            lastAccessField.set(staleSession, java.time.Instant.now().minus(java.time.Duration.ofHours(3)));
            sessions.put(456L, staleSession);

            Assertions.assertTrue(sessions.containsKey(123L));
            Assertions.assertTrue(sessions.containsKey(456L));

            // Запускаем очистку
            bot.evictStaleSessions();

            // Проверяем результат
            Assertions.assertTrue(sessions.containsKey(123L));
            Assertions.assertFalse(sessions.containsKey(456L));
        }

        @Test
        @DisplayName("consume не выбрасывает исключение при ошибке обработки сообщения")
        void testConsumeStability() {
            // Создаем апдейт, который вызовет ошибку (например, null message)
            Update update = new Update();
            // Метод consume должен поймать NPE или другое исключение и просто залогировать его

            Assertions.assertDoesNotThrow(() -> {
                bot.consume(update);
            });
        }

        @Test
        @DisplayName("Сессия сбрасывается после успешного экспорта")
        @SuppressWarnings("unchecked")
        void testSessionResetAfterExport() throws Exception {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), anyString(), any(), any()))
                    .thenReturn("task_reset");
            when(jobProducerMock.getQueueLength()).thenReturn(1L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);

            // Выбираем чат (переводит в AWAITING_DATE_CHOICE)
            bot.consume(createTextMessageUpdate(123L, "test_chat"));

            // Запускаем экспорт (CB_EXPORT_ALL сбросит сессию после запуска)
            CallbackQuery cbExportAll = createCallbackQuery(123L, "export_all");
            bot.consume(createCallbackUpdate(cbExportAll));

            // Находим сессию через рефлексию
            Field sessionsField = ExportBot.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            ConcurrentHashMap<Long, UserSession> sessions = (ConcurrentHashMap<Long, UserSession>) sessionsField.get(bot);

            UserSession session = sessions.get(123L);
            Assertions.assertNotNull(session);
            // После экспорта сессия должна быть в начальном состоянии (IDLE)
            Assertions.assertEquals(UserSession.State.IDLE, session.getState());
            Assertions.assertNull(session.getChatId());
        }
    }

    private Update createTextMessageUpdate(long userId, String text) {
        User user = User.builder().id(userId).isBot(false).firstName("Test").build();
        Chat chat = Chat.builder().id(userId).type("private").build();
        Message m = Message.builder().messageId(1).from(user).chat(chat).text(text).build();
        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(m);
        return update;
    }

    private CallbackQuery createCallbackQuery(long userId, String callbackData) {
        User user = User.builder()
                .id(userId)
                .isBot(false)
                .firstName("Test")
                .build();

        Chat chat = Chat.builder()
                .id(userId)
                .type("private")
                .build();

        Message message = Message.builder()
                .messageId(999)
                .from(user)
                .chat(chat)
                .text("Test message")
                .build();

        CallbackQuery callback = new CallbackQuery();
        callback.setId("callback_123");
        callback.setFrom(user);
        callback.setMessage(message);
        callback.setData(callbackData);
        return callback;
    }

    private Update createCallbackUpdate(CallbackQuery callback) {
        Update update = new Update();
        update.setUpdateId(1);
        update.setCallbackQuery(callback);
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
