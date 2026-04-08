package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Сервис отправки сообщений в Telegram.
 *
 * <p>Инкапсулирует логику отправки текстовых сообщений и обработку ошибок.</p>
 */
@Service
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class BotMessenger {

    private static final Logger log = LoggerFactory.getLogger(BotMessenger.class);

    private final TelegramClient telegramClient;

    public BotMessenger(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    /**
     * Отправляет текстовое сообщение в Telegram чат.
     *
     * <p>Метод синхронный и блокирует поток на время HTTP-запроса.
     * При ошибке отправки только логирует ошибку, не пробрасывает исключение.
     * Гарантирует, что сообщение отправится или будет залогировано.</p>
     *
     * <p><strong>Потокобезопасен:</strong> может быть вызван из разных потоков одновременно.</p>
     *
     * @param chatId ID Telegram чата
     * @param text   текст сообщения
     */
    public void send(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        try {
            telegramClient.execute(message);
            log.debug("Сообщение отправлено в чат {}: {} символов", chatId, text.length());
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение в чат {}: {}", chatId, e.getMessage());
        }
    }
}
