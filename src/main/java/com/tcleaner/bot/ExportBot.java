package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram-бот для запуска экспорта чатов (Версия 10.0.0).
 *
 * <p>Принимает идентификатор целевого чата в формате username (@channel) или ссылки
 * (https://t.me/channel), после чего предлагает интерактивный wizard выбора диапазона
 * экспорта через inline-кнопки.</p>
 *
 * <h3>Состояния сессии</h3>
 * <ul>
 *   <li>IDLE — ожидание username или ссылки</li>
 *   <li>AWAITING_DATE_CHOICE — показан wizard: «📦 Весь чат» или «📅 Указать даты»</li>
 *   <li>AWAITING_FROM_DATE — ввод начальной даты (дд.мм.гггг) или кнопка «⏮ С начала чата»</li>
 *   <li>AWAITING_TO_DATE — ввод конечной даты (дд.мм.гггг) или кнопка «⏭ До сегодня»</li>
 * </ul>
 *
 * <h3>Команды</h3>
 * <ul>
 *   <li>/start, /help — справка, автоматически снимает устаревшую reply-клавиатуру</li>
 *   <li>/cancel — отмена активного экспорта</li>
 *   <li>@username, https://t.me/username — запуск диалога</li>
 * </ul>
 *
 * <p>Бот работает только в личных сообщениях (private chat). Сообщения из групп игнорируются.</p>
 *
 * <p>Защита от параллельных экспортов через Redis SET NX.
 * Сессии автоматически очищаются через {@link #evictStaleSessions()} каждые 30 минут.</p>
 */
@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ExportBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportBot.class);

    private static final Pattern TME_LINK_PATTERN =
            Pattern.compile("https?://t\\.me/([a-zA-Z][a-zA-Z0-9_]{3,})");

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^@([a-zA-Z][a-zA-Z0-9_]{3,})$");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final String HELP_TEXT = """
            Этот бот экспортирует историю Telegram-чата и отправляет очищенный текст.

            Отправьте одно из:
            • username: @durov
            • ссылка: https://t.me/durov

            Для приватных чатов аккаунт должен быть их участником.

            Команды: /cancel (отмена активного экспорта)
            """;

    static final String CB_EXPORT_ALL = "export_all";
    static final String CB_DATE_RANGE = "date_range";
    static final String CB_FROM_START = "from_start";
    static final String CB_TO_TODAY = "to_today";
    static final String CB_BACK_TO_MAIN = "back_main";
    static final String CB_BACK_TO_DATE_CHOICE = "back_date_choice";
    static final String CB_BACK_TO_FROM_DATE = "back_from_date";
    static final String CB_CANCEL_EXPORT = "cancel_export";

    private final String botToken;
    private final ExportJobProducer jobProducer;
    private final BotMessenger messenger;
    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();

    public ExportBot(
            @Value("${telegram.bot.token}") String botToken,
            ExportJobProducer jobProducer,
            BotMessenger messenger
    ) {
        this.botToken = botToken;
        this.jobProducer = jobProducer;
        this.messenger = messenger;
        log.info("Telegram-бот инициализирован");
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

        if (message.hasText()) {
            handleMessageText(chatId, userId, message.getText().trim());
        }
    }

    private void handleMessageText(long chatId, long userId, String text) {
        if (text.startsWith("/start") || text.startsWith("/help")) {
            getSession(userId).reset();
            // Снимаем устаревшую reply-клавиатуру (кнопки «Выбрать группу/канал»
            // из прежних версий, закэшированные в Telegram-клиенте пользователя).
            messenger.sendRemoveReplyKeyboard(chatId, HELP_TEXT);
        } else if (text.startsWith("/cancel")) {
            handleCancel(chatId, userId);
        } else {
            handleTextInput(chatId, userId, text);
        }
    }

    private void handleCancel(long chatId, long userId) {
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId == null) {
            messenger.send(chatId, "❌ Нет активного экспорта.");
            return;
        }
        jobProducer.cancelExport(userId);
        messenger.send(chatId, "✅ Экспорт " + activeTaskId + " отменён.");
        getSession(userId).reset();
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
        if (checkActiveExportAndNotify(chatId, userId)) {
            return;
        }

        UserSession session = getSession(userId);
        String identifier = extractUsername(input);
        if (identifier == null) {
            messenger.send(chatId, "❌ Неверный формат. Отправьте ссылку (https://t.me/channel) или @username.");
            return;
        }

        session.setChatId(identifier);
        session.setChatDisplay("@" + identifier);
        session.setState(UserSession.State.AWAITING_DATE_CHOICE);

        sendDateChoiceMenu(chatId, session.getChatDisplay());
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
        UserSession session = getSession(userId);
        messenger.answerCallback(callback.getId());

        switch (data) {
            case CB_EXPORT_ALL -> {
                session.setFromDate(null);
                session.setToDate(null);
                startExport(chatId, userId, messageId);
            }
            case CB_DATE_RANGE -> {
                session.setState(UserSession.State.AWAITING_FROM_DATE);
                messenger.editMessage(chatId, messageId,
                        "📅 Чат: " + session.getChatDisplay()
                                + "\n\nВведите начальную дату в формате дд.мм.гггг"
                                + "\nНапример: 01.01.2024",
                        buildFromDateKeyboard());
            }
            case CB_FROM_START -> {
                session.setFromDate(null);
                session.setState(UserSession.State.AWAITING_TO_DATE);
                messenger.editMessage(chatId, messageId,
                        "📅 Чат: " + session.getChatDisplay()
                                + "\nОт: начало чата"
                                + "\n\nВведите конечную дату в формате дд.мм.гггг"
                                + "\nНапример: 31.12.2025",
                        buildToDateKeyboard());
            }
            case CB_TO_TODAY -> {
                session.setToDate(null);
                startExport(chatId, userId, messageId);
            }
            case CB_BACK_TO_MAIN -> {
                session.reset();
                messenger.editMessage(chatId, messageId, HELP_TEXT, null);
            }
            case CB_BACK_TO_DATE_CHOICE -> {
                session.setFromDate(null);
                session.setToDate(null);
                session.setState(UserSession.State.AWAITING_DATE_CHOICE);
                messenger.editMessage(chatId, messageId,
                        "📋 Чат: " + session.getChatDisplay() + "\nВыберите диапазон:",
                        buildDateChoiceKeyboard());
            }
            case CB_BACK_TO_FROM_DATE -> {
                session.setToDate(null);
                session.setState(UserSession.State.AWAITING_FROM_DATE);
                messenger.editMessage(chatId, messageId,
                        "📅 Чат: " + session.getChatDisplay()
                                + "\n\nВведите начальную дату в формате дд.мм.гггг"
                                + "\nНапример: 01.01.2024",
                        buildFromDateKeyboard());
            }
            case CB_CANCEL_EXPORT -> {
                jobProducer.cancelExport(userId);
                messenger.editMessage(chatId, messageId, "✅ Экспорт отменён.", null);
                session.reset();
            }
            default -> log.warn("Неизвестный callback: {}", data);
        }
    }

    private void handleFromDateInput(long chatId, long userId, String text) {
        LocalDate date = parseDate(text);
        if (date == null) {
            messenger.send(chatId, "❌ Неверный формат (дд.мм.гггг)");
            return;
        }
        UserSession session = getSession(userId);
        session.setFromDate(date.atStartOfDay().toString());
        session.setState(UserSession.State.AWAITING_TO_DATE);
        messenger.sendWithKeyboard(chatId,
                "📅 От: " + date.format(DATE_FORMAT) + "\n\nВведите конечную дату (дд.мм.гггг):",
                buildToDateKeyboard());
    }

    private void handleToDateInput(long chatId, long userId, String text) {
        LocalDate date = parseDate(text);
        if (date == null) {
            messenger.send(chatId, "❌ Неверный формат (дд.мм.гггг)");
            return;
        }
        getSession(userId).setToDate(date.atTime(23, 59, 59).toString());
        startExport(chatId, userId, 0);
    }

    private void startExport(long chatId, long userId, int editMessageId) {
        UserSession session = getSession(userId);

        if (checkActiveExportAndNotify(chatId, userId)) {
            return;
        }

        String taskId;
        String targetIdentifier = session.getChatId();

        try {
            taskId = jobProducer.enqueue(userId, chatId, targetIdentifier,
                    session.getFromDate(), session.getToDate());
        } catch (IllegalStateException e) {
            log.warn("Попытка дублирующего экспорта от пользователя {}: {}", userId, e.getMessage());
            messenger.send(chatId,
                    "⏳ У вас уже есть активный экспорт. Дождитесь его завершения или отправьте /cancel");
            session.reset();
            return;
        } catch (Exception e) {
            log.error("Ошибка при постановке задачи в очередь: {}", e.getMessage(), e);
            messenger.send(chatId, "❌ Произошла ошибка при добавлении задачи. Попробуйте позже.");
            session.reset();
            return;
        }

        // Захватываем данные сессии ДО reset() — иначе в сообщении будет «Чат: null».
        String chatDisplay = session.getChatDisplay();
        String dateInfo = buildDateInfoText(session);

        boolean fromCache = jobProducer.isLikelyCached(targetIdentifier);
        long pendingInQueue = jobProducer.getQueueLength();
        boolean hasActiveJob = jobProducer.hasActiveProcessingJob();
        long aheadCount = (pendingInQueue - 1) + (hasActiveJob ? 1 : 0);
        long myPosition = pendingInQueue + (hasActiveJob ? 1 : 0);

        String queueInfo;
        if (fromCache) {
            queueInfo = "\n\n⚡ Данные в кэше — результат будет быстро!";
        } else if (aheadCount == 0) {
            queueInfo = "\n\n⚙️ Задача поставлена в работу, ожидайте...";
        } else {
            queueInfo = String.format("\n\n📋 Вы в очереди: позиция %d\nВпереди %d задач(и)",
                    myPosition, aheadCount);
        }

        String resultText = String.format(
                "⏳ Задача принята!\n\nID: %s\nЧат: %s%s%s",
                taskId, chatDisplay, dateInfo, queueInfo);

        InlineKeyboardMarkup cancelKeyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("❌ Отменить экспорт")
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

    /**
     * Отправляет меню выбора диапазона дат с inline-кнопками.
     */
    private void sendDateChoiceMenu(long chatId, String display) {
        messenger.sendWithKeyboard(chatId,
                "📋 Чат: " + display + "\n\nВыберите диапазон экспорта:",
                buildDateChoiceKeyboard());
    }

    private InlineKeyboardMarkup buildDateChoiceKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("📦 Весь чат")
                                .callbackData(CB_EXPORT_ALL)
                                .build()))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("📅 Указать диапазон дат")
                                .callbackData(CB_DATE_RANGE)
                                .build()))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("◀️ Назад")
                                .callbackData(CB_BACK_TO_MAIN)
                                .build()))
                .build();
    }

    private InlineKeyboardMarkup buildFromDateKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("⏮ С начала чата")
                                .callbackData(CB_FROM_START)
                                .build()))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("◀️ Назад")
                                .callbackData(CB_BACK_TO_DATE_CHOICE)
                                .build()))
                .build();
    }

    private InlineKeyboardMarkup buildToDateKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("⏭ До сегодня")
                                .callbackData(CB_TO_TODAY)
                                .build()))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("◀️ Назад")
                                .callbackData(CB_BACK_TO_FROM_DATE)
                                .build()))
                .build();
    }

    /**
     * Проверяет наличие активного экспорта у пользователя.
     * Если есть — отправляет уведомление и возвращает true.
     */
    private boolean checkActiveExportAndNotify(long chatId, long userId) {
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId != null) {
            messenger.send(chatId, "⏳ У вас уже есть активный экспорт ("
                    + activeTaskId + ").\nДождитесь его завершения или отправьте /cancel");
            return true;
        }
        return false;
    }

    /**
     * Очистка сессий старше 2 часов.
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void evictStaleSessions() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(2));
        int beforeSize = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().getLastAccess().isBefore(cutoff));
        int removed = beforeSize - sessions.size();
        if (removed > 0) {
            log.info("Evicted {} stale sessions. Current count: {}", removed, sessions.size());
        }
    }

    private UserSession getSession(long userId) {
        UserSession session = sessions.computeIfAbsent(userId, k -> new UserSession());
        session.touch();
        return session;
    }

    private String buildDateInfoText(UserSession session) {
        if (session.getFromDate() == null && session.getToDate() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n📅 ");
        if (session.getFromDate() != null) {
            LocalDate from = LocalDate.parse(session.getFromDate().substring(0, 10));
            sb.append("От: ").append(from.format(DATE_FORMAT));
        } else {
            sb.append("От: начало чата");
        }
        if (session.getToDate() != null) {
            LocalDate to = LocalDate.parse(session.getToDate().substring(0, 10));
            sb.append(" — До: ").append(to.format(DATE_FORMAT));
        } else {
            sb.append(" — До: сегодня");
        }
        return sb.toString();
    }

    static String extractUsername(String input) {
        Matcher matcher = TME_LINK_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher usernameMatcher = USERNAME_PATTERN.matcher(input);
        if (usernameMatcher.matches()) {
            return usernameMatcher.group(1);
        }
        return null;
    }

    static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.trim(), DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }
}
