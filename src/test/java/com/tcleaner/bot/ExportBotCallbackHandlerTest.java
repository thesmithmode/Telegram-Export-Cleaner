package com.tcleaner.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.events.StatsStreamPublisher;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import com.tcleaner.dashboard.service.subscription.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
            UserSession s = sessionRegistry.get(2L);
            s.setChatDisplay("@ch");
            s.setState(UserSession.State.AWAITING_FROM_DATE);

            handler.handleCallbackSafe(makeCallback(2L, ExportBot.CB_BACK_TO_DATE_CHOICE));

            verify(messengerMock).editMessage(eq(2L), anyInt(), anyString(), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("CB_BACK_TO_FROM_DATE сбрасывает toDate и показывает ввод начальной даты")
        void backToFromDate() {
            UserSession s = sessionRegistry.get(3L);
            s.setChatDisplay("@ch");
            s.setState(UserSession.State.AWAITING_TO_DATE);

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
            s.setState(UserSession.State.AWAITING_DATE_CHOICE);

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
            s.setState(UserSession.State.AWAITING_DATE_CHOICE);

            handler.handleCallbackSafe(makeCallback(6L, ExportBot.CB_LAST_7D));

            verify(jobProducerMock).enqueue(eq(6L), eq(6L), eq("ch2"), isNull(), anyString(), isNull());
        }

        @Test
        @DisplayName("Неизвестный callback: нет editMessage")
        void unknownCallback() {
            handler.handleCallbackSafe(makeCallback(7L, "unknown_cb_xyz"));

            verify(messengerMock, never()).editMessage(anyLong(), anyInt(), anyString(), any());
        }

        @Test
        @DisplayName("Устаревший LAST_24H при IDLE не запускает экспорт")
        void staleQuickRangeIgnoredWhenIdle() {
            UserSession s = sessionRegistry.get(8L);
            s.setChatId("ch");
            s.setChatDisplay("@ch");
            // state остаётся IDLE

            handler.handleCallbackSafe(makeCallback(8L, ExportBot.CB_LAST_24H));

            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), any(), any(), any(), any());
            verify(messengerMock).send(eq(8L), contains("истекла"));
        }

        @Test
        @DisplayName("cancel_export:taskId отменяет только если taskId = active")
        void cancelBoundToActiveTaskId() {
            when(jobProducerMock.cancelExportIfCurrent(9L, "export_abc")).thenReturn(true);

            handler.handleCallbackSafe(makeCallback(9L, ExportBot.CB_CANCEL_EXPORT + ":export_abc"));

            verify(jobProducerMock).cancelExportIfCurrent(9L, "export_abc");
            verify(jobProducerMock, never()).getActiveExport(anyLong());
            verify(jobProducerMock, never()).cancelExport(anyLong());
            verify(messengerMock).editMessage(eq(9L), anyInt(), contains("отменён"), isNull());
        }

        @Test
        @DisplayName("cancel_export:старый taskId не трогает новый активный экспорт")
        void cancelWrongTaskIdDoesNotCancel() {
            when(jobProducerMock.cancelExportIfCurrent(10L, "export_old")).thenReturn(false);

            handler.handleCallbackSafe(makeCallback(10L, ExportBot.CB_CANCEL_EXPORT + ":export_old"));

            verify(jobProducerMock).cancelExportIfCurrent(10L, "export_old");
            verify(jobProducerMock, never()).cancelExport(anyLong());
            verify(messengerMock).editMessage(eq(10L), anyInt(), contains("активн"), isNull());
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

    @Nested
    @DisplayName("State guard + cancel taskId branches")
    class StateAndCancelBranches {

        private void prepareEnqueueMocks() {
            when(jobProducerMock.enqueue(anyLong(), anyLong(), any(), any(), any(), any()))
                    .thenReturn("tid");
            when(jobProducerMock.isLikelyCached(any())).thenReturn(false);
            when(jobProducerMock.getQueueLength()).thenReturn(0L);
            when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
            when(messengerMock.sendWithKeyboardGetId(anyLong(), anyString(), any())).thenReturn(5);
        }

        @Test
        @DisplayName("CB_LAST_3D / CB_LAST_30D при AWAITING_DATE_CHOICE запускают экспорт")
        void quickRangesWithValidState() {
            prepareEnqueueMocks();
            UserSession s3 = sessionRegistry.get(31L);
            s3.setChatId("c3");
            s3.setChatDisplay("@c3");
            s3.setState(UserSession.State.AWAITING_DATE_CHOICE);
            handler.handleCallbackSafe(makeCallback(31L, ExportBot.CB_LAST_3D));
            verify(jobProducerMock).enqueue(eq(31L), eq(31L), eq("c3"), isNull(), anyString(), isNull());

            UserSession s30 = sessionRegistry.get(32L);
            s30.setChatId("c30");
            s30.setChatDisplay("@c30");
            s30.setState(UserSession.State.AWAITING_DATE_CHOICE);
            handler.handleCallbackSafe(makeCallback(32L, ExportBot.CB_LAST_30D));
            verify(jobProducerMock).enqueue(eq(32L), eq(32L), eq("c30"), isNull(), anyString(), isNull());
        }

        @Test
        @DisplayName("CB_EXPORT_ALL / CB_FROM_START / CB_TO_TODAY требуют нужный state")
        void wizardButtonsRequireState() {
            prepareEnqueueMocks();

            UserSession idle = sessionRegistry.get(40L);
            idle.setChatId("x");
            idle.setChatDisplay("@x");
            handler.handleCallbackSafe(makeCallback(40L, ExportBot.CB_EXPORT_ALL));
            verify(jobProducerMock, never()).enqueue(eq(40L), anyLong(), any(), any(), any(), any());

            UserSession choice = sessionRegistry.get(41L);
            choice.setChatId("y");
            choice.setChatDisplay("@y");
            choice.setState(UserSession.State.AWAITING_DATE_CHOICE);
            handler.handleCallbackSafe(makeCallback(41L, ExportBot.CB_EXPORT_ALL));
            verify(jobProducerMock).enqueue(eq(41L), eq(41L), eq("y"), isNull(), isNull(), isNull());

            UserSession wrongFrom = sessionRegistry.get(42L);
            wrongFrom.setChatDisplay("@z");
            wrongFrom.setState(UserSession.State.AWAITING_DATE_CHOICE);
            handler.handleCallbackSafe(makeCallback(42L, ExportBot.CB_FROM_START));
            verify(messengerMock).send(eq(42L), contains("истекла"));

            UserSession from = sessionRegistry.get(43L);
            from.setChatDisplay("@z2");
            from.setState(UserSession.State.AWAITING_FROM_DATE);
            handler.handleCallbackSafe(makeCallback(43L, ExportBot.CB_FROM_START));
            verify(messengerMock).editMessage(eq(43L), anyInt(), contains("конечн"), any(InlineKeyboardMarkup.class));

            UserSession wrongTo = sessionRegistry.get(44L);
            wrongTo.setChatId("t");
            wrongTo.setChatDisplay("@t");
            wrongTo.setState(UserSession.State.AWAITING_FROM_DATE);
            handler.handleCallbackSafe(makeCallback(44L, ExportBot.CB_TO_TODAY));
            verify(jobProducerMock, never()).enqueue(eq(44L), anyLong(), any(), any(), any(), any());

            UserSession to = sessionRegistry.get(45L);
            to.setChatId("t2");
            to.setChatDisplay("@t2");
            to.setState(UserSession.State.AWAITING_TO_DATE);
            handler.handleCallbackSafe(makeCallback(45L, ExportBot.CB_TO_TODAY));
            verify(jobProducerMock).enqueue(eq(45L), eq(45L), eq("t2"), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("BACK_TO_DATE_CHOICE принимает AWAITING_TO_DATE; BACK_TO_FROM_DATE отвергает IDLE")
        void backButtonsStateBranches() {
            UserSession to = sessionRegistry.get(50L);
            to.setChatDisplay("@b");
            to.setState(UserSession.State.AWAITING_TO_DATE);
            handler.handleCallbackSafe(makeCallback(50L, ExportBot.CB_BACK_TO_DATE_CHOICE));
            verify(messengerMock).editMessage(eq(50L), anyInt(), anyString(), any(InlineKeyboardMarkup.class));

            UserSession idle = sessionRegistry.get(51L);
            idle.setChatDisplay("@b2");
            handler.handleCallbackSafe(makeCallback(51L, ExportBot.CB_BACK_TO_FROM_DATE));
            verify(messengerMock).send(eq(51L), contains("истекла"));
            verify(messengerMock, never()).editMessage(eq(51L), anyInt(), anyString(), any());
        }

        @Test
        @DisplayName("cancel_export: пустой taskId или нет active → no_active, без cancelExport")
        void cancelBlankOrMissingActive() {
            handler.handleCallbackSafe(makeCallback(60L, ExportBot.CB_CANCEL_EXPORT + ":"));
            verify(jobProducerMock, never()).cancelExport(anyLong());
            verify(jobProducerMock, never()).cancelExportIfCurrent(anyLong(), anyString());
            verify(messengerMock).editMessage(eq(60L), anyInt(), contains("активн"), isNull());

            when(jobProducerMock.cancelExportIfCurrent(61L, "export_x")).thenReturn(false);
            handler.handleCallbackSafe(makeCallback(61L, ExportBot.CB_CANCEL_EXPORT + ":export_x"));
            verify(jobProducerMock, never()).cancelExport(eq(61L));
            verify(messengerMock).editMessage(eq(61L), anyInt(), contains("активн"), isNull());
        }

        @Test
        @DisplayName("CB_FROM_START при AWAITING_TO_DATE очищает fromDate и не шлёт session_expired")
        void fromStartAcceptedWhileAwaitingToDate() {
            UserSession session = sessionRegistry.get(80L);
            session.setChatDisplay("@chan");
            session.setFromDate("2024-01-01T00:00:00");
            session.setState(UserSession.State.AWAITING_TO_DATE);

            handler.handleCallbackSafe(makeCallback(80L, ExportBot.CB_FROM_START));

            assertEquals(UserSession.State.AWAITING_TO_DATE, session.getState());
            assertNull(session.getFromDate());
            verify(messengerMock, never()).send(eq(80L), contains("истекла"));
            verify(messengerMock).editMessage(
                    eq(80L), anyInt(), contains("конечн"), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("cancel в окне BLMOVE: processing=false и пустые очереди всё равно ставят флаг")
        void cancelDuringBlmoveWindowSetsFlagBeforeDeletingActiveKey() {
            long userId = 91L;
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("active_export:" + userId)).thenReturn("export_abc");
            when(valueOps.get("job_json:export_abc")).thenReturn(null);
            // getActiveExport в этом окне удалил бы ключ: processing нет, обе очереди пустые.
            when(redis.executePipelined(any(SessionCallback.class))).thenReturn(
                    Arrays.asList(false, false, false, 0L, 0L, false));

            List<String> ops = new ArrayList<>();
            doAnswer(inv -> {
                ops.add("set:" + inv.getArgument(0));
                return null;
            }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
            when(redis.delete(anyString())).thenAnswer(inv -> {
                ops.add("delete:" + inv.getArgument(0));
                return Boolean.TRUE;
            });

            @SuppressWarnings("unchecked")
            ObjectProvider<StatsStreamPublisher> noPublisher = mock(ObjectProvider.class);
            when(noPublisher.getIfAvailable()).thenReturn(null);
            ExportJobProducer realProducer = new ExportJobProducer(
                    redis, new ObjectMapper(), "telegram_export", noPublisher);

            BotI18n i18n = new BotI18n(newTestMessageSource());
            BotKeyboards keyboards = new BotKeyboards(i18n);
            BotSessionRegistry registry = new BotSessionRegistry();
            ExportBotCommandHandler cmdHandler = new ExportBotCommandHandler(
                    realProducer, messengerMock, i18n, keyboards,
                    registry, userUpserterMock, new QueueDisplayBuilder(i18n));
            ExportBotCallbackHandler realHandler = new ExportBotCallbackHandler(
                    realProducer, messengerMock, i18n, keyboards,
                    registry, userUpserterMock, subscriptionServiceMock, cmdHandler);

            realHandler.handleCallbackSafe(makeCallback(userId, ExportBot.CB_CANCEL_EXPORT + ":export_abc"));

            int setAt = ops.indexOf("set:cancel_export:export_abc");
            int deleteAt = ops.indexOf("delete:active_export:" + userId);
            assertTrue(setAt >= 0, "cancel flag was not set: " + ops);
            assertTrue(deleteAt > setAt, "active_export deleted before cancel flag: " + ops);
            verify(redis, never()).executePipelined(any(SessionCallback.class));
            InOrder order = inOrder(valueOps, redis);
            order.verify(valueOps).set(
                    eq("cancel_export:export_abc"), eq("1"), eq(60L), eq(TimeUnit.MINUTES));
            order.verify(redis).delete("active_export:" + userId);
            verify(messengerMock).editMessage(eq(userId), anyInt(), contains("отменён"), isNull());
        }

        @Test
        @DisplayName("CB_DATE_RANGE при валидном state переводит в AWAITING_FROM_DATE")
        void dateRangeValidState() {
            UserSession s = sessionRegistry.get(70L);
            s.setChatDisplay("@dr");
            s.setState(UserSession.State.AWAITING_DATE_CHOICE);
            handler.handleCallbackSafe(makeCallback(70L, ExportBot.CB_DATE_RANGE));
            verify(messengerMock).editMessage(eq(70L), anyInt(), contains("начальн"), any(InlineKeyboardMarkup.class));
            org.junit.jupiter.api.Assertions.assertEquals(UserSession.State.AWAITING_FROM_DATE, s.getState());
        }
    }

}
