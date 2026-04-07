package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButtonRequestChat;
import org.telegram.telegrambots.meta.api.objects.ChatShared;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram-бот для запуска экспорта чатов (Версия 9.5.0).
 */
@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ExportBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportBot.class);

    private static final Pattern TME_LINK_PATTERN =
            Pattern.compile("https?://t\\.me/([a-zA-Z][a-zA-Z0-9_]{3,})");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    static final String PICKER_REQUEST_ID_GROUP = "1";
    static final String PICKER_REQUEST_ID_CHANNEL = "2";

    private static final String CANONICAL_PREFIX = "canonical:";

    private static final String HELP_TEXT = """
            Этот бот экспортирует историю Telegram-чата и отправляет очищенный текст.

            Нажмите кнопку ниже чтобы выбрать группу или канал, или введите вручную:
            • Ссылку: https://t.me/durov
            • Username: @durov или durov
            • ID чата: -1001234567890

            Для приватных чатов аккаунт должен быть их участником.
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
    private final StringRedisTemplate redis;
    private final TelegramClient telegramClient;
    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();

    public ExportBot(
            @Value("${telegram.bot.token}") String botToken,
            ExportJobProducer jobProducer,
            StringRedisTemplate redis
    ) {
        this.botToken = botToken;
        this.jobProducer = jobProducer;
        this.redis = redis;
        this.telegramClient = new OkHttpTelegramClient(botToken);
        log.info("Telegram-бот инициализирован через SpringLongPollingBot");
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

        ChatShared chatShared = message.getChatShared();
        if (chatShared != null) {
            handleChatShared(chatId, userId, chatShared);
            return;
        }

        if (message.hasText()) {
            handleMessageText(chatId, userId, message.getText().trim());
        }
    }

    private void handleCallbackSafe(CallbackQuery callback) {
        try {
            handleCallback(callback);
        } catch (Exception e) {
            log.error("Callback error for user {}: {}", callback.getFrom().getId(), e.getMessage());
            answerCallback(callback.getId());
        }
    }

    private void handleChatShared(long chatId, long userId, ChatShared chatShared) {
        long sharedChatId = chatShared.getChatId();
        String sharedUsername = chatShared.getUsername();
        
        String chatIdentifier;
        if (sharedUsername != null && !sharedUsername.isBlank()) {
            chatIdentifier = sharedUsername;
        } else {
            chatIdentifier = resolveChatIdentifierWithFallback(sharedChatId);
        }
        log.info("Export via ChatShared: userId={}, target={}", userId, chatIdentifier);
        handleExportDirect(chatId, userId, chatIdentifier);
    }

    private void handleMessageText(long chatId, long userId, String text) {
        if (text.startsWith("/start") || text.startsWith("/help")) {
            getSession(userId).reset();
            sendMainMenu(chatId, HELP_TEXT);
        } else if (text.startsWith("/export")) {
            handleExport(chatId, userId, text);
        } else {
            handleTextInput(chatId, userId, text);
        }
    }

    private String resolveChatIdentifierWithFallback(long rawId) {
        log.info("Resolving chat identifier for rawId={}", rawId);

        // Check Redis cache first
        try {
            String cached = redis.opsForValue().get(CANONICAL_PREFIX + rawId);
            if (cached != null && !cached.isBlank()) {
                log.info("Found cached username in Redis: {} → {}", rawId, cached);
                return cached;
            }
        } catch (Exception e) {
            log.debug("Redis lookup failed: {}", e.getMessage());
        }

        // Try GetChat with different ID formats
        List<String> variants = new ArrayList<>();
        variants.add(String.valueOf(rawId));

        if (rawId > 0) {
            variants.add("-100" + rawId);
        } else {
            String s = String.valueOf(rawId);
            if (s.startsWith("-100")) {
                variants.add(s.substring(4));  // Try without -100 prefix
            }
        }

        for (String id : variants) {
            try {
                Chat chat = telegramClient.execute(GetChat.builder().chatId(id).build());

                // SDK 9.5.0: try both getUserName() and getUsername() methods
                String username = null;
                try {
                    username = chat.getUserName();
                } catch (Exception e1) {
                    log.debug("getUserName() failed, trying getUsername()");
                    try {
                        username = chat.getUsername();
                    } catch (Exception e2) {
                        log.debug("getUsername() also failed");
                    }
                }

                log.info("getChat({}): username='{}', title='{}', type='{}'",
                    id, username, chat.getTitle(), chat.getType());

                if (username != null && !username.isBlank()) {
                    // Save to Redis for future requests
                    try {
                        redis.opsForValue().set(CANONICAL_PREFIX + rawId, username, Duration.ofDays(30));
                    } catch (Exception e) {
                        log.debug("Failed to cache username: {}", e.getMessage());
                    }
                    return username;
                }
            } catch (TelegramApiException e) {
                log.warn("getChat({}) failed: {}", id, e.getMessage());
            }
        }

        // Could not resolve to username
        log.error("⚠️ Failed to resolve chat {} to username via GetChat. " +
                "Picker may not have returned username and Bot API cannot resolve this ID. " +
                "This chat is private or worker account needs access. Falling back to numeric ID.", rawId);
        return String.valueOf(rawId);
    }

    private void handleTextInput(long chatId, long userId, String text) {
        UserSession session = getSession(userId);
        switch (session.getState()) {
            case AWAITING_FROM_DATE -> handleFromDateInput(chatId, userId, text);
            case AWAITING_TO_DATE -> handleToDateInput(chatId, userId, text);
            default -> handleExportDirect(chatId, userId, text);
        }
    }

    private void handleExport(long chatId, long userId, String text) {
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            sendMainMenu(chatId, HELP_TEXT);
            return;
        }
        handleExportDirect(chatId, userId, parts[1].trim());
    }

    private void handleExportDirect(long chatId, long userId, String input) {
        UserSession session = getSession(userId);
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId != null) {
            InlineKeyboardMarkup cancelKeyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text("❌ Отменить").callbackData(CB_CANCEL_EXPORT).build()))
                .build();
            sendWithInlineKeyboard(chatId, "⏳ У вас уже есть активный экспорт (" + activeTaskId + ").", cancelKeyboard);
            return;
        }

        try {
            String username = extractUsername(input);
            if (username != null) {
                session.setChatId(username);
                session.setChatDisplay("@" + username);
            } else {
                long targetChatId = Long.parseLong(input);
                session.setChatId(targetChatId);
                session.setChatDisplay(String.valueOf(targetChatId));
            }
        } catch (NumberFormatException e) {
            sendText(chatId, "Неверный формат. Отправьте ссылку или @username.");
            return;
        }

        session.setState(UserSession.State.AWAITING_DATE_CHOICE);
        sendDateChoiceMenu(chatId, session.getChatDisplay());
    }

    private void handleCallback(CallbackQuery callback) {
        if (callback.getMessage() == null) {
            log.warn("Callback без message field от пользователя {}", callback.getFrom().getId());
            answerCallback(callback.getId());
            return;
        }

        long chatId = callback.getFrom().getId();
        long userId = callback.getFrom().getId();
        String data = callback.getData();
        int messageId = callback.getMessage().getMessageId();
        UserSession session = getSession(userId);
        answerCallback(callback.getId());

        switch (data) {
            case CB_EXPORT_ALL -> {
                session.setFromDate(null);
                session.setToDate(null);
                startExport(chatId, userId, messageId);
            }
            case CB_DATE_RANGE -> {
                session.setState(UserSession.State.AWAITING_FROM_DATE);
                editMessage(chatId, messageId,
                        "📅 Чат: " + session.getChatDisplay()
                        + "\n\nВведите начальную дату в формате дд.мм.гггг"
                        + "\nНапример: 01.01.2024",
                        buildFromDateKeyboard());
            }
            case CB_FROM_START -> {
                session.setFromDate(null);
                session.setState(UserSession.State.AWAITING_TO_DATE);
                editMessage(chatId, messageId,
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
                editMessage(chatId, messageId, HELP_TEXT, null);
            }
            case CB_BACK_TO_DATE_CHOICE -> {
                session.setFromDate(null);
                session.setToDate(null);
                session.setState(UserSession.State.AWAITING_DATE_CHOICE);
                editMessage(chatId, messageId,
                        buildDateChoiceText(session.getChatDisplay()),
                        buildDateChoiceKeyboard());
            }
            case CB_BACK_TO_FROM_DATE -> {
                session.setToDate(null);
                session.setState(UserSession.State.AWAITING_FROM_DATE);
                editMessage(chatId, messageId,
                        "📅 Чат: " + session.getChatDisplay()
                        + "\n\nВведите начальную дату в формате дд.мм.гггг"
                        + "\nНапример: 01.01.2024",
                        buildFromDateKeyboard());
            }
            case CB_CANCEL_EXPORT -> {
                jobProducer.cancelExport(userId);
                editMessage(chatId, messageId, "✅ Экспорт отменён.", null);
            }
            default -> log.warn("Неизвестный callback: {}", data);
        }
    }

    private void handleFromDateInput(long chatId, long userId, String text) {
        LocalDate date = parseDate(text);
        if (date == null) {
            sendText(chatId, "❌ Неверный формат (дд.мм.гггг)");
            return;
        }
        UserSession session = getSession(userId);
        session.setFromDate(date.atStartOfDay().toString());
        session.setState(UserSession.State.AWAITING_TO_DATE);
        sendWithInlineKeyboard(chatId, "📅 От: " + date.format(DATE_FORMAT) + "\nВведите конечную дату:", buildToDateKeyboard());
    }

    private void handleToDateInput(long chatId, long userId, String text) {
        LocalDate date = parseDate(text);
        if (date == null) {
            sendText(chatId, "❌ Неверный формат");
            return;
        }
        getSession(userId).setToDate(date.atTime(23, 59, 59).toString());
        startExport(chatId, userId, 0);
    }

    private void startExport(long chatId, long userId, int editMessageId) {
        UserSession session = getSession(userId);

        // Проверяем, нет ли активного экспорта
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId != null) {
            String text = "⏳ У вас уже есть активный экспорт (" + activeTaskId
                    + ").\nДождитесь его завершения или отмените.";
            InlineKeyboardMarkup cancelKeyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder()
                                    .text("❌ Отменить текущий экспорт")
                                    .callbackData(CB_CANCEL_EXPORT)
                                    .build()))
                    .build();
            if (editMessageId > 0) {
                editMessage(chatId, editMessageId, text, cancelKeyboard);
            } else {
                sendWithInlineKeyboard(chatId, text, cancelKeyboard);
            }
            return;
        }

        String taskId;
        Object targetChatId = session.getChatId();

        try {
            if (targetChatId instanceof String username) {
                taskId = jobProducer.enqueue(userId, chatId, username,
                        session.getFromDate(), session.getToDate());
            } else {
                taskId = jobProducer.enqueue(userId, chatId, (Long) targetChatId,
                        session.getFromDate(), session.getToDate());
            }
        } catch (IllegalStateException e) {
            // Экспорт уже активен — race condition побеждён на уровне SET NX
            log.warn("Попытка дублирующего экспорта от пользователя {}: {}", userId, e.getMessage());
            sendText(chatId, "⏳ У вас уже есть активный экспорт. Дождитесь его завершения или отмените.");
            return;
        } catch (Exception e) {
            log.error("Ошибка при постановке задачи в очередь: {}", e.getMessage(), e);
            sendText(chatId, "Произошла ошибка при добавлении задачи. Попробуйте позже.");
            session.reset();
            return;
        }

        String dateInfo = buildDateInfoText(session);

        // Проверяем, пойдёт ли задача в express-очередь (кэш доступен)
        boolean fromCache = jobProducer.isLikelyCached(targetChatId);

        long pendingInQueue = jobProducer.getQueueLength();
        boolean hasActiveJob = jobProducer.hasActiveProcessingJob();
        // pendingInQueue включает нашу задачу (только что добавлена).
        // hasActiveJob — воркер прямо сейчас обрабатывает задачу (уже снята из очереди через BLPOP).
        long aheadCount = (pendingInQueue - 1) + (hasActiveJob ? 1 : 0);
        long myPosition = pendingInQueue + (hasActiveJob ? 1 : 0);
        String queueInfo;
        if (fromCache) {
            queueInfo = "\n\n⚡ Данные в кэше — результат будет быстро!";
        } else if (aheadCount == 0) {
            queueInfo = "\n\n⚙️ Задача поставлена в работу, ожидайте...";
        } else {
            queueInfo = String.format(
                    "\n\n📋 Вы в очереди: позиция %d\nВпереди %d задач(и)", myPosition, aheadCount);
        }

        String resultText = String.format(
                "⏳ Задача принята!\n\nID: %s\nЧат: %s%s%s",
                taskId, session.getChatDisplay(), dateInfo, queueInfo);

        InlineKeyboardMarkup cancelKeyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("❌ Отменить экспорт")
                                .callbackData(CB_CANCEL_EXPORT)
                                .build()))
                .build();

        int sentMsgId;
        if (editMessageId > 0) {
            editMessage(chatId, editMessageId, resultText, cancelKeyboard);
            sentMsgId = editMessageId;
        } else {
            sentMsgId = sendWithInlineKeyboardGetId(chatId, resultText, cancelKeyboard);
        }
        if (sentMsgId > 0) {
            jobProducer.storeQueueMsgId(taskId, chatId, sentMsgId);
        }

        log.info("Пользователь {} запросил экспорт чата {}, taskId={}, from={}, to={}",
                userId, session.getChatDisplay(), taskId, session.getFromDate(), session.getToDate());

        session.reset();
    }

    private void sendMainMenu(long chatId, String text) {
        KeyboardButtonRequestChat groupRequest = KeyboardButtonRequestChat.builder()
                .requestId(PICKER_REQUEST_ID_GROUP)
                .chatIsChannel(false)
                .requestUsername(Boolean.TRUE)
                .build();

        KeyboardButton groupButton = KeyboardButton.builder()
                .text("💬 Выбрать группу")
                .requestChat(groupRequest)
                .build();

        KeyboardButtonRequestChat channelRequest = KeyboardButtonRequestChat.builder()
                .requestId(PICKER_REQUEST_ID_CHANNEL)
                .chatIsChannel(true)
                .requestUsername(Boolean.TRUE)
                .build();

        KeyboardButton channelButton = KeyboardButton.builder()
                .text("📢 Выбрать канал")
                .requestChat(channelRequest)
                .build();

        KeyboardRow groupRow = new KeyboardRow();
        groupRow.add(groupButton);
        
        KeyboardRow channelRow = new KeyboardRow();
        channelRow.add(channelButton);

        ReplyKeyboardMarkup markup = ReplyKeyboardMarkup.builder()
                .keyboardRow(groupRow)
                .keyboardRow(channelRow)
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .replyMarkup(markup)
                .build();
        try { telegramClient.execute(msg); } catch (Exception e) { log.error("Menu fail: {}", e.getMessage()); }
    }

    private void sendDateChoiceMenu(long chatId, String display) {
        InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text("📦 Весь чат").callbackData(CB_EXPORT_ALL).build()))
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text("📅 Указать даты").callbackData(CB_DATE_RANGE).build()))
                .build();
        sendWithInlineKeyboard(chatId, "📋 Чат: " + display + "\nВыберите диапазон:", kb);
    }

    private String buildDateChoiceText(String chatDisplay) {
        return "📋 Чат: " + chatDisplay
                + "\n\nВыберите диапазон экспорта:";
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

    static String extractUsername(String input) {
        Matcher matcher = TME_LINK_PATTERN.matcher(input);
        if (matcher.find()) return matcher.group(1);
        if (input.startsWith("@")) return input.substring(1);
        try { Long.parseLong(input); return null; } catch (Exception e) { return input; }
    }

    static LocalDate parseDate(String text) {
        try { return LocalDate.parse(text.trim(), DATE_FORMAT); } catch (Exception e) { return null; }
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

    private int sendWithInlineKeyboardGetId(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .replyMarkup(keyboard)
                .build();
        try {
            Message sent = telegramClient.execute(message);
            return sent != null ? sent.getMessageId() : 0;
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение с кнопками в chat {}: {}", chatId, e.getMessage());
            return 0;
        }
    }

    private void sendText(long chatId, String text) {
        SendMessage m = SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build();
        try { telegramClient.execute(m); } catch (Exception e) { log.error("Send fail: {}", e.getMessage()); }
    }

    private void sendWithInlineKeyboard(long chatId, String text, InlineKeyboardMarkup kb) {
        SendMessage m = SendMessage.builder().chatId(String.valueOf(chatId)).text(text).replyMarkup(kb).build();
        try { telegramClient.execute(m); } catch (Exception e) { log.error("Send KB fail: {}", e.getMessage()); }
    }

    private void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup kb) {
        EditMessageText.EditMessageTextBuilder<?, ?> builder = EditMessageText.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .text(text);
        if (kb != null) {
            builder.replyMarkup(kb);
        }
        try { telegramClient.execute(builder.build()); } catch (Exception ignored) {}
    }

    private void answerCallback(String id) {
        try { telegramClient.execute(AnswerCallbackQuery.builder().callbackQueryId(id).build()); } catch (Exception ignored) {}
    }
}
