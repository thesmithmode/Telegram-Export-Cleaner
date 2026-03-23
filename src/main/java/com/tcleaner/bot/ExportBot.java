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
 * <p>Команды:</p>
 * <ul>
 *   <li>{@code /start} — приветствие и инструкция</li>
 *   <li>{@code /export <chat_id>} — запустить экспорт чата по ID</li>
 *   <li>{@code /help} — справка</li>
 * </ul>
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

            Команды:
            /export <chat_id или ссылка> — экспортировать чат
            /help — показать эту справку

            Примеры:
            /export https://t.me/durov
            /export @durov
            /export durov
            /export -1001234567890
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

        log.debug("Получено сообщение от userId={}: {}", userId, text);

        if (text.startsWith("/start") || text.startsWith("/help")) {
            sendText(chatId, HELP_TEXT);
        } else if (text.startsWith("/export")) {
            handleExport(chatId, userId, text);
        } else {
            sendText(chatId, "Неизвестная команда. Используйте /help для справки.");
        }
    }

    /**
     * Обрабатывает команду /export <chat_id>.
     *
     * @param chatId Telegram chat ID пользователя (куда отвечать)
     * @param userId Telegram user ID пользователя
     * @param text   полный текст сообщения
     */
    private void handleExport(long chatId, long userId, String text) {
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            sendText(chatId,
                    "Укажите чат:\n/export <ссылка, username или chat_id>"
                    + "\n\nПример: /export https://t.me/durov");
            return;
        }

        String input = parts[1].trim();
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
                    "Неверный формат. Используйте ссылку, @username или числовой ID."
                    + "\n\nПример: /export https://t.me/durov");
            return;
        } catch (Exception e) {
            log.error("Ошибка при постановке задачи в очередь: {}", e.getMessage(), e);
            sendText(chatId, "Произошла ошибка при добавлении задачи. Попробуйте позже.");
            return;
        }

        sendText(chatId, String.format(
                "Задача принята!\n\nID задачи: %s\nЧат: %s\n\n"
                + "Экспорт запущен. Когда воркер обработает — вы получите файл здесь.",
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
