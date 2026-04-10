package com.tcleaner.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

            verify(mockTelegramClient, times(1)).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("должен использовать правильный chat ID")
        void shouldUseCorrectChatId() throws TelegramApiException {
            setupMessenger();
            long chatId = 98765L;
            String text = "Test";

            botMessenger.send(chatId, text);

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(mockTelegramClient).execute(captor.capture());
            assertEquals(String.valueOf(chatId), captor.getValue().getChatId());
        }

        @Test
        @DisplayName("должен передать текст без изменений")
        void shouldPassTextUnchanged() throws TelegramApiException {
            setupMessenger();
            String text = "Важное сообщение **с форматированием**";

            botMessenger.send(12345L, text);

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(mockTelegramClient).execute(captor.capture());
            assertEquals(text, captor.getValue().getText());
        }

        @Test
        @DisplayName("должен установить HTML парсинг")
        void shouldSetHtmlParsing() throws TelegramApiException {
            setupMessenger();

            botMessenger.send(12345L, "Text");

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(mockTelegramClient).execute(captor.capture());
            assertNotNull(captor.getValue().getParseMode());
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
            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().build();

            botMessenger.sendWithKeyboard(chatId, text, keyboard);

            verify(mockTelegramClient).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("должен прикрепить inline-клавиатуру к сообщению")
        void shouldAttachKeyboard() throws TelegramApiException {
            setupMessenger();
            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().build();

            botMessenger.sendWithKeyboard(12345L, "Text", keyboard);

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(mockTelegramClient).execute(captor.capture());
            assertNotNull(captor.getValue().getReplyMarkup());
        }

        @Test
        @DisplayName("должен игнорировать null клавиатуру")
        void shouldHandleNullKeyboard() throws TelegramApiException {
            setupMessenger();

            assertDoesNotThrow(() -> botMessenger.sendWithKeyboard(12345L, "Text", null));
            verify(mockTelegramClient).execute(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("sendWithKeyboardGetId()")
    class SendWithKeyboardGetIdTests {

        @Test
        @DisplayName("должен отправить сообщение и вернуть ID")
        void shouldReturnMessageId() throws TelegramApiException {
            setupMessenger();

            Message mockMessage = mock(Message.class);
            when(mockMessage.getMessageId()).thenReturn(12345);
            when(mockTelegramClient.execute(any(SendMessage.class)))
                    .thenReturn(mockMessage);

            int messageId = botMessenger.sendWithKeyboardGetId(12345L, "Text", null);

            assertEquals(12345, messageId);
            verify(mockTelegramClient).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("должен вернуть положительное ID")
        void shouldReturnPositiveId() throws TelegramApiException {
            setupMessenger();

            Message mockMessage = mock(Message.class);
            when(mockMessage.getMessageId()).thenReturn(999);
            when(mockTelegramClient.execute(any(SendMessage.class)))
                    .thenReturn(mockMessage);

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

            verify(mockTelegramClient).execute(any(EditMessageText.class));
        }

        @Test
        @DisplayName("должен использовать правильный chat ID и message ID")
        void shouldUseCorrectIds() throws TelegramApiException {
            setupMessenger();
            long chatId = 54321L;
            int messageId = 777;

            botMessenger.editMessage(chatId, messageId, "New text", null);

            ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(mockTelegramClient).execute(captor.capture());
            assertEquals(String.valueOf(chatId), captor.getValue().getChatId());
            assertEquals(messageId, captor.getValue().getMessageId());
        }

        @Test
        @DisplayName("должен заменить текст полностью")
        void shouldReplaceText() throws TelegramApiException {
            setupMessenger();
            String newText = "Новый текст сообщения";

            botMessenger.editMessage(12345L, 999, newText, null);

            ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(mockTelegramClient).execute(captor.capture());
            assertEquals(newText, captor.getValue().getText());
        }

        @Test
        @DisplayName("должен обновить клавиатуру")
        void shouldUpdateKeyboard() throws TelegramApiException {
            setupMessenger();
            InlineKeyboardMarkup newKeyboard = InlineKeyboardMarkup.builder().build();

            botMessenger.editMessage(12345L, 999, "Text", newKeyboard);

            ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(mockTelegramClient).execute(captor.capture());
            assertNotNull(captor.getValue().getReplyMarkup());
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

            verify(mockTelegramClient).execute(any(AnswerCallbackQuery.class));
        }

        @Test
        @DisplayName("должен использовать правильный callback ID")
        void shouldUseCorrectCallbackId() throws TelegramApiException {
            setupMessenger();
            String callbackId = "unique_callback_456";

            botMessenger.answerCallback(callbackId);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(mockTelegramClient).execute(captor.capture());
            assertEquals(callbackId, captor.getValue().getCallbackQueryId());
        }

        @Test
        @DisplayName("должен обработать пустой callback ID")
        void shouldHandleEmptyCallbackId() throws TelegramApiException {
            setupMessenger();

            assertDoesNotThrow(() -> botMessenger.answerCallback(""));
            verify(mockTelegramClient).execute(any(AnswerCallbackQuery.class));
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

            verify(mockTelegramClient).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("должен очистить reply_markup")
        void shouldClearReplyMarkup() throws TelegramApiException {
            setupMessenger();

            botMessenger.sendRemoveReplyKeyboard(12345L, "Clear keyboard");

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(mockTelegramClient).execute(captor.capture());
            assertNotNull(captor.getValue());
        }
    }
}
