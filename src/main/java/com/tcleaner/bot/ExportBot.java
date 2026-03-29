package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.ChatShared;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButtonRequestChat;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram-бот для запуска экспорта чатов.
 *
 * <p>Бот работает только в личных сообщениях (private chat).
 * Поддерживает интерактивный wizard с кнопками:</p>
 * <ol>
 *   <li>Выбор чата (кнопка picker или прямой ввод)</li>
 *   <li>Выбор диапазона дат (весь чат или конкретные даты)</li>
 *   <li>Запуск экспорта</li>
 * </ol>
 *
 * <p>Регистрация и инициализация производится через {@link BotInitializer}.</p>
 */
@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ExportBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(ExportBot.class);

    private static final Pattern TME_LINK_PATTERN =
            Pattern.compile("https?://t\\.me/([a-zA-Z][a-zA-Z0-9_]{3,})");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final int CHAT_REQUEST_ID = 1;

    private static final String HELP_TEXT = """
            Этот бот экспортирует историю Telegram-чата и отправляет очищенный текст.

            Нажмите «📂 Выбрать чат» или отправьте идентификатор вручную:
            • Ссылку: https://t.me/durov
            • Username: @durov или durov
            • ID чата: -1001234567890

            Для приватных чатов аккаунт должен быть их участником.
            """;

    // Callback data constants
    static final String CB_EXPORT_ALL = "export_all";
    static final String CB_DATE_RANGE = "date_range";
    static final String CB_FROM_START = "from_start";
    static final String CB_TO_TODAY = "to_today";
    static final String CB_BACK_TO_MAIN = "back_main";
    static final String CB_BACK_TO_DATE_CHOICE = "back_date_choice";
    static final String CB_BACK_TO_FROM_DATE = "back_from_date";
    static final String CB_CANCEL_EXPORT = "cancel_export";

    private final String botUsername;
    private final ExportJobProducer jobProducer;
    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();

    /**
     * Конструктор.
     *
     * @param botToken   токен бота
     * @param botUsername имя бота
     * @param jobProducer сервис добавления задач в Redis-очередь
     */
    public ExportBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            ExportJobProducer jobProducer
    ) {
        super(botToken);
        this.botUsername = botUsername;
        this.jobProducer = jobProducer;
        log.info("Telegram-бот инициализирован: @{}", botUsername);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        // Обработка inline-кнопок (CallbackQuery)
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }

        if (!update.hasMessage()) {
            return;
        }

        Message message = update.getMessage();
        long chatId = message.getChatId();
        long userId = message.getFrom().getId();

        // Бот работает только в личных сообщениях
        if (!"private".equals(message.getChat().getType())) {
            return;
        }

        // Обработка выбора чата через picker (ChatShared)
        if (message.getChatShared() != null) {
            handleChatShared(chatId, userId, message.getChatShared());
            return;
        }

        if (!message.hasText()) {
            return;
        }

        String text = message.getText().trim();
        log.debug("Получено сообщение от userId={}: {}", userId, text);

        if (text.startsWith("/start") || text.startsWith("/help")) {
            getSession(userId).reset();
            sendMainMenu(chatId, HELP_TEXT);
        } else if (text.startsWith("/export")) {
            handleExport(chatId, userId, text);
        } else if (text.startsWith("/")) {
            sendText(chatId, "Неизвестная команда. Используйте /help для справки.");
        } else {
            handleTextInput(chatId, userId, text);
        }
    }

    /**
     * Обрабатывает текстовый ввод в зависимости от состояния сессии.
     */
    private void handleTextInput(long chatId, long userId, String text) {
        UserSession session = getSession(userId);

        switch (session.getState()) {
            case AWAITING_FROM_DATE -> handleFromDateInput(chatId, userId, text);
            case AWAITING_TO_DATE -> handleToDateInput(chatId, userId, text);
            default -> handleExportDirect(chatId, userId, text);
        }
    }

    /**
     * Обрабатывает команду /export (обратная совместимость).
     */
    private void handleExport(long chatId, long userId, String text) {
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            sendMainMenu(chatId, HELP_TEXT);
            return;
        }

        handleExportDirect(chatId, userId, parts[1].trim());
    }

    /**
     * Обрабатывает прямой ввод идентификатора чата.
     * Переводит сессию в AWAITING_DATE_CHOICE.
     */
    private void handleExportDirect(long chatId, long userId, String input) {
        UserSession session = getSession(userId);

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
            sendText(chatId,
                    "Неверный формат. Отправьте ссылку, @username или числовой ID."
                    + "\n\nПример: https://t.me/durov");
            return;
        }

        session.setFromDate(null);
        session.setToDate(null);
        session.setState(UserSession.State.AWAITING_DATE_CHOICE);
        sendDateChoiceMenu(chatId, session.getChatDisplay());
    }

    /**
     * Обрабатывает выбор чата из нативного Telegram picker.
     */
    private void handleChatShared(long chatId, long userId, ChatShared chatShared) {
        long sharedChatId = chatShared.getChatId();
        UserSession session = getSession(userId);

        session.setChatId(sharedChatId);
        session.setChatDisplay(String.valueOf(sharedChatId));
        session.setFromDate(null);
        session.setToDate(null);
        session.setState(UserSession.State.AWAITING_DATE_CHOICE);

        log.info("Пользователь {} выбрал чат {} через picker", userId, sharedChatId);
        sendDateChoiceMenu(chatId, session.getChatDisplay());
    }

    /**
     * Обрабатывает нажатие на inline-кнопку.
     */
    private void handleCallback(CallbackQuery callback) {
        long chatId = callback.getFrom().getId();
        long userId = callback.getFrom().getId();
        String data = callback.getData();
        int messageId = callback.getMessage().getMessageId();

        UserSession session = getSession(userId);

        // Ответить на callback, чтобы убрать "часики"
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
                editMessage(chatId, messageId,
                        "Отправьте идентификатор чата или выберите через кнопку ниже.", null);
                sendMainMenu(chatId, "Выберите чат для экспорта:");
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
                editMessage(chatId, messageId,
                        "🛑 Отмена запрошена. Уже скачанные сообщения сохранены в кэш.",
                        null);
            }
            default -> log.warn("Неизвестный callback: {}", data);
        }
    }

    /**
     * Обрабатывает ввод начальной даты.
     */
    private void handleFromDateInput(long chatId, long userId, String text) {
        UserSession session = getSession(userId);
        LocalDate date = parseDate(text);

        if (date == null) {
            sendText(chatId,
                    "❌ Неверный формат даты. Используйте дд.мм.гггг\nНапример: 01.01.2024");
            return;
        }

        session.setFromDate(date.atStartOfDay().toString());
        session.setState(UserSession.State.AWAITING_TO_DATE);

        sendWithInlineKeyboard(chatId,
                "📅 Чат: " + session.getChatDisplay()
                + "\nОт: " + date.format(DATE_FORMAT)
                + "\n\nВведите конечную дату в формате дд.мм.гггг"
                + "\nНапример: 31.12.2025",
                buildToDateKeyboard());
    }

    /**
     * Обрабатывает ввод конечной даты.
     */
    private void handleToDateInput(long chatId, long userId, String text) {
        UserSession session = getSession(userId);
        LocalDate date = parseDate(text);

        if (date == null) {
            sendText(chatId,
                    "❌ Неверный формат даты. Используйте дд.мм.гггг\nНапример: 31.12.2025");
            return;
        }

        // Конечная дата — конец дня (23:59:59)
        session.setToDate(date.atTime(23, 59, 59).toString());
        startExport(chatId, userId, -1);
    }

    /**
     * Запускает экспорт с текущими параметрами сессии.
     */
    private void startExport(long chatId, long userId, int editMessageId) {
        UserSession session = getSession(userId);

        // Проверяем, нет ли активного экспорта
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId != null) {
            String text = "⏳ У вас уже есть активный экспорт (" + activeTaskId
                    + ").\nДождитесь его завершения или отмените.";
            InlineKeyboardMarkup cancelKeyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(List.of(
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
        } catch (Exception e) {
            log.error("Ошибка при постановке задачи в очередь: {}", e.getMessage(), e);
            sendText(chatId, "Произошла ошибка при добавлении задачи. Попробуйте позже.");
            session.reset();
            return;
        }

        String dateInfo = buildDateInfoText(session);
        String resultText = String.format(
                "⏳ Задача принята!\n\nID: %s\nЧат: %s%s\n\n"
                + "Когда воркер обработает — вы получите файл здесь.",
                taskId, session.getChatDisplay(), dateInfo);

        InlineKeyboardMarkup cancelKeyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("❌ Отменить экспорт")
                                .callbackData(CB_CANCEL_EXPORT)
                                .build()))
                .build();

        if (editMessageId > 0) {
            editMessage(chatId, editMessageId, resultText, cancelKeyboard);
        } else {
            sendWithInlineKeyboard(chatId, resultText, cancelKeyboard);
        }

        log.info("Пользователь {} запросил экспорт чата {}, taskId={}, from={}, to={}",
                userId, session.getChatDisplay(), taskId, session.getFromDate(), session.getToDate());

        session.reset();
    }

    // === Keyboards ===

    /**
     * Отправляет reply-клавиатуру с кнопкой "📂 Выбрать чат".
     */
    private void sendMainMenu(long chatId, String text) {
        KeyboardButtonRequestChat requestChat = new KeyboardButtonRequestChat();
        requestChat.setRequestId(String.valueOf(CHAT_REQUEST_ID));
        requestChat.setChatIsChannel(false);

        KeyboardButton chatButton = new KeyboardButton("📂 Выбрать группу");
        chatButton.setRequestChat(requestChat);

        KeyboardButtonRequestChat requestChannel = new KeyboardButtonRequestChat();
        requestChannel.setRequestId(String.valueOf(CHAT_REQUEST_ID + 1));
        requestChannel.setChatIsChannel(true);

        KeyboardButton channelButton = new KeyboardButton("📢 Выбрать канал");
        channelButton.setRequestChat(requestChannel);

        KeyboardRow row = new KeyboardRow();
        row.add(chatButton);
        row.add(channelButton);

        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row);

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setKeyboard(keyboard);
        markup.setResizeKeyboard(true);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить главное меню в chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Отправляет меню выбора дат для чата.
     */
    private void sendDateChoiceMenu(long chatId, String chatDisplay) {
        sendWithInlineKeyboard(chatId,
                buildDateChoiceText(chatDisplay),
                buildDateChoiceKeyboard());
    }

    private String buildDateChoiceText(String chatDisplay) {
        return "📋 Чат: " + chatDisplay
                + "\n\nВыберите диапазон экспорта:";
    }

    private InlineKeyboardMarkup buildDateChoiceKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📦 Весь чат")
                                .callbackData(CB_EXPORT_ALL)
                                .build()))
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📅 Указать диапазон дат")
                                .callbackData(CB_DATE_RANGE)
                                .build()))
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("◀️ Назад")
                                .callbackData(CB_BACK_TO_MAIN)
                                .build()))
                .build();
    }

    private InlineKeyboardMarkup buildFromDateKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⏮ С начала чата")
                                .callbackData(CB_FROM_START)
                                .build()))
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("◀️ Назад")
                                .callbackData(CB_BACK_TO_DATE_CHOICE)
                                .build()))
                .build();
    }

    private InlineKeyboardMarkup buildToDateKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⏭ До сегодня")
                                .callbackData(CB_TO_TODAY)
                                .build()))
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("◀️ Назад")
                                .callbackData(CB_BACK_TO_FROM_DATE)
                                .build()))
                .build();
    }

    // === Helpers ===

    private UserSession getSession(long userId) {
        return sessions.computeIfAbsent(userId, k -> new UserSession());
    }

    /**
     * Извлекает username из ссылки t.me, @username или простого username.
     *
     * @param input строка от пользователя
     * @return username без @ или null, если input — числовой ID
     */
    static String extractUsername(String input) {
        Matcher matcher = TME_LINK_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (input.startsWith("@")) {
            return input.substring(1);
        }
        try {
            Long.parseLong(input);
            return null;
        } catch (NumberFormatException e) {
            return input;
        }
    }

    /**
     * Парсит дату в формате дд.мм.гггг.
     *
     * @return LocalDate или null при ошибке парсинга
     */
    static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.trim(), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
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

    private void sendText(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение в chat {}: {}", chatId, e.getMessage());
        }
    }

    private void sendWithInlineKeyboard(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setReplyMarkup(keyboard);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение с кнопками в chat {}: {}", chatId, e.getMessage());
        }
    }

    private void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId);
        edit.setMessageId(messageId);
        edit.setText(text);
        if (keyboard != null) {
            edit.setReplyMarkup(keyboard);
        }
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            log.error("Не удалось обновить сообщение {} в chat {}: {}", messageId, chatId, e.getMessage());
        }
    }

    private void answerCallback(String callbackId) {
        try {
            execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
        } catch (TelegramApiException e) {
            log.error("Не удалось ответить на callback {}: {}", callbackId, e.getMessage());
        }
    }
}
