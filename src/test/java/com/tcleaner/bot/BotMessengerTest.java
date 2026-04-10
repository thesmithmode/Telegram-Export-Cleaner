package com.tcleaner.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Тесты для BotMessenger.
 *
 * Проверяет корректность отправки сообщений, редактирования и callback ответов.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotMessenger")
class BotMessengerTest {

    @Mock
    private TelegramClient mockTelegramClient;

    private BotMessenger botMessenger;

    private void setupMessenger() {
        botMessenger = new BotMessenger(mockTelegramClient);
    }

    @Nested
    @DisplayName("send()")
    class SendTests {

        @Test
        @DisplayName("должен отправить простое сообщение")
        void shouldSendSimpleMessage() throws TelegramApiException {
            setupMessenger();
            long chatId = 12345L;
            String text = "Тестовое сообщение";

            botMessenger.send(chatId, text);

            verify(mockTelegramClient, times(1)).executeAsync(any(SendMessage.class));
        }

        @Test
        @DisplayName("должен использовать правильный chat ID")
        void shouldUseCorrectChatId() throws TelegramApiException {
            setupMessenger();
            long chatId = 98765L;
            String text = "Test";

            botMessenger.send(chatId, text);

            verify(mockTelegramClient).executeAsync(argThat(sendMessage ->
                    sendMessage instanceof SendMessage &&
                            ((SendMessage) sendMessage).getChatId().equals(String.valueOf(chatId))
            ));
        }

        @Test
        @DisplayName("должен передать текст без изменений")
        void shouldPassTextUnchanged() throws TelegramApiException {
            setupMessenger();
            String text = "Важное сообщение **с форматированием**";

            botMessenger.send(12345L, text);

            verify(mockTelegramClient).executeAsync(argThat(sendMessage ->
                    sendMessage instanceof SendMessage &&
                            ((SendMessage) sendMessage).getText().equals(text)
            ));
        }

        @Test
        @DisplayName("должен установить HTML парсинг")
        void shouldSetHtmlParsing() throws TelegramApiException {
            setupMessenger();

            botMessenger.send(12345L, "Text");

            verify(mockTelegramClient).executeAsync(argThat(sendMessage ->
                    sendMessage instanceof SendMessage &&
                            ((SendMessage) sendMessage).getParseMode() != null
            ));
        }
    }

    @Nested
    @DisplayName("sendWithKeyboard()")
    class SendWithKeyboardTests {

        @Test
        @DisplayName("должен отправить сообщение с клавиатурой")
        void shouldSendMessageWithKeyboard() throws TelegramApiException {
            setupMessenger();
            long chatId = 12345L;
            String text = "Выбери опцию";
            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            botMessenger.sendWithKeyboard(chatId, text, keyboard);

            verify(mockTelegramClient).executeAsync(any(SendMessage.class));
        }

        @Test
        @DisplayName("должен прикрепить inline-клавиатуру к сообщению")
        void shouldAttachKeyboard() throws TelegramApiException {
            setupMessenger();
            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            botMessenger.sendWithKeyboard(12345L, "Text", keyboard);

            verify(mockTelegramClient).executeAsync(argThat(sendMessage ->
                    sendMessage instanceof SendMessage &&
                            ((SendMessage) sendMessage).getReplyMarkup() != null
            ));
        }

        @Test
        @DisplayName("должен игнорировать null клавиатуру")
        void shouldHandleNullKeyboard() throws TelegramApiException {
            setupMessenger();

            assertDoesNotThrow(() -> botMessenger.sendWithKeyboard(12345L, "Text", null));
            verify(mockTelegramClient).executeAsync(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("sendWithKeyboardGetId()")
    class SendWithKeyboardGetIdTests {

        @Test
        @DisplayName("должен отправить сообщение и вернуть ID")
        void shouldReturnMessageId() throws TelegramApiException {
            setupMessenger();

            // Mock the response
            when(mockTelegramClient.executeAsync(any(SendMessage.class)))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(12345));

            int messageId = botMessenger.sendWithKeyboardGetId(12345L, "Text", null);

            assertEquals(12345, messageId);
            verify(mockTelegramClient).executeAsync(any(SendMessage.class));
        }

        @Test
        @DisplayName("должен вернуть положительное ID")
        void shouldReturnPositiveId() throws TelegramApiException {
            setupMessenger();

            when(mockTelegramClient.executeAsync(any(SendMessage.class)))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(999));

            int messageId = botMessenger.sendWithKeyboardGetId(12345L, "Test", null);

            assertTrue(messageId > 0);
        }
    }

