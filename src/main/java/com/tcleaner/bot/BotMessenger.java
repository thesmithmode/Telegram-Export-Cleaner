package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * Сервис отправки и редактирования сообщений в Telegram.
 *
 * <p>Инкапсулирует отправку обычных текстовых сообщений, сообщений с inline-клавиатурой,
 * редактирование ранее отправленных сообщений и обработку callback-запросов.
 * Все методы синхронные и при ошибках только логируют (не бросают исключения),
 * чтобы обработчики Telegram Long Polling не падали.</p>
 *
 * <p><strong>Потокобезопасен:</strong> может вызываться из разных потоков одновременно.</p>
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
     * Отправляет простое текстовое сообщение в чат.
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

    /**
     * Отправляет сообщение с inline-клавиатурой.
     *
     * @param chatId   ID Telegram чата
     * @param text     текст сообщения
     * @param keyboard inline-клавиатура (может быть {@code null})
     */
    public void sendWithKeyboard(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение с клавиатурой в чат {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Отправляет сообщение с inline-клавиатурой и возвращает ID отправленного сообщения.
     *
     * <p>Используется когда нужно позже редактировать это сообщение (например, для
     * progress-бара или подтверждения экспорта).</p>
     *
     * @param chatId   ID Telegram чата
     * @param text     текст сообщения
     * @param keyboard inline-клавиатура (может быть {@code null})
     * @return ID отправленного сообщения, либо {@code 0} при ошибке
     */
    public int sendWithKeyboardGetId(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .replyMarkup(keyboard)
                .build();
        try {
            Message sent = telegramClient.execute(message);
            return sent != null ? sent.getMessageId() : 0;
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение с клавиатурой в чат {}: {}", chatId, e.getMessage());
            return 0;
        }
    }

    /**
     * Редактирует ранее отправленное сообщение — текст и/или inline-клавиатуру.
     *
     * @param chatId    ID Telegram чата
     * @param messageId ID редактируемого сообщения
     * @param text      новый текст сообщения
     * @param keyboard  новая inline-клавиатура (может быть {@code null} — клавиатура останется)
     */
    public void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        EditMessageText.EditMessageTextBuilder<?, ?> builder = EditMessageText.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .text(text);
        if (keyboard != null) {
            builder.replyMarkup(keyboard);
        }
        try {
            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            log.debug("Не удалось отредактировать сообщение {} в чате {}: {}",
                    messageId, chatId, e.getMessage());
        }
    }

    /**
     * Подтверждает callback query (убирает крутилку у кнопки в Telegram UI).
     *
     * @param callbackQueryId ID callback query
     */
    public void answerCallback(String callbackQueryId) {
        try {
            telegramClient.execute(
                    AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build());
        } catch (TelegramApiException e) {
            log.debug("Не удалось подтвердить callback query {}: {}", callbackQueryId, e.getMessage());
        }
    }

    /**
     * Отправляет сообщение и принудительно снимает старую reply-клавиатуру.
     *
     * <p>Нужно для пользователей, у которых в кэше Telegram осталась reply-клавиатура
     * с кнопками вроде «Выбрать группу/канал» от предыдущих версий бота. Без явного
     * {@link ReplyKeyboardRemove} такая клавиатура остаётся висеть бесконечно.</p>
     *
     * @param chatId ID Telegram чата
     * @param text   текст сообщения
     */
    public void sendRemoveReplyKeyboard(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .replyMarkup(ReplyKeyboardRemove.builder().removeKeyboard(true).build())
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение c ReplyKeyboardRemove в чат {}: {}",
                    chatId, e.getMessage());
        }
    }

    /**
     * Регистрирует список slash-команд бота для подсказок в Telegram-клиентах.
     */
    public void setMyCommands(List<BotCommand> commands) {
        try {
            telegramClient.execute(
                    SetMyCommands.builder()
                            .commands(commands)
                            .scope(new BotCommandScopeDefault())
                            .build()
            );
            log.info("Telegram slash-команды зарегистрированы: {}", commands.size());
        } catch (TelegramApiException e) {
            log.error("Не удалось зарегистрировать slash-команды: {}", e.getMessage());
        }
    }
}
