package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram-бот для запуска экспорта чатов (Версия 9.5.0).
 *
 * <p>Принимает только два формата ввода: username (@channel) и ссылку (https://t.me/channel).
 * Поэтапный диалог для выбора диапазона дат.</p>
 *
 * <h3>Состояния сессии</h3>
 * <ul>
 *   <li>IDLE — ожидание username или ссылки</li>
 *   <li>AWAITING_FROM_DATE — ввод начальной даты (дд.мм.гггг) или /all</li>
 *   <li>AWAITING_TO_DATE — ввод конечной даты (дд.мм.гггг) или /today</li>
 * </ul>
 *
 * <h3>Команды</h3>
 * <ul>
 *   <li>/start, /help — справка</li>
 *   <li>/cancel — отмена активного экспорта</li>
 *   <li>@username, https://t.me/username — запуск диалога</li>
 *   <li>/all, /today — быстрые переходы в диалоге дат</li>
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
        if (!update.hasMessage()) {
            return;
        }

        Message message = update.getMessage();
        if (!"private".equals(message.getChat().getType())) {
            return; // Бот работает только в личных чатах
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
            sendMainMenu(chatId);
        } else if (text.startsWith("/cancel")) {
            handleCancel(chatId, userId);
        } else {
            handleTextInput(chatId, userId, text);
        }
    }

    private void sendMainMenu(long chatId) {
        messenger.send(chatId, HELP_TEXT);
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
        UserSession session = getSession(userId);

        // Проверяем активный экспорт
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId != null) {
            messenger.send(chatId, "⏳ У вас уже есть активный экспорт (" + activeTaskId +
                    ").\nДождитесь его завершения или отправьте /cancel");
            return;
        }

        String identifier = extractUsername(input);
        if (identifier == null) {
            messenger.send(chatId, "❌ Неверный формат. Отправьте ссылку (https://t.me/channel) или @username.");
            return;
        }

        session.setChatId(identifier);
        session.setChatDisplay("@" + identifier);

        // Переходим в режим выбора дат
        session.setState(UserSession.State.AWAITING_FROM_DATE);
        messenger.send(chatId, "📋 Чат: " + session.getChatDisplay() +
                "\n\nВведите дату начала (дд.мм.гггг) или /all для всего чата");
    }

    private void handleFromDateInput(long chatId, long userId, String text) {
        UserSession session = getSession(userId);

        if ("/all".equalsIgnoreCase(text)) {
            session.setFromDate(null);
        } else {
            LocalDate date = parseDate(text);
            if (date == null) {
                messenger.send(chatId, "❌ Неверный формат (дд.мм.гггг или /all)");
                return;
            }
            session.setFromDate(date.atStartOfDay().toString());
        }

        session.setState(UserSession.State.AWAITING_TO_DATE);
        String fromText;
        if (session.getFromDate() == null) {
            fromText = "начало чата";
        } else {
            fromText = LocalDate.parse(session.getFromDate().substring(0, 10)).format(DATE_FORMAT);
        }
        messenger.send(chatId, "📅 От: " + fromText +
                "\n\nВведите дату конца (дд.мм.гггг) или /today для сегодня");
    }

    private void handleToDateInput(long chatId, long userId, String text) {
        UserSession session = getSession(userId);

        if ("/today".equalsIgnoreCase(text)) {
            session.setToDate(null);
        } else {
            LocalDate date = parseDate(text);
            if (date == null) {
                messenger.send(chatId, "❌ Неверный формат (дд.мм.гггг или /today)");
                return;
            }
            session.setToDate(date.atTime(23, 59, 59).toString());
        }

        startExport(chatId, userId);
    }

    private void startExport(long chatId, long userId) {
        UserSession session = getSession(userId);

        // Финальная проверка активного экспорта (на случай race condition)
        String activeTaskId = jobProducer.getActiveExport(userId);
        if (activeTaskId != null) {
            messenger.send(chatId, "⏳ У вас уже есть активный экспорт (" + activeTaskId +
                    ").\nДождитесь его завершения или отправьте /cancel");
            return;
        }

        String taskId;
        String targetIdentifier = session.getChatId();

        try {
            taskId = jobProducer.enqueue(userId, chatId, targetIdentifier,
                    session.getFromDate(), session.getToDate());
        } catch (IllegalStateException e) {
            log.warn("Попытка дублирующего экспорта от пользователя {}: {}", userId, e.getMessage());
            messenger.send(chatId, "⏳ У вас уже есть активный экспорт. Дождитесь его завершения или отправьте /cancel");
            return;
        } catch (Exception e) {
            log.error("Ошибка при постановке задачи в очередь: {}", e.getMessage(), e);
            messenger.send(chatId, "❌ Произошла ошибка при добавлении задачи. Попробуйте позже.");
            return;
        }

        // Успешно поставили в очередь — сбрасываем сессию
        session.reset();

        // Формируем текст подтверждения
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
            queueInfo = String.format("\n\n📋 Вы в очереди: позиция %d\nВпереди %d задач(и)", myPosition, aheadCount);
        }

        String resultText = String.format(
                "⏳ Задача принята!\n\nID: %s\nЧат: %s%s%s",
                taskId, session.getChatDisplay(), dateInfo, queueInfo);

        messenger.send(chatId, resultText);

        log.info("Пользователь {} запросил экспорт чата {}, taskId={}, from={}, to={}",
                userId, session.getChatDisplay(), taskId, session.getFromDate(), session.getToDate());
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
        // Пробуем как ссылку https://t.me/username
        Matcher matcher = TME_LINK_PATTERN.matcher(input);
        if (matcher.find()) return matcher.group(1);
        // Пробуем как @username или username (без @)
        Matcher usernameMatcher = USERNAME_PATTERN.matcher(input);
        if (usernameMatcher.matches()) return usernameMatcher.group(1);
        return null;
    }

    static LocalDate parseDate(String text) {
        try { return LocalDate.parse(text.trim(), DATE_FORMAT); } catch (Exception e) { return null; }
    }

    private void sendText(long chatId, String text) {
        SendMessage m = SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build();
        try { telegramClient.execute(m); } catch (Exception e) { log.error("Send fail: {}", e.getMessage()); }
    }

    private void answerCallback(String id) {
        try { telegramClient.execute(AnswerCallbackQuery.builder().callbackQueryId(id).build()); } catch (Exception ignored) {}
    }
}
