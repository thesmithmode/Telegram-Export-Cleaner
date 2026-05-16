package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;

import java.io.Serializable;
import java.util.List;

@Service
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class BotMessenger {

    private static final Logger log = LoggerFactory.getLogger(BotMessenger.class);

    private final TelegramClient telegramClient;

    public BotMessenger(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    private SendMessage.SendMessageBuilder<?, ?> buildMessage(long chatId, String text) {
        return SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text);
    }

    // Все Telegram API вызовы из этого класса — best-effort: ошибка не должна
    // ронять остальной flow (валидацию, очередь, ответ юзеру). Helper унифицирует
    // обработку TelegramApiException: один лог, один return null. Уровень
    // логирования передаётся, чтобы edit/callback (тихие фейлы) шли в debug,
    // а отправка / регистрация команд — в error.
    private <T extends Serializable> T executeQuietly(
            BotApiMethod<T> method, String errorContext, boolean errorLevel) {
        try {
            return telegramClient.execute(method);
        } catch (TelegramApiException e) {
            if (errorLevel) {
                log.error("{}: {}", errorContext, e.getMessage());
            } else {
                log.debug("{}: {}", errorContext, e.getMessage());
            }
            return null;
        }
    }

    public void send(long chatId, String text) {
        SendMessage message = buildMessage(chatId, text).build();
        if (executeQuietly(message,
                "Не удалось отправить сообщение в чат " + chatId, true) != null) {
            log.debug("Сообщение отправлено в чат {}: {} символов", chatId, text.length());
        }
    }

    /**
     * В отличие от {@link #send(long, String)} возвращает статус доставки,
     * чтобы вызывающий мог отдать пользователю 503 / ретрай вместо fire-and-forget.
     */
    public boolean trySend(long chatId, String text) {
        SendMessage message = buildMessage(chatId, text).build();
        Message sent = executeQuietly(message,
                "trySend → fail, chat " + chatId, true);
        if (sent != null) {
            log.debug("trySend → ok, chat {}: {} символов", chatId, text.length());
            return true;
        }
        return false;
    }

    public void sendWithKeyboard(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = buildMessage(chatId, text)
                .replyMarkup(keyboard)
                .build();
        executeQuietly(message,
                "Не удалось отправить сообщение с клавиатурой в чат " + chatId, true);
    }

    public int sendWithKeyboardGetId(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = buildMessage(chatId, text)
                .replyMarkup(keyboard)
                .build();
        Message sent = executeQuietly(message,
                "Не удалось отправить сообщение с клавиатурой в чат " + chatId, true);
        return sent != null ? sent.getMessageId() : 0;
    }

    public void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        EditMessageText.EditMessageTextBuilder<?, ?> builder = EditMessageText.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .text(text);
        if (keyboard != null) {
            builder.replyMarkup(keyboard);
        }
        // Edit fail обычно "message is not modified" / "message to edit not found" —
        // debug-уровень, не засорять error-log.
        executeQuietly(builder.build(),
                "Не удалось отредактировать сообщение " + messageId + " в чате " + chatId, false);
    }

    public void answerCallback(String callbackQueryId) {
        // Stale callback (юзер закрыл диалог) — типичный debug-кейс.
        executeQuietly(
                AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build(),
                "Не удалось подтвердить callback query " + callbackQueryId, false);
    }

    /**
     * Регистрирует slash-команды. Если {@code languageCode} задан (ISO 639-1, напр. "ru"),
     * Telegram показывает этот набор клиентам с соответствующим language_code. Если null —
     * регистрируется default-набор (используется как fallback).
     */
    public void setMyCommands(List<BotCommand> commands, String languageCode) {
        SetMyCommands.SetMyCommandsBuilder<?, ?> builder = SetMyCommands.builder()
                .commands(commands)
                .scope(new BotCommandScopeDefault());
        if (languageCode != null && !languageCode.isBlank()) {
            builder.languageCode(languageCode);
        }
        Boolean ok = executeQuietly(builder.build(),
                "Не удалось зарегистрировать slash-команды (lang=" + languageCode + ")", true);
        if (Boolean.TRUE.equals(ok)) {
            log.info("Telegram slash-команды зарегистрированы ({}): lang={}",
                    commands.size(), languageCode != null ? languageCode : "default");
        }
    }

    public void setChatMenuButton(String url, String title) {
        Boolean ok = executeQuietly(
                SetChatMenuButton.builder()
                        .menuButton(new MenuButtonWebApp(title, new WebAppInfo(url)))
                        .build(),
                "Не удалось установить кнопку меню", true);
        if (Boolean.TRUE.equals(ok)) {
            log.info("Кнопка меню установлена: {}", url);
        }
    }
}
