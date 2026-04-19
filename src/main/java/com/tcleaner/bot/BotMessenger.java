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
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;

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

    public void send(long chatId, String text) {
        SendMessage message = buildMessage(chatId, text).build();
        try {
            telegramClient.execute(message);
            log.debug("Сообщение отправлено в чат {}: {} символов", chatId, text.length());
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение в чат {}: {}", chatId, e.getMessage());
        }
    }

    public void sendWithKeyboard(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = buildMessage(chatId, text)
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение с клавиатурой в чат {}: {}", chatId, e.getMessage());
        }
    }

    public int sendWithKeyboardGetId(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = buildMessage(chatId, text)
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

    public void answerCallback(String callbackQueryId) {
        try {
            telegramClient.execute(
                    AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build());
        } catch (TelegramApiException e) {
            log.debug("Не удалось подтвердить callback query {}: {}", callbackQueryId, e.getMessage());
        }
    }

    public void sendRemoveReplyKeyboard(long chatId, String text) {
        SendMessage message = buildMessage(chatId, text)
                .replyMarkup(ReplyKeyboardRemove.builder().removeKeyboard(true).build())
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение c ReplyKeyboardRemove в чат {}: {}",
                    chatId, e.getMessage());
        }
    }

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

    public void setChatMenuButton(String url, String title) {
        try {
            telegramClient.execute(
                    SetChatMenuButton.builder()
                            .menuButton(new MenuButtonWebApp(title, new WebAppInfo(url)))
                            .build()
            );
            log.info("Кнопка меню установлена: {}", url);
        } catch (TelegramApiException e) {
            log.error("Не удалось установить кнопку меню: {}", e.getMessage());
        }
    }
}
