package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ExportBotCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ExportBotCommandHandler.class);

    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private final ExportJobProducer jobProducer;
    private final BotMessenger messenger;
    private final BotI18n i18n;
    private final BotKeyboards keyboards;
    private final BotSessionRegistry sessionRegistry;
    private final BotUserUpserter userUpserter;
    private final QueueDisplayBuilder queueDisplayBuilder;

    public ExportBotCommandHandler(
            ExportJobProducer jobProducer,
            BotMessenger messenger,
            BotI18n i18n,
            BotKeyboards keyboards,
            BotSessionRegistry sessionRegistry,
            BotUserUpserter userUpserter,
            QueueDisplayBuilder queueDisplayBuilder
    ) {
        this.jobProducer = jobProducer;
        this.messenger = messenger;
        this.i18n = i18n;
        this.keyboards = keyboards;
        this.sessionRegistry = sessionRegistry;
        this.userUpserter = userUpserter;
        this.queueDisplayBuilder = queueDisplayBuilder;
    }

    public void handleMessageText(long chatId, long userId, String text) {
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

    public void startExport(long chatId, long userId, int editMessageId) {
        BotLanguage lang = resolveLanguage(userId);
        UserSession session = getSession(userId);

        if (checkActiveExportAndNotify(chatId, userId, lang)) {
            return;
        }

        String targetIdentifier = session.getChatId();

        if (targetIdentifier == null) {
            messenger.send(chatId, i18n.msg(lang, "bot.error.session_expired"));
            session.reset();
            return;
        }

        String taskId;
        try {
            taskId = jobProducer.enqueue(userId, chatId, targetIdentifier,
                    session.getTopicId(), session.getFromDate(), session.getToDate());
        } catch (IllegalStateException ex) {
            log.warn("Попытка дублирующего экспорта от пользователя {}: {}", userId, ex.getMessage());
            messenger.send(chatId, i18n.msg(lang, "bot.error.active_export_exists"));
            session.reset();
            return;
        } catch (Exception ex) {
            log.error("Ошибка при постановке задачи в очередь: {}", ex.getMessage(), ex);
            messenger.send(chatId, i18n.msg(lang, "bot.error.queue_fail"));
            session.reset();
            return;
        }

        String chatDisplay = session.getChatDisplay();
        String dateInfo = queueDisplayBuilder.dateInfo(lang, session);

        boolean fromCache = jobProducer.isLikelyCached(targetIdentifier);
        long pendingInQueue = jobProducer.getQueueLength();
        boolean hasActiveJob = jobProducer.hasActiveProcessingJob();
        String queueInfo = queueDisplayBuilder.build(lang, fromCache, pendingInQueue, hasActiveJob);

        String resultText = i18n.msg(lang, "bot.task.accepted",
                taskId, chatDisplay, dateInfo, queueInfo);

        InlineKeyboardMarkup cancelKeyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text(i18n.msg(lang, "bot.button.cancel_export"))
                                .callbackData(ExportBot.CB_CANCEL_EXPORT)
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

    public void startQuickRangeExport(long chatId, long userId, int messageId, int days) {
        UserSession session = getSession(userId);
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        session.setFromDate(from.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        session.setToDate(null);
        startExport(chatId, userId, messageId);
    }

    public boolean checkActiveExportAndNotify(long chatId, long userId, BotLanguage lang) {
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId != null) {
            messenger.send(chatId,
                    i18n.msg(lang, "bot.error.active_export_exists_id", activeTaskId));
            return true;
        }
        return false;
    }

    private void handleStart(long chatId, long userId) {
        BotLanguage stored = userUpserter.getLanguage(userId)
                .flatMap(BotLanguage::fromCode)
                .orElse(null);
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

        String identifier = BotInputParser.extractUsername(input);
        if (identifier == null) {
            messenger.send(chatId, i18n.msg(lang, "bot.error.invalid_format"));
            return;
        }

        UserSession session = getSession(userId);
        session.setChatId(identifier);
        session.setTopicId(BotInputParser.extractTopicId(input));
        session.setChatDisplay("@" + identifier);
        session.setState(UserSession.State.AWAITING_DATE_CHOICE);

        messenger.sendWithKeyboard(chatId,
                i18n.msg(lang, "bot.prompt.choose_range", session.getChatDisplay()),
                keyboards.dateChoiceKeyboard(lang));
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

    private BotLanguage resolveLanguage(long userId) {
        return userUpserter.resolveLanguage(userId);
    }

    private UserSession getSession(long userId) {
        return sessionRegistry.get(userId);
    }
}
