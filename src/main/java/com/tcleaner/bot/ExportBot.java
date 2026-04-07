package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.ChatShared;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram-бот для запуска экспорта чатов.
 *
 * <p>Принимает только текстовые команды: username (@channel), ссылку (https://t.me/channel)
 * или числовой ID. Поэтапный диалог для выбора диапазона дат.</p>
 *
 * <h3>Состояния сессии</h3>
 * <ul>
 *   <li>IDLE — ожидание username, ссылки или ID чата</li>
 *   <li>AWAITING_FROM_DATE — ввод начальной даты (дд.мм.гггг) или /all</li>
 *   <li>AWAITING_TO_DATE — ввод конечной даты (дд.мм.гггг) или /today</li>
 * </ul>
 *
 * <h3>Команды</h3>
 * <ul>
 *   <li>/start, /help — справка</li>
 *   <li>/cancel — отмена активного экспорта</li>
 *   <li>@username, https://t.me/username, числовой ID — запуск диалога</li>
 *   <li>/all, /today — быстрые переходы в диалоге дат</li>
 * </ul>
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

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final String CANONICAL_PREFIX = "canonical:";

    private static final String HELP_TEXT = """
            Этот бот экспортирует историю Telegram-чата и отправляет очищенный текст.

            Отправьте одно из:
            • username: @durov или durov
            • ссылка: https://t.me/durov
            • числовой ID: -123456789

            Для приватных чатов аккаунт должен быть их участником.

            Команды: /cancel (отмена активного экспорта)
            """;

    private final String botToken;
    private final ExportJobProducer jobProducer;
    private final StringRedisTemplate redis;
    private final BotMessenger messenger;
    private final TelegramClient telegramClient;
    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();

    public ExportBot(
            @Value("${telegram.bot.token}") String botToken,
            ExportJobProducer jobProducer,
            StringRedisTemplate redis,
            BotMessenger messenger
    ) {
        this.botToken = botToken;
        this.jobProducer = jobProducer;
        this.redis = redis;
        this.messenger = messenger;
        this.telegramClient = new OkHttpTelegramClient(botToken);
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

        // Обработка выбора чата через встроенную Telegram функцию (кнопка выбора)
        ChatShared chatShared = message.getChatShared();
        if (chatShared != null) {
            handleChatShared(chatId, userId, chatShared);
            return;
        }

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

    private void handleChatShared(long chatId, long userId, ChatShared chatShared) {
        long sharedChatId = chatShared.getChatId();
        String sharedUsername = chatShared.getUsername();

        log.info("📢 ChatShared received: chatId={}, username={}, title={}",
                 sharedChatId, sharedUsername, chatShared.getTitle());

        String chatIdentifier;
        if (sharedUsername != null && !sharedUsername.isBlank()) {
            chatIdentifier = sharedUsername;
            log.info("✅ Using username from picker: @{}", chatIdentifier);
        } else {
            log.warn("⚠️ Username is null/blank from picker. Attempting Bot API resolution for ID {}", sharedChatId);
            chatIdentifier = resolveChatIdentifierWithFallback(sharedChatId);
        }
        log.info("Export via ChatShared: userId={}, target={}", userId, chatIdentifier);
        handleChatIdentifier(chatId, userId, chatIdentifier);
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

                String username = getUsernameFromChat(chat);

                log.info("getChat({}): username='{}', title='{}', type='{}'",
                    id, username, chat.getTitle(), chat.getType());

                if (username != null && !username.isBlank()) {
                    // Save to Redis for future requests (30 days TTL)
                    try {
                        redis.opsForValue().set(CANONICAL_PREFIX + rawId, username, 30, TimeUnit.DAYS);
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

    private String getUsernameFromChat(Chat chat) {
        // SDK 9.5.0: try both getUserName and getUsername
        String username = null;
        try {
            // Try getUsername method
            username = chat.getUserName();
        } catch (Exception e) {
            // Fallback to reflection if method doesn't exist
            try {
                java.lang.reflect.Method method = Chat.class.getMethod("getUserName");
                username = (String) method.invoke(chat);
            } catch (Exception ignored) {
            }
        }
        return username;
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
            messenger.send(chatId, "❌ Неверный формат. Отправьте ссылку, @username или числовой ID.");
            return;
        }

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
        String fromText = session.getFromDate() == null ? "начало чата" :
                LocalDate.parse(session.getFromDate().substring(0, 10)).format(DATE_FORMAT);
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
            log.warn("Попытка дублирующего экспорта от пользователя {}: {}", userId, e.getMessage());
            messenger.send(chatId, "⏳ У вас уже есть активный экспорт. Дождитесь его завершения или отправьте /cancel");
            return;
        } catch (Exception e) {
            log.error("Ошибка при постановке задачи в очередь: {}", e.getMessage(), e);
            messenger.send(chatId, "❌ Произошла ошибка при добавлении задачи. Попробуйте позже.");
            session.reset();
            return;
        }

        // Формируем текст подтверждения
        String dateInfo = buildDateInfoText(session);
        boolean fromCache = jobProducer.isLikelyCached(targetChatId);
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

        session.reset();
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
        if (matcher.find()) return matcher.group(1);
        if (input.startsWith("@")) return input.substring(1);
        try { Long.parseLong(input); return null; } catch (Exception e) { return input; }
    }

    static LocalDate parseDate(String text) {
        try { return LocalDate.parse(text.trim(), DATE_FORMAT); } catch (Exception e) { return null; }
    }
}
