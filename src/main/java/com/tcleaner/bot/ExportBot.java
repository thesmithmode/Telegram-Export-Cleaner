package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.events.StatsStreamPublisher;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ExportBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportBot.class);

    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    static final String CB_EXPORT_ALL = "export_all";
    static final String CB_DATE_RANGE = "date_range";
    static final String CB_FROM_START = "from_start";
    static final String CB_TO_TODAY = "to_today";
    static final String CB_LAST_24H = "last_24h";
    static final String CB_LAST_3D = "last_3d";
    static final String CB_LAST_7D = "last_7d";
    static final String CB_LAST_30D = "last_30d";
    static final String CB_BACK_TO_MAIN = "back_main";
    static final String CB_BACK_TO_DATE_CHOICE = "back_date_choice";
    static final String CB_BACK_TO_FROM_DATE = "back_from_date";
    static final String CB_CANCEL_EXPORT = "cancel_export";
    static final String CB_LANG_PREFIX = "lang:";
    static final String CB_SETTINGS_LANGUAGE = "settings:language";
    static final String CB_SETTINGS_OPEN = "settings:open";

    private final String botToken;
    private final ExportJobProducer jobProducer;
    private final BotMessenger messenger;
    private final BotI18n i18n;
    private final BotKeyboards keyboards;
    private final BotSessionRegistry sessionRegistry;
    private final BotUserUpserter userUpserter;
    private final ObjectProvider<StatsStreamPublisher> statsPublisherProvider;
    private final String miniAppUrl;

    public ExportBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${dashboard.mini-app.url}") String miniAppUrl,
            ExportJobProducer jobProducer,
            BotMessenger messenger,
            BotI18n i18n,
            BotKeyboards keyboards,
            BotSessionRegistry sessionRegistry,
            BotUserUpserter userUpserter,
            ObjectProvider<StatsStreamPublisher> statsPublisherProvider
    ) {
        String normalized = miniAppUrl.toLowerCase();
        if (!normalized.startsWith("https://")
                || normalized.contains("localhost")
                || normalized.contains("127.0.0.1")) {
            throw new IllegalStateException(
                    "dashboard.mini-app.url некорректен: " + miniAppUrl
                    + ". Требуется публичный HTTPS-URL (Telegram Mini App не принимает http/localhost)."
                    + " Установите TRAEFIK_DASHBOARD_DOMAIN в окружении контейнера.");
        }
        this.botToken = botToken;
        this.miniAppUrl = miniAppUrl;
        this.jobProducer = jobProducer;
        this.messenger = messenger;
        this.i18n = i18n;
        this.keyboards = keyboards;
        this.sessionRegistry = sessionRegistry;
        this.userUpserter = userUpserter;
        this.statsPublisherProvider = statsPublisherProvider;
        log.info("Telegram-бот инициализирован");
    }

    @PostConstruct
    void registerBotCommands() {
        // Default-scope — используется клиентом, если для его locale нет специального
        // набора. Далее регистрируем локализованный набор на каждый поддерживаемый язык.
        List<BotCommand> defaultCommands = buildCommands(BotLanguage.EN);
        messenger.setMyCommands(defaultCommands, null);
        for (BotLanguage lang : BotLanguage.allActive()) {
            messenger.setMyCommands(buildCommands(lang), lang.getCode());
        }
        messenger.setChatMenuButton(miniAppUrl, "Dashboard");
    }

    private List<BotCommand> buildCommands(BotLanguage lang) {
        return List.of(
                new BotCommand("/start", i18n.msg(lang, "bot.command.start")),
                new BotCommand("/cancel", i18n.msg(lang, "bot.command.cancel")),
                new BotCommand("/settings", i18n.msg(lang, "bot.command.settings"))
        );
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        try {
            processUpdate(update);
        } catch (Exception e) {
            log.error("Update processing fail: {}", e.getMessage(), e);
        }
    }

    private void processUpdate(Update update) {
        if (update.hasCallbackQuery()) {
            publishBotUserSeen(update.getCallbackQuery().getFrom());
            handleCallbackSafe(update.getCallbackQuery());
            return;
        }

        if (!update.hasMessage()) {
            return;
        }

        Message message = update.getMessage();
        if (!"private".equals(message.getChat().getType())) {
            return;
        }

        long chatId = message.getChatId();
        long userId = message.getFrom().getId();

        publishBotUserSeen(message.getFrom());

        if (message.hasText()) {
            handleMessageText(chatId, userId, message.getText().trim());
        }
    }

    private void handleMessageText(long chatId, long userId, String text) {
        if (text.startsWith("/start")) {
            getSession(userId).reset();
            handleStart(chatId, userId);
        } else if (text.startsWith("/cancel")) {
            handleCancel(chatId, userId);
        } else if (text.startsWith("/settings")) {
            handleSettings(chatId, userId);
        } else {
            handleTextInput(chatId, userId, text);
        }
    }

    private void handleStart(long chatId, long userId) {
        BotLanguage stored = resolveStoredLanguage(userId);
        if (stored == null) {
            messenger.sendWithKeyboard(chatId,
                    i18n.msg(BotLanguage.EN, "bot.start.choose_language"),
                    keyboards.languageChoiceKeyboard());
            return;
        }
        messenger.sendWithKeyboard(chatId,
                i18n.msg(stored, "bot.start.help"),
                keyboards.mainMenuKeyboard(stored));
    }

    private void handleCancel(long chatId, long userId) {
        BotLanguage lang = resolveLanguage(userId);
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId == null) {
            messenger.send(chatId, i18n.msg(lang, "bot.cancel.no_active"));
            return;
        }
        jobProducer.cancelExport(userId);
        messenger.send(chatId, i18n.msg(lang, "bot.cancel.ok", activeTaskId));
        getSession(userId).reset();
    }

    private void handleSettings(long chatId, long userId) {
        BotLanguage lang = resolveLanguage(userId);
        messenger.sendWithKeyboard(chatId,
                i18n.msg(lang, "bot.settings.title"),
                keyboards.settingsKeyboard(lang));
    }

    private void handleTextInput(long chatId, long userId, String text) {
        UserSession session = getSession(userId);
        switch (session.getState()) {
            case AWAITING_FROM_DATE -> handleFromDateInput(chatId, userId, text);
            case AWAITING_TO_DATE -> handleToDateInput(chatId, userId, text);
            default -> handleChatIdentifier(chatId, userId, text);
        }
    }

    private void handleChatIdentifier(long chatId, long userId, String input) {
        BotLanguage lang = resolveLanguage(userId);
        if (checkActiveExportAndNotify(chatId, userId, lang)) {
            return;
        }

        UserSession session = getSession(userId);
        String identifier = BotInputParser.extractUsername(input);
        if (identifier == null) {
            messenger.send(chatId, i18n.msg(lang, "bot.error.invalid_format"));
            return;
        }

        session.setChatId(identifier);
        session.setTopicId(BotInputParser.extractTopicId(input));
        session.setChatDisplay("@" + identifier);
        session.setState(UserSession.State.AWAITING_DATE_CHOICE);

        sendDateChoiceMenu(chatId, lang, session.getChatDisplay());
    }

    private void handleCallbackSafe(CallbackQuery callback) {
        try {
            handleCallback(callback);
        } catch (Exception e) {
            log.error("Callback error for user {}: {}", callback.getFrom().getId(), e.getMessage(), e);
            messenger.answerCallback(callback.getId());
        }
    }

    private void handleCallback(CallbackQuery callback) {
        Object maybe = callback.getMessage();
        if (!(maybe instanceof Message cbMessage)) {
            log.warn("Callback без доступного message от пользователя {}", callback.getFrom().getId());
            messenger.answerCallback(callback.getId());
            return;
        }

        long userId = callback.getFrom().getId();
        long chatId = cbMessage.getChatId();
        String data = callback.getData();
        int messageId = cbMessage.getMessageId();
        messenger.answerCallback(callback.getId());

        if (data.startsWith(CB_LANG_PREFIX)) {
            handleLanguageCallback(chatId, userId, messageId, data.substring(CB_LANG_PREFIX.length()));
            return;
        }
        if (CB_SETTINGS_LANGUAGE.equals(data)) {
            messenger.editMessage(chatId, messageId,
                    i18n.msg(BotLanguage.EN, "bot.start.choose_language"),
                    keyboards.languageChoiceKeyboard());
            return;
        }
        if (CB_SETTINGS_OPEN.equals(data)) {
            BotLanguage lang = resolveLanguage(userId);
            messenger.editMessage(chatId, messageId,
                    i18n.msg(lang, "bot.settings.title"),
                    keyboards.settingsKeyboard(lang));
            return;
        }

        BotLanguage lang = resolveLanguage(userId);
        UserSession session = getSession(userId);

        switch (data) {
            case CB_EXPORT_ALL -> {
                session.setFromDate(null);
                session.setToDate(null);
                startExport(chatId, userId, messageId);
            }
            case CB_LAST_24H -> startQuickRangeExport(chatId, userId, messageId, 1);
            case CB_LAST_3D -> startQuickRangeExport(chatId, userId, messageId, 3);
            case CB_LAST_7D -> startQuickRangeExport(chatId, userId, messageId, 7);
            case CB_LAST_30D -> startQuickRangeExport(chatId, userId, messageId, 30);
            case CB_DATE_RANGE -> {
                session.setState(UserSession.State.AWAITING_FROM_DATE);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.prompt.from_range_header", session.getChatDisplay())
                                + i18n.msg(lang, "bot.prompt.from_date"),
                        keyboards.fromDateKeyboard(lang));
            }
            case CB_FROM_START -> {
                session.setFromDate(null);
                session.setState(UserSession.State.AWAITING_TO_DATE);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.prompt.from_range_header", session.getChatDisplay())
                                + i18n.msg(lang, "bot.prompt.from_start_line")
                                + i18n.msg(lang, "bot.prompt.to_date"),
                        keyboards.toDateKeyboard(lang));
            }
            case CB_TO_TODAY -> {
                session.setToDate(null);
                startExport(chatId, userId, messageId);
            }
            case CB_BACK_TO_MAIN -> {
                session.reset();
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.start.help"),
                        keyboards.mainMenuKeyboard(lang));
            }
            case CB_BACK_TO_DATE_CHOICE -> {
                session.setFromDate(null);
                session.setToDate(null);
                session.setState(UserSession.State.AWAITING_DATE_CHOICE);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.prompt.choose_range_short", session.getChatDisplay()),
                        keyboards.dateChoiceKeyboard(lang));
            }
            case CB_BACK_TO_FROM_DATE -> {
                session.setToDate(null);
                session.setState(UserSession.State.AWAITING_FROM_DATE);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.prompt.from_range_header", session.getChatDisplay())
                                + i18n.msg(lang, "bot.prompt.from_date"),
                        keyboards.fromDateKeyboard(lang));
            }
            case CB_CANCEL_EXPORT -> {
                jobProducer.cancelExport(userId);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.cancel.ok_simple"), null);
                session.reset();
            }
            default -> log.warn("Неизвестный callback: {}", data);
        }
    }

    private void handleLanguageCallback(long chatId, long userId, int messageId, String code) {
        BotLanguage picked = BotLanguage.fromCode(code).orElse(null);
        if (picked == null) {
            log.warn("Неизвестный код языка в callback от userId={}: {}", userId, code);
            return;
        }
        try {
            userUpserter.setLanguage(userId, picked.getCode());
        } catch (RuntimeException ex) {
            log.error("Не удалось сохранить язык userId={} code={}", userId, picked.getCode(), ex);
            messenger.send(chatId, i18n.msg(picked, "bot.error.language_save_failed"));
            return;
        }
        log.info("Пользователь {} выбрал язык: {}", userId, picked.getCode());
        messenger.editMessage(chatId, messageId,
                i18n.msg(picked, "bot.start.help"),
                keyboards.mainMenuKeyboard(picked));
    }

    private void handleFromDateInput(long chatId, long userId, String text) {
        BotLanguage lang = resolveLanguage(userId);
        LocalDate date = BotInputParser.parseDate(text);
        if (date == null) {
            messenger.send(chatId, i18n.msg(lang, "bot.error.invalid_date_format"));
            return;
        }
        UserSession session = getSession(userId);
        session.setFromDate(date.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        session.setState(UserSession.State.AWAITING_TO_DATE);
        messenger.sendWithKeyboard(chatId,
                i18n.msg(lang, "bot.prompt.from_date_set", date.format(BotInputParser.dateFormat()))
                        + i18n.msg(lang, "bot.prompt.to_date_inline"),
                keyboards.toDateKeyboard(lang));
    }

    private void handleToDateInput(long chatId, long userId, String text) {
        BotLanguage lang = resolveLanguage(userId);
        LocalDate date = BotInputParser.parseDate(text);
        if (date == null) {
            messenger.send(chatId, i18n.msg(lang, "bot.error.invalid_date_format"));
            return;
        }
        getSession(userId).setToDate(date.atTime(END_OF_DAY).toString());
        startExport(chatId, userId, 0);
    }

    private void startExport(long chatId, long userId, int editMessageId) {
        BotLanguage lang = resolveLanguage(userId);
        UserSession session = getSession(userId);

        if (checkActiveExportAndNotify(chatId, userId, lang)) {
            return;
        }

        String taskId;
        String targetIdentifier = session.getChatId();

        if (targetIdentifier == null) {
            messenger.send(chatId, i18n.msg(lang, "bot.error.session_expired"));
            session.reset();
            return;
        }

        try {
            taskId = jobProducer.enqueue(userId, chatId, targetIdentifier,
                    session.getTopicId(), session.getFromDate(), session.getToDate());
        } catch (IllegalStateException e) {
            log.warn("Попытка дублирующего экспорта от пользователя {}: {}", userId, e.getMessage());
            messenger.send(chatId, i18n.msg(lang, "bot.error.active_export_exists"));
            session.reset();
            return;
        } catch (Exception e) {
            log.error("Ошибка при постановке задачи в очередь: {}", e.getMessage(), e);
            messenger.send(chatId, i18n.msg(lang, "bot.error.queue_fail"));
            session.reset();
            return;
        }

        // Захватываем данные сессии ДО reset() — иначе в сообщении будет «Чат: null».
        String chatDisplay = session.getChatDisplay();
        String dateInfo = buildDateInfoText(lang, session);

        boolean fromCache = jobProducer.isLikelyCached(targetIdentifier);
        long pendingInQueue = jobProducer.getQueueLength();
        boolean hasActiveJob = jobProducer.hasActiveProcessingJob();
        String queueInfo = buildQueueInfoText(lang, fromCache, pendingInQueue, hasActiveJob);

        String resultText = i18n.msg(lang, "bot.task.accepted",
                taskId, chatDisplay, dateInfo, queueInfo);

        InlineKeyboardMarkup cancelKeyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text(i18n.msg(lang, "bot.button.cancel_export"))
                                .callbackData(CB_CANCEL_EXPORT)
                                .build()))
                .build();

        int sentMsgId;
        if (editMessageId > 0) {
            messenger.editMessage(chatId, editMessageId, resultText, cancelKeyboard);
            sentMsgId = editMessageId;
        } else {
            sentMsgId = messenger.sendWithKeyboardGetId(chatId, resultText, cancelKeyboard);
        }
        if (sentMsgId > 0) {
            jobProducer.storeQueueMsgId(taskId, chatId, sentMsgId);
        }

        log.info("Пользователь {} запросил экспорт чата {}, taskId={}, from={}, to={}",
                userId, chatDisplay, taskId, session.getFromDate(), session.getToDate());

        session.reset();
    }

    private void sendDateChoiceMenu(long chatId, BotLanguage lang, String display) {
        messenger.sendWithKeyboard(chatId,
                i18n.msg(lang, "bot.prompt.choose_range", display),
                keyboards.dateChoiceKeyboard(lang));
    }

    private void startQuickRangeExport(long chatId, long userId, int messageId, int days) {
        UserSession session = getSession(userId);
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        session.setFromDate(from.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        session.setToDate(null);
        startExport(chatId, userId, messageId);
    }

    private boolean checkActiveExportAndNotify(long chatId, long userId, BotLanguage lang) {
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId != null) {
            messenger.send(chatId, i18n.msg(lang, "bot.error.active_export_exists_id", activeTaskId));
            return true;
        }
        return false;
    }

    private UserSession getSession(long userId) {
        return sessionRegistry.get(userId);
    }

    /**
     * Язык, сохранённый в БД. {@code null} — ещё не выбран (показать клавиатуру выбора).
     */
    private BotLanguage resolveStoredLanguage(long userId) {
        return userUpserter.getLanguage(userId)
                .flatMap(BotLanguage::fromCode)
                .orElse(null);
    }

    /**
     * Язык для рендера UI. Если в БД нет — {@link BotLanguage#EN}: универсальный fallback,
     * гарантирующий понятный ответ до выбора.
     */
    private BotLanguage resolveLanguage(long userId) {
        BotLanguage stored = resolveStoredLanguage(userId);
        return stored != null ? stored : BotLanguage.EN;
    }

    private String buildQueueInfoText(BotLanguage lang, boolean fromCache, long pendingInQueue, boolean hasActiveJob) {
        if (fromCache) {
            return i18n.msg(lang, "bot.queue.cached");
        }
        // pendingInQueue includes this job; aheadCount excludes it
        long aheadCount = (pendingInQueue - 1) + (hasActiveJob ? 1 : 0);
        long myPosition = pendingInQueue + (hasActiveJob ? 1 : 0);
        if (aheadCount <= 0) {
            return i18n.msg(lang, "bot.queue.starting");
        }
        return i18n.msg(lang, "bot.queue.position", myPosition, aheadCount);
    }

    private String buildDateInfoText(BotLanguage lang, UserSession session) {
        if (session.getFromDate() == null && session.getToDate() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(i18n.msg(lang, "bot.date.prefix")).append(' ');
        if (session.getFromDate() != null) {
            LocalDate from = LocalDateTime.parse(session.getFromDate()).toLocalDate();
            sb.append(i18n.msg(lang, "bot.date.from", from.format(BotInputParser.dateFormat())));
        } else {
            sb.append(i18n.msg(lang, "bot.date.from_chat_start"));
        }
        if (session.getToDate() != null) {
            LocalDate to = LocalDateTime.parse(session.getToDate()).toLocalDate();
            sb.append(i18n.msg(lang, "bot.date.to", to.format(BotInputParser.dateFormat())));
        } else {
            sb.append(i18n.msg(lang, "bot.date.to_today"));
        }
        return sb.toString();
    }

    private void publishBotUserSeen(User from) {
        StatsStreamPublisher publisher = statsPublisherProvider.getIfAvailable();
        if (publisher == null || from == null) {
            return;
        }
        try {
            publisher.publish(StatsEventPayload.builder()
                    .type(StatsEventType.BOT_USER_SEEN)
                    .botUserId(from.getId())
                    .username(from.getUserName())
                    .displayName(buildDisplayName(from))
                    .ts(Instant.now())
                    .build());
        } catch (Exception ex) {
            log.debug("bot_user.seen не опубликовано: {}", ex.getMessage());
        }
    }

    private static String buildDisplayName(User from) {
        String first = from.getFirstName();
        String last = from.getLastName();
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) {
            sb.append(first);
        }
        if (last != null && !last.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(last);
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
