package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram-бот для запуска экспорта чатов.
 *
 * <p>Бот работает только в личных сообщениях (private chat).
 * Пользователь просто отправляет идентификатор чата в любом формате:</p>
 * <ul>
 *   <li>{@code https://t.me/durov} — ссылка</li>
 *   <li>{@code @durov} — username с @</li>
 *   <li>{@code durov} — username без @</li>
 *   <li>{@code -1001234567890} — числовой ID</li>
 * </ul>
 *
 * <p>Команда {@code /export} поддерживается для обратной совместимости.</p>
 *
 * <p>После успешного экспорта Python-воркер отправит пользователю файл напрямую через Bot API.</p>
 *
 * <p>Регистрация и инициализация производится через {@link BotInitializer}.</p>
 */
@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ExportBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(ExportBot.class);

    private static final Pattern TME_LINK_PATTERN =
            Pattern.compile("https?://t\\.me/([a-zA-Z][a-zA-Z0-9_]{3,})");

    private static final String HELP_TEXT = """
            Этот бот экспортирует историю Telegram-чата и отправляет очищенный текст.

            Просто отправьте мне идентификатор чата:
            • Ссылку: https://t.me/durov
            • Username: @durov или durov
            • ID чата: -1001234567890

            Для приватных чатов аккаунт должен быть их участником.
            """;

    private final String botUsername;
    private final ExportJobProducer jobProducer;

    /**
     * Конструктор.
     *
     * @param botToken   токен бота (из переменной окружения TELEGRAM_BOT_TOKEN)
     * @param botUsername имя бота (из переменной окружения TELEGRAM_BOT_USERNAME)
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
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Message message = update.getMessage();
        long chatId = message.getChatId();
        long userId = message.getFrom().getId();
        String text = message.getText().trim();

        // Бот работает только в личных сообщениях
        if (!"private".equals(message.getChat().getType())) {
            return;
        }

        log.debug("Получено сообщение от userId={}: {}", userId, text);

        if (text.startsWith("/start") || text.startsWith("/help")) {
            sendText(chatId, HELP_TEXT);
        } else if (text.startsWith("/export")) {
            // Обратная совместимость: /export <identifier>
            handleExport(chatId, userId, text);
        } else if (text.startsWith("/")) {
            sendText(chatId, "Неизвестная команда. Используйте /help для справки.");
        } else {
            // Основной flow: пользователь просто отправляет идентификатор чата
            handleExportDirect(chatId, userId, text);
        }
    }

    /**
     * Обрабатывает команду /export <chat_id> (обратная совместимость).
     *
     * @param chatId Telegram chat ID пользователя (куда отвечать)
     * @param userId Telegram user ID пользователя
     * @param text   полный текст сообщения
     */
    private void handleExport(long chatId, long userId, String text) {
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            sendText(chatId, HELP_TEXT);
            return;
        }

        handleExportDirect(chatId, userId, parts[1].trim());
    }

    /**
     * Обрабатывает прямой ввод идентификатора чата (основной flow).
     *
     * <p>Принимает ссылку t.me, @username, просто username или числовой ID.</p>
     *
     * @param chatId Telegram chat ID пользователя (куда отвечать)
     * @param userId Telegram user ID пользователя
     * @param input  идентификатор чата от пользователя
     */
    private void handleExportDirect(long chatId, long userId, String input) {
        String taskId;
        String chatDisplay;

        try {
            String username = extractUsername(input);
            if (username != null) {
                taskId = jobProducer.enqueue(userId, chatId, username);
                chatDisplay = "@" + username;
            } else {
                long targetChatId = Long.parseLong(input);
                taskId = jobProducer.enqueue(userId, chatId, targetChatId);
                chatDisplay = String.valueOf(targetChatId);
            }
        } catch (NumberFormatException e) {
            sendText(chatId,
                    "Неверный формат. Отправьте ссылку, @username или числовой ID."
                    + "\n\nПример: https://t.me/durov");
            return;
        } catch (Exception e) {
            log.error("Ошибка при постановке задачи в очередь: {}", e.getMessage(), e);
            sendText(chatId, "Произошла ошибка при добавлении задачи. Попробуйте позже.");
            return;
        }

        sendText(chatId, String.format(
                "⏳ Задача принята!\n\nID: %s\nЧат: %s\n\n"
                + "Когда воркер обработает — вы получите файл здесь.",
                taskId, chatDisplay));
        log.info("Пользователь {} запросил экспорт чата {}, taskId={}", userId, chatDisplay, taskId);
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
     * Отправляет текстовое сообщение пользователю.
     *
     * @param chatId получатель
     * @param text   текст сообщения
     */
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
}
