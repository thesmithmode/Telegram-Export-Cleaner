package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import com.tcleaner.dashboard.service.subscription.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExportBotCallbackHandler")
class ExportBotCallbackHandlerTest {

    private ExportJobProducer jobProducerMock;
    private BotMessenger messengerMock;
    private BotUserUpserter userUpserterMock;
    private SubscriptionService subscriptionServiceMock;
    private ExportBotCallbackHandler handler;
    private BotSessionRegistry sessionRegistry;

    @BeforeEach
    void setUp() {
        jobProducerMock = mock(ExportJobProducer.class);
        messengerMock = mock(BotMessenger.class);
        userUpserterMock = mock(BotUserUpserter.class);
        subscriptionServiceMock = mock(SubscriptionService.class);
        when(userUpserterMock.resolveLanguage(anyLong())).thenReturn(BotLanguage.RU);

        BotI18n i18n = new BotI18n(newTestMessageSource());
        BotKeyboards keyboards = new BotKeyboards(i18n);
        sessionRegistry = new BotSessionRegistry();

        ExportBotCommandHandler cmdHandler = new ExportBotCommandHandler(
                jobProducerMock, messengerMock, i18n, keyboards,
                sessionRegistry, userUpserterMock, new QueueDisplayBuilder(i18n));

        handler = new ExportBotCallbackHandler(
                jobProducerMock, messengerMock, i18n, keyboards,
                sessionRegistry, userUpserterMock, subscriptionServiceMock, cmdHandler);
    }

    private static ReloadableResourceBundleMessageSource newTestMessageSource() {
        ReloadableResourceBundleMessageSource src = new ReloadableResourceBundleMessageSource();
        src.setBasename("classpath:bot_messages");
        src.setDefaultEncoding(StandardCharsets.UTF_8.name());
        src.setFallbackToSystemLocale(false);
        src.setDefaultLocale(Locale.ENGLISH);
        return src;
    }

    private CallbackQuery makeCallback(long userId, String data) {
        Message msg = new Message();
        msg.setMessageId(10);
        msg.setChat(Chat.builder().id(userId).type("private").build());

        User user = User.builder().id(userId).firstName("Test").isBot(false).build();
        CallbackQuery cb = new CallbackQuery();
        cb.setId("cb_id");
        cb.setFrom(user);
        cb.setData(data);
        cb.setMessage(msg);
        return cb;
    }

    private CallbackQuery makeCallbackNoMessage(long userId, String data) {
        User user = User.builder().id(userId).firstName("Test").isBot(false).build();
        CallbackQuery cb = new CallbackQuery();
        cb.setId("cb_no_msg");
        cb.setFrom(user);
        cb.setData(data);
        return cb;
    }

    @Nested
    @DisplayName("Обработка ошибок (handleCallbackSafe catch-block)")
    class ErrorHandling {

        @Test
        @DisplayName("Исключение в handleCallback: answerCallback + отправка ошибки пользователю")
        void exceptionInHandleCallbackNotifiesUser() {
            doThrow(new RuntimeException("boom"))
                    .when(messengerMock).answerCallback(anyString());

            CallbackQuery cb = makeCallback(42L, ExportBot.CB_EXPORT_ALL);
            handler.handleCallbackSafe(cb);

            // catch-block должен попытаться ещё раз ответить на callback
            verify(messengerMock).send(eq(42L), anyString());
        }

        @Test
        @DisplayName("Исключение без Message: send пользователю не вызывается")
        void exceptionWithoutMessageSkipsSend() {
            doThrow(new RuntimeException("no msg"))
                    .when(messengerMock).answerCallback(anyString());

            CallbackQuery cb = makeCallbackNoMessage(42L, ExportBot.CB_EXPORT_ALL);
            handler.handleCallbackSafe(cb);

            verify(messengerMock, never()).send(anyLong(), anyString());
        }