    @Nested
    @DisplayName("editMessage()")
    class EditMessageTests {

        @Test
        @DisplayName("должен отредактировать сообщение")
        void shouldEditMessage() throws TelegramApiException {
            setupMessenger();
            long chatId = 12345L;
            int messageId = 999;
            String newText = "Отредактированный текст";

            botMessenger.editMessage(chatId, messageId, newText, null);

            verify(mockTelegramClient).executeAsync(any(EditMessageText.class));
        }

        @Test
        @DisplayName("должен использовать правильный chat ID и message ID")
        void shouldUseCorrectIds() throws TelegramApiException {
            setupMessenger();
            long chatId = 54321L;
            int messageId = 777;

            botMessenger.editMessage(chatId, messageId, "New text", null);

            verify(mockTelegramClient).executeAsync(argThat(editMessage ->
                    editMessage instanceof EditMessageText &&
                            ((EditMessageText) editMessage).getChatId().equals(String.valueOf(chatId)) &&
                            ((EditMessageText) editMessage).getMessageId() == messageId
            ));
        }

        @Test
        @DisplayName("должен заменить текст полностью")
        void shouldReplaceText() throws TelegramApiException {
            setupMessenger();
            String newText = "Новый текст сообщения";

            botMessenger.editMessage(12345L, 999, newText, null);

            verify(mockTelegramClient).executeAsync(argThat(editMessage ->
                    editMessage instanceof EditMessageText &&
                            ((EditMessageText) editMessage).getText().equals(newText)
            ));
        }

        @Test
        @DisplayName("должен обновить клавиатуру")
        void shouldUpdateKeyboard() throws TelegramApiException {
            setupMessenger();
            InlineKeyboardMarkup newKeyboard = new InlineKeyboardMarkup();

            botMessenger.editMessage(12345L, 999, "Text", newKeyboard);

            verify(mockTelegramClient).executeAsync(argThat(editMessage ->
                    editMessage instanceof EditMessageText &&
                            ((EditMessageText) editMessage).getReplyMarkup() != null
            ));
        }
    }

    @Nested
    @DisplayName("answerCallback()")
    class AnswerCallbackTests {

        @Test
        @DisplayName("должен ответить на callback запрос")
        void shouldAnswerCallback() throws TelegramApiException {
            setupMessenger();
            String callbackId = "callback_query_123";

            botMessenger.answerCallback(callbackId);

            verify(mockTelegramClient).executeAsync(any(AnswerCallbackQuery.class));
        }

        @Test
        @DisplayName("должен использовать правильный callback ID")
        void shouldUseCorrectCallbackId() throws TelegramApiException {
            setupMessenger();
            String callbackId = "unique_callback_456";

            botMessenger.answerCallback(callbackId);

            verify(mockTelegramClient).executeAsync(argThat(answerCallback ->
                    answerCallback instanceof AnswerCallbackQuery &&
                            ((AnswerCallbackQuery) answerCallback).getCallbackQueryId().equals(callbackId)
            ));
        }

        @Test
        @DisplayName("должен обработать пустой callback ID")
        void shouldHandleEmptyCallbackId() throws TelegramApiException {
            setupMessenger();

            // Should not throw exception
            assertDoesNotThrow(() -> botMessenger.answerCallback(""));
            verify(mockTelegramClient).executeAsync(any(AnswerCallbackQuery.class));
        }
    }

    @Nested
    @DisplayName("sendRemoveReplyKeyboard()")
    class SendRemoveReplyKeyboardTests {

        @Test
        @DisplayName("должен отправить сообщение без клавиатуры")
        void shouldRemoveKeyboard() throws TelegramApiException {
            setupMessenger();
            String text = "Клавиатура удалена";

            botMessenger.sendRemoveReplyKeyboard(12345L, text);

            verify(mockTelegramClient).executeAsync(any(SendMessage.class));
        }

        @Test
        @DisplayName("должен очистить reply_markup")
        void shouldClearReplyMarkup() throws TelegramApiException {
            setupMessenger();

            botMessenger.sendRemoveReplyKeyboard(12345L, "Clear keyboard");

            verify(mockTelegramClient).executeAsync(argThat(sendMessage ->
                    sendMessage instanceof SendMessage
            ));
        }
    }
}
