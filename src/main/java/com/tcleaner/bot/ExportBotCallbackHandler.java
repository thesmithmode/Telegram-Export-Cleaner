package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import com.tcleaner.dashboard.service.subscription.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ExportBotCallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(ExportBotCallbackHandler.class);

    private final ExportJobProducer jobProducer;
    private final BotMessenger messenger;
    private final BotI18n i18n;
    private final BotKeyboards keyboards;
    private final BotSessionRegistry sessionRegistry;
    private final BotUserUpserter userUpserter;
    private final SubscriptionService subscriptionService;
    private final ExportBotCommandHandler commandHandler;

    public ExportBotCallbackHandler(
            ExportJobProducer jobProducer,
            BotMessenger messenger,
            BotI18n i18n,
            BotKeyboards keyboards,
            BotSessionRegistry sessionRegistry,
            BotUserUpserter userUpserter,
            SubscriptionService subscriptionService,
            ExportBotCommandHandler commandHandler
    ) {
        this.jobProducer = jobProducer;
        this.messenger = messenger;
        this.i18n = i18n;
        this.keyboards = keyboards;
        this.sessionRegistry = sessionRegistry;
        this.userUpserter = userUpserter;
        this.subscriptionService = subscriptionService;
        this.commandHandler = commandHandler;
    }

    public void handleCallbackSafe(CallbackQuery callback) {
        try {
            handleCallback(callback);
        } catch (Exception ex) {
            long userId = callback.getFrom().getId();
            log.error("Callback error for user {}: {}", userId, ex.getMessage(), ex);
            try {
                messenger.answerCallback(callback.getId());
            } catch (Exception ack) {
                log.warn("answerCallback failed: {}", ack.getMessage());
            }
            Object maybe = callback.getMessage();
            if (maybe instanceof Message cbMessage) {
                try {
                    BotLanguage lang = resolveLanguage(userId);
                    messenger.send(cbMessage.getChatId(), i18n.msg(lang, "bot.error.internal"));
                } catch (Exception notify) {
                    log.warn("Failed to notify user {} about callback error: {}",
                            userId, notify.getMessage());
                }
            }
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
        if (data == null || data.isBlank()) {
            log.warn("Пустой callback от пользователя {}", userId);
            return;
        }

        if (data.startsWith(ExportBot.CB_LANG_PREFIX)) {
            handleLanguageCallback(chatId, userId, messageId,
                    data.substring(ExportBot.CB_LANG_PREFIX.length()));
            return;
        }
        if (data.startsWith(ExportBot.CB_SUB_CONFIRM_PREFIX)) {
            handleSubConfirmCallback(chatId, userId, messageId,
                    data.substring(ExportBot.CB_SUB_CONFIRM_PREFIX.length()));
            return;
        }
        if (ExportBot.CB_SETTINGS_LANGUAGE.equals(data)) {
            messenger.editMessage(chatId, messageId,
                    i18n.msg(BotLanguage.EN, "bot.start.choose_language"),
                    keyboards.languageChoiceKeyboard());
            return;
        }
        if (ExportBot.CB_SETTINGS_OPEN.equals(data)) {
            BotLanguage lang = resolveLanguage(userId);
            messenger.editMessage(chatId, messageId,
                    i18n.msg(lang, "bot.settings.title"),
                    keyboards.settingsKeyboard(lang));
            return;
        }

        BotLanguage lang = resolveLanguage(userId);
        UserSession session = getSession(userId);

        switch (data) {
            case ExportBot.CB_EXPORT_ALL -> {
                if (!requireState(chatId, session, lang, UserSession.State.AWAITING_DATE_CHOICE)) {
                    return;
                }
                session.setFromDate(null);
                session.setToDate(null);
                commandHandler.startExport(chatId, userId, messageId);
            }
            case ExportBot.CB_LAST_24H -> {
                if (!requireState(chatId, session, lang, UserSession.State.AWAITING_DATE_CHOICE)) {
                    return;
                }
                commandHandler.startQuickRangeExport(chatId, userId, messageId, 1);
            }
            case ExportBot.CB_LAST_3D -> {
                if (!requireState(chatId, session, lang, UserSession.State.AWAITING_DATE_CHOICE)) {
                    return;
                }
                commandHandler.startQuickRangeExport(chatId, userId, messageId, 3);
            }
            case ExportBot.CB_LAST_7D -> {
                if (!requireState(chatId, session, lang, UserSession.State.AWAITING_DATE_CHOICE)) {
                    return;
                }
                commandHandler.startQuickRangeExport(chatId, userId, messageId, 7);
            }
            case ExportBot.CB_LAST_30D -> {
                if (!requireState(chatId, session, lang, UserSession.State.AWAITING_DATE_CHOICE)) {
                    return;
                }
                commandHandler.startQuickRangeExport(chatId, userId, messageId, 30);
            }
            case ExportBot.CB_DATE_RANGE -> {
                if (!requireState(chatId, session, lang, UserSession.State.AWAITING_DATE_CHOICE)) {
                    return;
                }
                session.setState(UserSession.State.AWAITING_FROM_DATE);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.prompt.from_range_header", session.getChatDisplay())
                                + i18n.msg(lang, "bot.prompt.from_date"),
                        keyboards.fromDateKeyboard(lang));
            }
            case ExportBot.CB_FROM_START -> {
                if (!requireState(chatId, session, lang, UserSession.State.AWAITING_FROM_DATE)) {
                    return;
                }
                session.setFromDate(null);
                session.setState(UserSession.State.AWAITING_TO_DATE);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.prompt.from_range_header", session.getChatDisplay())
                                + i18n.msg(lang, "bot.prompt.from_start_line")
                                + i18n.msg(lang, "bot.prompt.to_date"),
                        keyboards.toDateKeyboard(lang));
            }
            case ExportBot.CB_TO_TODAY -> {
                if (!requireState(chatId, session, lang, UserSession.State.AWAITING_TO_DATE)) {
                    return;
                }
                session.setToDate(null);
                commandHandler.startExport(chatId, userId, messageId);
            }
            case ExportBot.CB_BACK_TO_MAIN -> {
                session.reset();
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.start.help"),
                        keyboards.mainMenuKeyboard(lang));
            }
            case ExportBot.CB_BACK_TO_DATE_CHOICE -> {
                if (!requireState(chatId, session, lang,
                        UserSession.State.AWAITING_FROM_DATE, UserSession.State.AWAITING_TO_DATE)) {
                    return;
                }
                session.setFromDate(null);
                session.setToDate(null);
                session.setState(UserSession.State.AWAITING_DATE_CHOICE);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.prompt.choose_range_short", session.getChatDisplay()),
                        keyboards.dateChoiceKeyboard(lang));
            }
            case ExportBot.CB_BACK_TO_FROM_DATE -> {
                if (!requireState(chatId, session, lang, UserSession.State.AWAITING_TO_DATE)) {
                    return;
                }
                session.setToDate(null);
                session.setState(UserSession.State.AWAITING_FROM_DATE);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.prompt.from_range_header", session.getChatDisplay())
                                + i18n.msg(lang, "bot.prompt.from_date"),
                        keyboards.fromDateKeyboard(lang));
            }
            default -> {
                if (data.startsWith(ExportBot.CB_CANCEL_EXPORT + ":")) {
                    handleCancelExportCallback(chatId, userId, messageId, data, lang, session);
                    return;
                }
                log.warn("Неизвестный callback: {}", data);
            }
        }
    }

    private boolean requireState(long chatId, UserSession session, BotLanguage lang,
                                 UserSession.State... expected) {
        for (UserSession.State state : expected) {
            if (session.getState() == state) {
                return true;
            }
        }
        messenger.send(chatId, i18n.msg(lang, "bot.error.session_expired"));
        return false;
    }

    private void handleCancelExportCallback(long chatId, long userId, int messageId, String data,
                                            BotLanguage lang, UserSession session) {
        String taskId = data.substring((ExportBot.CB_CANCEL_EXPORT + ":").length());
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId == null || !activeTaskId.equals(taskId)) {
            messenger.editMessage(chatId, messageId, i18n.msg(lang, "bot.cancel.no_active"), null);
            return;
        }
        jobProducer.cancelExport(userId);
        messenger.editMessage(chatId, messageId, i18n.msg(lang, "bot.cancel.ok_simple"), null);
        session.reset();
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

    private void handleSubConfirmCallback(long chatId, long userId, int messageId, String idStr) {
        BotLanguage lang = resolveLanguage(userId);
        long subscriptionId;
        try {
            subscriptionId = Long.parseLong(idStr);
        } catch (NumberFormatException ex) {
            log.warn("Некорректный sub_confirm id: {}", idStr);
            messenger.editMessage(chatId, messageId,
                    i18n.msg(lang, "bot.sub.confirm.invalid"), null);
            return;
        }
        try {
            var subOpt = subscriptionService.findById(subscriptionId);
            if (subOpt.isEmpty() || !subOpt.get().getBotUserId().equals(userId)) {
                log.warn("User {} tried to confirm subscription {} (not owner or missing)",
                        userId, subscriptionId);
                messenger.editMessage(chatId, messageId,
                        i18n.msg(lang, "bot.sub.confirm.not_found"), null);
                return;
            }
            subscriptionService.confirmReceived(subscriptionId);
            messenger.editMessage(chatId, messageId,
                    i18n.msg(lang, "bot.sub.confirm.ok"), null);
            log.info("Пользователь {} подтвердил подписку {}", userId, subscriptionId);
        } catch (java.util.NoSuchElementException ex) {
            messenger.editMessage(chatId, messageId,
                    i18n.msg(lang, "bot.sub.confirm.not_found"), null);
        }
    }

    private BotLanguage resolveLanguage(long userId) {
        return userUpserter.resolveLanguage(userId);
    }

    private UserSession getSession(long userId) {
        return sessionRegistry.get(userId);
    }
}