        @Test
        @DisplayName("Callback без Message (InaccessibleMessage): answerCallback + нет ошибки")
        void callbackWithoutMessageAnswersAndReturns() {
            CallbackQuery cb = makeCallbackNoMessage(99L, ExportBot.CB_EXPORT_ALL);
            handler.handleCallbackSafe(cb);

            verify(messengerMock).answerCallback("cb_no_msg");
            verify(messengerMock, never()).editMessage(anyLong(), anyInt(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("Switch-кейсы callback (непокрытые)")
    class UncoveredSwitchCases {

        @Test
        @DisplayName("CB_BACK_TO_MAIN сбрасывает сессию и показывает главное меню")
        void backToMain() {
            handler.handleCallbackSafe(makeCallback(1L, ExportBot.CB_BACK_TO_MAIN));

            verify(messengerMock).editMessage(eq(1L), anyInt(), anyString(), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("CB_BACK_TO_DATE_CHOICE сбрасывает даты и показывает меню выбора диапазона")
        void backToDateChoice() {
            handler.handleCallbackSafe(makeCallback(2L, ExportBot.CB_BACK_TO_DATE_CHOICE));

            verify(messengerMock).editMessage(eq(2L), anyInt(), anyString(), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("CB_BACK_TO_FROM_DATE сбрасывает toDate и показывает ввод начальной даты")
        void backToFromDate() {
            handler.handleCallbackSafe(makeCallback(3L, ExportBot.CB_BACK_TO_FROM_DATE));

            verify(messengerMock).editMessage(eq(3L), anyInt(), anyString(), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("CB_SETTINGS_OPEN показывает меню настроек")
        void settingsOpen() {
            handler.handleCallbackSafe(makeCallback(4L, ExportBot.CB_SETTINGS_OPEN));

            verify(messengerMock).editMessage(eq(4L), anyInt(), anyString(), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("CB_LAST_24H запускает quickRangeExport за 1 день")
        void last24h() {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), any(), any(), anyString(), isNull()))
                    .thenReturn("tid");
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);
            when(jobProducerMock.getQueueLength()).thenReturn(0L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(messengerMock.sendWithKeyboardGetId(anyLong(), anyString(), any())).thenReturn(5);

            UserSession s = sessionRegistry.get(5L);
            s.setChatId("ch");
            s.setChatDisplay("@ch");

            handler.handleCallbackSafe(makeCallback(5L, ExportBot.CB_LAST_24H));

            verify(jobProducerMock).enqueue(eq(5L), eq(5L), eq("ch"), isNull(), anyString(), isNull());
        }

        @Test
        @DisplayName("CB_LAST_7D запускает quickRangeExport за 7 дней")
        void last7d() {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), any(), any(), anyString(), isNull()))
                    .thenReturn("tid");
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);
            when(jobProducerMock.getQueueLength()).thenReturn(0L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(messengerMock.sendWithKeyboardGetId(anyLong(), anyString(), any())).thenReturn(5);

            UserSession s = sessionRegistry.get(6L);
            s.setChatId("ch2");
            s.setChatDisplay("@ch2");

            handler.handleCallbackSafe(makeCallback(6L, ExportBot.CB_LAST_7D));

            verify(jobProducerMock).enqueue(eq(6L), eq(6L), eq("ch2"), isNull(), anyString(), isNull());
        }

        @Test
        @DisplayName("Неизвестный callback: нет editMessage")
        void unknownCallback() {
            handler.handleCallbackSafe(makeCallback(7L, "unknown_cb_xyz"));

            verify(messengerMock, never()).editMessage(anyLong(), anyInt(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("handleLanguageCallback")
    class LanguageCallback {

        @Test
        @DisplayName("Неизвестный код языка: нет editMessage")
        void unknownLanguageCode() {
            handler.handleCallbackSafe(makeCallback(10L, ExportBot.CB_LANG_PREFIX + "xx"));

            verify(messengerMock, never()).editMessage(anyLong(), anyInt(), anyString(), any());
        }

        @Test
        @DisplayName("RuntimeException при сохранении: send с ошибкой, нет editMessage")
        void runtimeExceptionOnSave() {
            doThrow(new RuntimeException("DB down"))
                    .when(userUpserterMock).setLanguage(anyLong(), anyString());

            handler.handleCallbackSafe(makeCallback(11L, ExportBot.CB_LANG_PREFIX + "ru"));

            verify(messengerMock).send(eq(11L), anyString());
            verify(messengerMock, never()).editMessage(anyLong(), anyInt(), anyString(), any());
        }

        @Test
        @DisplayName("Валидный язык ru: editMessage с главным меню")
        void validLanguageRu() {
            handler.handleCallbackSafe(makeCallback(12L, ExportBot.CB_LANG_PREFIX + "ru"));

            verify(userUpserterMock).setLanguage(eq(12L), eq("ru"));
            verify(messengerMock).editMessage(eq(12L), anyInt(), anyString(), any(InlineKeyboardMarkup.class));
        }
    }

    @Nested
    @DisplayName("handleSubConfirmCallback")
    class SubConfirmCallback {

        @Test
        @DisplayName("Некорректный id (не число): сообщение об ошибке")
        void invalidId() {
            handler.handleCallbackSafe(makeCallback(20L, ExportBot.CB_SUB_CONFIRM_PREFIX + "abc"));

            verify(messengerMock).editMessage(eq(20L), anyInt(), anyString(), isNull());
            verify(subscriptionServiceMock, never()).findById(anyLong());
        }

        @Test
        @DisplayName("Подписка не найдена (empty): сообщение not_found")
        void subscriptionNotFound() {
            when(subscriptionServiceMock.findById(99L)).thenReturn(Optional.empty());

            handler.handleCallbackSafe(makeCallback(21L, ExportBot.CB_SUB_CONFIRM_PREFIX + "99"));

            verify(messengerMock).editMessage(eq(21L), anyInt(), anyString(), isNull());
            verify(subscriptionServiceMock, never()).confirmReceived(anyLong());
        }

        @Test
        @DisplayName("Подписка принадлежит другому user: сообщение not_found")
        void subscriptionWrongOwner() {
            ChatSubscription sub = new ChatSubscription();
            sub.setBotUserId(999L);
            when(subscriptionServiceMock.findById(1L)).thenReturn(Optional.of(sub));

            handler.handleCallbackSafe(makeCallback(22L, ExportBot.CB_SUB_CONFIRM_PREFIX + "1"));

            verify(messengerMock).editMessage(eq(22L), anyInt(), anyString(), isNull());
            verify(subscriptionServiceMock, never()).confirmReceived(anyLong());
        }

        @Test
        @DisplayName("Валидная подписка: confirmReceived + сообщение ok")
        void validSubscription() {
            ChatSubscription sub = new ChatSubscription();
            sub.setBotUserId(23L);
            when(subscriptionServiceMock.findById(5L)).thenReturn(Optional.of(sub));

            handler.handleCallbackSafe(makeCallback(23L, ExportBot.CB_SUB_CONFIRM_PREFIX + "5"));

            verify(subscriptionServiceMock).confirmReceived(5L);
            verify(messengerMock).editMessage(eq(23L), anyInt(), anyString(), isNull());
        }

        @Test
        @DisplayName("NoSuchElementException: сообщение not_found")
        void noSuchElement() {
            when(subscriptionServiceMock.findById(anyLong()))
                    .thenThrow(new NoSuchElementException());

            handler.handleCallbackSafe(makeCallback(24L, ExportBot.CB_SUB_CONFIRM_PREFIX + "7"));

            verify(messengerMock).editMessage(eq(24L), anyInt(), anyString(), isNull());
        }
    }
}
