package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.events.StatsStreamPublisher;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private BotUserUpserter userUpserterMock;
    private com.tcleaner.dashboard.service.subscription.SubscriptionService subscriptionServiceMock;
    private BotI18n i18n;
    private ExportBot bot;

    @BeforeEach
    void setUp() {
        jobProducerMock = mock(ExportJobProducer.class);
        messengerMock = mock(BotMessenger.class);
        userUpserterMock = mock(BotUserUpserter.class);
        subscriptionServiceMock = mock(com.tcleaner.dashboard.service.subscription.SubscriptionService.class);

        // По умолчанию — юзер уже выбрал русский (существующая проверка текстов в assertions
        // построена под русский; тесты, специфичные для выбора языка, явно перекрывают).
        when(userUpserterMock.getLanguage(anyLong())).thenReturn(Optional.of("ru"));
        when(userUpserterMock.resolveLanguage(anyLong())).thenReturn(com.tcleaner.core.BotLanguage.RU);

        when(jobProducerMock.getActiveExport(anyLong())).thenReturn(null);
        when(jobProducerMock.enqueue(anyLong(), anyLong(), any(String.class), isNull(), isNull()))
                .thenReturn("export_test_id");
        when(jobProducerMock.enqueue(anyLong(), anyLong(), any(String.class), any(String.class), any(String.class)))
                .thenReturn("export_test_id");
        when(jobProducerMock.enqueue(anyLong(), anyLong(), any(String.class), any(), isNull(), isNull()))
                .thenReturn("export_test_id");
        when(jobProducerMock.enqueue(anyLong(), anyLong(), any(String.class), any(), any(String.class), any(String.class)))
                .thenReturn("export_test_id");
        when(jobProducerMock.isLikelyCached(any())).thenReturn(false);
        when(jobProducerMock.getQueueLength()).thenReturn(0L);
        when(jobProducerMock.hasActiveProcessingJob()).thenReturn(false);
        when(messengerMock.sendWithKeyboardGetId(anyLong(), anyString(), any())).thenReturn(42);

        i18n = new BotI18n(newTestMessageSource());

        @SuppressWarnings("unchecked")
        ObjectProvider<StatsStreamPublisher> noPublisher = mock(ObjectProvider.class);
        when(noPublisher.getIfAvailable()).thenReturn(null);
        bot = new ExportBot("token", "https://test.example.com/dashboard/mini-app",
                jobProducerMock, messengerMock, i18n, new BotKeyboards(i18n),
                new BotSessionRegistry(), userUpserterMock, noPublisher, subscriptionServiceMock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                new QueueDisplayBuilder(i18n));
    }

    private static ReloadableResourceBundleMessageSource newTestMessageSource() {
        ReloadableResourceBundleMessageSource src = new ReloadableResourceBundleMessageSource();
        src.setBasename("classpath:bot_messages");
        src.setDefaultEncoding(StandardCharsets.UTF_8.name());
        src.setFallbackToSystemLocale(false);
        src.setDefaultLocale(Locale.ENGLISH);
        return src;
    }

    @Test
    @DisplayName("При старте регистрируются slash-команды для default + всех локалей")
    void testRegistersSlashCommandsOnStartup() {
        bot.registerBotCommands();
        // 1 default + 10 per-locale
        verify(messengerMock, atLeast(11)).setMyCommands(any(), any());
    }

    @Test
    @DisplayName("setMyCommands для PT_BR использует 2-буквенный ISO 639-1 (\"pt\"), а не \"pt-BR\"")
    void testPtBrUsesTwoLetterLanguageCodeForTelegramApi() {
        bot.registerBotCommands();
        // Telegram Bot API requires ISO 639-1 (2 chars). "pt-BR" бы отклонился.
        verify(messengerMock).setMyCommands(any(), eq("pt"));
        verify(messengerMock, never()).setMyCommands(any(), eq("pt-BR"));
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

            verify(jobProducerMock).enqueue(123L, 123L, "my_channel", null, null, null);
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
                    eq(123L), eq(123L), eq("my_channel"), isNull(), contains("2024-01-01"), isNull());
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
                    isNull(), contains("2024-01-01"), contains("2024-12-31"));
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
        @DisplayName("/start у юзера с выбранным ru отправляет русский HELP")
        void testStartSendsHelpText() {
            bot.consume(createTextMessageUpdate(123L, "/start"));

            verify(messengerMock).sendWithKeyboard(
                    eq(123L),
                    contains("Этот бот экспортирует"),
                    any(InlineKeyboardMarkup.class));
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
            verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString(), any(), any(), any());
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

    @Nested
    @DisplayName("Выбор языка (/start, /settings, lang:*)")
    class LanguageSelection {

        @Test
        @DisplayName("/start без выбранного языка → клавиатура выбора, не HELP")
        void startWithoutLanguageShowsChooser() {
            when(userUpserterMock.getLanguage(anyLong())).thenReturn(Optional.empty());
            when(userUpserterMock.resolveLanguage(anyLong())).thenReturn(com.tcleaner.core.BotLanguage.EN);

            bot.consume(createTextMessageUpdate(123L, "/start"));

            verify(messengerMock).sendWithKeyboard(
                    eq(123L),
                    contains("Please choose your language"),
                    any(InlineKeyboardMarkup.class));
            verify(messengerMock, never()).send(eq(123L), contains("Этот бот экспортирует"));
        }

        @Test
        @DisplayName("callback lang:fa сохраняет fa и показывает фарси HELP")
        void callbackPersistsLanguageAndShowsHelp() {
            when(userUpserterMock.getLanguage(anyLong())).thenReturn(Optional.empty());
            when(userUpserterMock.resolveLanguage(anyLong())).thenReturn(com.tcleaner.core.BotLanguage.EN);
            bot.consume(createTextMessageUpdate(123L, "/start"));

            bot.consume(createCallbackUpdate(123L, ExportBot.CB_LANG_PREFIX + "fa"));

            verify(userUpserterMock).setLanguage(eq(123L), eq("fa"));
            verify(messengerMock).editMessage(
                    eq(123L), anyInt(), anyString(), any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("callback lang:<невалидный> игнорируется, язык не сохраняется")
        void invalidLanguageCodeIgnored() {
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_LANG_PREFIX + "xx"));

            verify(userUpserterMock, never()).setLanguage(anyLong(), anyString());
        }

        @Test
        @DisplayName("/settings отправляет меню настроек с кнопкой смены языка")
        void settingsShowsMenu() {
            bot.consume(createTextMessageUpdate(123L, "/settings"));

            verify(messengerMock).sendWithKeyboard(
                    eq(123L),
                    contains("Настройки"),
                    any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("callback settings:language → клавиатура выбора языка (editMessage)")
        void settingsLanguageCallbackShowsChooser() {
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_SETTINGS_LANGUAGE));

            verify(messengerMock).editMessage(
                    eq(123L), anyInt(),
                    contains("Please choose your language"),
                    any(InlineKeyboardMarkup.class));
        }

        @Test
        @DisplayName("/start у юзера с en отправляет английский HELP")
        void startWithEnSendsEnglishHelp() {
            when(userUpserterMock.getLanguage(anyLong())).thenReturn(Optional.of("en"));
            when(userUpserterMock.resolveLanguage(anyLong())).thenReturn(com.tcleaner.core.BotLanguage.EN);

            bot.consume(createTextMessageUpdate(123L, "/start"));

            verify(messengerMock).sendWithKeyboard(
                    eq(123L),
                    contains("This bot exports"),
                    any(InlineKeyboardMarkup.class));
        }
    }

    @Nested
    @DisplayName("Парсинг topic ID из ссылок")
    class TopicIdParsing {

        @Test
        @DisplayName("t.me/channel/12345 — extractUsername возвращает channel")
        void testTmeLinkWithTopicUsername() {
            assertThat(BotInputParser.extractUsername("https://t.me/public_channel/12345"))
                    .isEqualTo("public_channel");
        }

        @Test
        @DisplayName("t.me/channel/12345 — extractTopicId возвращает 12345")
        void testTmeLinkWithTopicId() {
            assertThat(BotInputParser.extractTopicId("https://t.me/public_channel/12345"))
                    .isEqualTo(12345);
        }

        @Test
        @DisplayName("t.me/channel (без топика) — extractTopicId возвращает null")
        void testTmeLinkWithoutTopicId() {
            assertThat(BotInputParser.extractTopicId("https://t.me/public_channel"))
                    .isNull();
        }

        @Test
        @DisplayName("@username — extractTopicId возвращает null")
        void testAtUsernameTopicIdNull() {
            assertThat(BotInputParser.extractTopicId("@test_chat")).isNull();
        }

        @Test
        @DisplayName("t.me/channel/0 — невалидный topic_id, extractTopicId возвращает null")
        void testZeroTopicId() {
            assertThat(BotInputParser.extractTopicId("https://t.me/public_channel/0"))
                    .isNull();
        }

        @Test
        @DisplayName("t.me/channel/abc — нечисловой topic, extractUsername извлекает channel, topicId null")
        void testNonNumericTopicIgnored() {
            assertThat(BotInputParser.extractUsername("https://t.me/public_channel/abc"))
                    .isEqualTo("public_channel");
            assertThat(BotInputParser.extractTopicId("https://t.me/public_channel/abc"))
                    .isNull();
        }

        @Test
        @DisplayName("t.me/channel/1 — topic_id=1 (General topic) валиден")
        void testTopicIdOne() {
            assertThat(BotInputParser.extractTopicId("https://t.me/public_channel/1"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("t.me/channel/148220 — реальный topic_id парсится")
        void testRealTopicId() {
            assertThat(BotInputParser.extractTopicId("https://t.me/strbypass/148220"))
                    .isEqualTo(148220);
            assertThat(BotInputParser.extractUsername("https://t.me/strbypass/148220"))
                    .isEqualTo("strbypass");
        }

        @Test
        @DisplayName("t.me/channel/99999999999 — overflow Integer, extractTopicId возвращает null")
        void testOverflowTopicId() {
            assertThat(BotInputParser.extractTopicId("https://t.me/public_channel/99999999999"))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Topic ID в потоке экспорта")
    class TopicIdExportFlow {

        @Test
        @DisplayName("Ссылка с топиком — topicId=148220 передаётся в enqueue")
        void testTopicLinkStoresTopicId() {
            bot.consume(createTextMessageUpdate(123L, "https://t.me/public_channel/148220"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(eq(123L), eq(123L), eq("public_channel"),
                    eq(148220), isNull(), isNull());
        }

        @Test
        @DisplayName("Ссылка без топика — topicId=null передаётся в enqueue")
        void testNoTopicLinkNullTopicId() {
            bot.consume(createTextMessageUpdate(123L, "https://t.me/public_channel"));
            bot.consume(createCallbackUpdate(123L, ExportBot.CB_EXPORT_ALL));

            verify(jobProducerMock).enqueue(eq(123L), eq(123L), eq("public_channel"),
                    isNull(), isNull(), isNull());
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

    @Nested
    @DisplayName("Валидация dashboard.mini-app.url в конструкторе")
    class MiniAppUrlValidation {

        @SuppressWarnings("unchecked")
        private ObjectProvider<StatsStreamPublisher> emptyPublisher() {
            ObjectProvider<StatsStreamPublisher> p = mock(ObjectProvider.class);
            when(p.getIfAvailable()).thenReturn(null);
            return p;
        }

        private void newBot(String url) {
            new ExportBot("token", url, jobProducerMock, messengerMock,
                    i18n, new BotKeyboards(i18n), new BotSessionRegistry(),
                    userUpserterMock, emptyPublisher(),
                    mock(com.tcleaner.dashboard.service.subscription.SubscriptionService.class),
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                    new QueueDisplayBuilder(i18n));
        }

        @Test
        @DisplayName("http://localhost/... падает — Telegram Mini App требует публичный HTTPS")
        void rejectsLocalhostUrl() {
            assertThatThrownBy(() -> newBot("http://localhost/dashboard/mini-app"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dashboard.mini-app.url");
        }

        @Test
        @DisplayName("https://localhost:8080/... тоже падает — даже HTTPS с localhost не принимается")
        void rejectsHttpsLocalhostUrl() {
            assertThatThrownBy(() -> newBot("https://localhost:8080/dashboard/mini-app"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("https://127.0.0.1/... падает")
        void rejectsLoopbackIpUrl() {
            assertThatThrownBy(() -> newBot("https://127.0.0.1/dashboard/mini-app"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("http:// (без TLS) падает — Telegram требует HTTPS")
        void rejectsPlainHttpUrl() {
            assertThatThrownBy(() -> newBot("http://example.com/dashboard/mini-app"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Публичный HTTPS URL принимается")
        void acceptsPublicHttpsUrl() {
            assertThatCode(() -> newBot("https://tec.searchingforgamesforever.online/dashboard/mini-app"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("BotLanguage enum")
    class BotLanguageTests {

        @Test
        @DisplayName("fromCode резолвит ru/en/fa case-insensitive")
        void fromCodeCaseInsensitive() {
            assertThat(BotLanguage.fromCode("ru")).contains(BotLanguage.RU);
            assertThat(BotLanguage.fromCode("RU")).contains(BotLanguage.RU);
            assertThat(BotLanguage.fromCode("fa")).contains(BotLanguage.FA);
        }

        @Test
        @DisplayName("fromCode принимает pt-BR и pt_BR")
        void fromCodePtBrBothForms() {
            assertThat(BotLanguage.fromCode("pt-BR")).contains(BotLanguage.PT_BR);
            assertThat(BotLanguage.fromCode("pt_BR")).contains(BotLanguage.PT_BR);
        }

        @Test
        @DisplayName("fromCode невалидный → пусто")
        void fromCodeInvalid() {
            assertThat(BotLanguage.fromCode("xx")).isEmpty();
            assertThat(BotLanguage.fromCode("")).isEmpty();
            assertThat(BotLanguage.fromCode(null)).isEmpty();
        }

        @Test
        @DisplayName("RTL флаг установлен для fa и ar")
        void rtlFlag() {
            assertThat(BotLanguage.FA.isRtl()).isTrue();
            assertThat(BotLanguage.AR.isRtl()).isTrue();
            assertThat(BotLanguage.RU.isRtl()).isFalse();
            assertThat(BotLanguage.EN.isRtl()).isFalse();
        }

        @Test
        @DisplayName("allActive содержит ровно 10 языков")
        void allActiveCount() {
            assertThat(BotLanguage.allActive()).hasSize(10);
        }
    }

    @Nested
    @DisplayName("BotI18n")
    class BotI18nTests {

        @Test
        @DisplayName("msg резолвит ключ в ru локали")
        void msgResolvesRu() {
            String result = i18n.msg(BotLanguage.RU, "bot.cancel.no_active");
            assertThat(result).contains("Нет активного экспорта");
        }

        @Test
        @DisplayName("msg резолвит ключ в en локали")
        void msgResolvesEn() {
            String result = i18n.msg(BotLanguage.EN, "bot.cancel.no_active");
            assertThat(result).contains("No active export");
        }

        @Test
        @DisplayName("msg с отсутствующим ключом возвращает сам ключ (graceful)")
        void msgMissingKeyReturnsKey() {
            String result = i18n.msg(BotLanguage.RU, "bot.nonexistent.key");
            assertThat(result).isEqualTo("bot.nonexistent.key");
        }

        @Test
        @DisplayName("msg с аргументами подставляет через MessageFormat")
        void msgWithArgs() {
            String result = i18n.msg(BotLanguage.RU, "bot.cancel.ok", "task_123");
            assertThat(result).contains("task_123").contains("отменён");
        }
    }
}
