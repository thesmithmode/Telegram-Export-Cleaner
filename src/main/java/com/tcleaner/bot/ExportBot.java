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
 * <p>Spring Boot starter автоматически регистрирует бот и запускает long polling.</p>
 */
@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ExportBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(ExportBot.class);

    private static final String HELP_TEXT = """
            Этот бот экспортирует историю Telegram-чата и отправляет очищенный текст.

            Команды:
            /export <chat_id> — экспортировать чат
            /help — показать эту справку

            Как узнать chat_id чата:
            • Для личных чатов — ваш числовой Telegram ID (например: 123456789)
            • Для групп/каналов — отрицательный ID (например: -1001234567890)
              Добавьте @userinfobot в группу, он покажет ID.
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
                    "Укажите chat_id чата:\n/export <chat_id>\n\nПример: /export -1001234567890");
            return;
        }

        long targetChatId;
        try {
            targetChatId = Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            sendText(chatId,
                    "Неверный формат chat_id. Должно быть число, например: -1001234567890");
            return;
        }

        try {
            String taskId = jobProducer.enqueue(userId, chatId, targetChatId);
            sendText(chatId, String.format(
                    "Задача принята!\n\nID задачи: %s\nЧат: %d\n\n"
                    + "Экспорт запущен. Когда воркер обработает — вы получите файл здесь.",
                    taskId, targetChatId));
            log.info("Пользователь {} запросил экспорт чата {}, taskId={}", userId, targetChatId, taskId);
        } catch (Exception e) {
            log.error("Ошибка при постановке задачи в очередь: {}", e.getMessage(), e);
            sendText(chatId, "Произошла ошибка при добавлении задачи. Попробуйте позже.");
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
