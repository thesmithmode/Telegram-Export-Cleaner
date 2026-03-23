package com.tcleaner.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExportBot.
 *
 * <p>Tests command parsing and job enqueueing.</p>
 */
@DisplayName("ExportBot")
class ExportBotTest {

    private ExportBot bot;
    private ExportJobProducer jobProducerMock;

    @BeforeEach
    void setUp() {
        jobProducerMock = mock(ExportJobProducer.class);
        bot = new ExportBot("test_token", "test_bot", jobProducerMock);
    }

    @DisplayName("Should return correct bot username")
    @Test
    void testGetBotUsername() {
        assert bot.getBotUsername().equals("test_bot");
    }

    @DisplayName("Should handle /start command")
    @Test
    void testStartCommand() throws TelegramApiException {
        Update update = createUpdate("/start");

        bot.onUpdateReceived(update);

        // onUpdateReceived doesn't return anything, we just verify it doesn't throw
    }

    @DisplayName("Should handle /help command")
    @Test
    void testHelpCommand() {
        Update update = createUpdate("/help");

        // Should not throw
        bot.onUpdateReceived(update);
    }

    @DisplayName("Should handle /export with valid chat ID")
    @Test
    void testExportCommandWithValidId() {
        when(jobProducerMock.enqueue(123L, 123L, -1001234567890L))
                .thenReturn("export_task_123");

        Update update = createUpdate("/export -1001234567890");

        bot.onUpdateReceived(update);

        verify(jobProducerMock).enqueue(123L, 123L, -1001234567890L);
    }

    @DisplayName("Should handle /export without chat ID")
    @Test
    void testExportCommandWithoutId() {
        Update update = createUpdate("/export");

        bot.onUpdateReceived(update);

        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString());
    }

    @DisplayName("Should handle /export with username as chat identifier")
    @Test
    void testExportCommandWithUsername() {
        when(jobProducerMock.enqueue(123L, 123L, "some_channel"))
                .thenReturn("export_task_123");

        Update update = createUpdate("/export some_channel");

        bot.onUpdateReceived(update);

        verify(jobProducerMock).enqueue(123L, 123L, "some_channel");
    }

    @DisplayName("Should handle /export with whitespace")
    @Test
    void testExportCommandWithWhitespace() {
        when(jobProducerMock.enqueue(123L, 123L, 999L))
                .thenReturn("export_task_123");

        Update update = createUpdate("/export   999   ");

        bot.onUpdateReceived(update);

        verify(jobProducerMock).enqueue(123L, 123L, 999L);
    }

    @DisplayName("Should handle unknown command")
    @Test
    void testUnknownCommand() {
        Update update = createUpdate("/unknown");

        bot.onUpdateReceived(update);

        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString());
    }

    @DisplayName("Should handle message without text")
    @Test
    void testMessageWithoutText() {
        Update update = new Update();
        Message message = new Message();
        message.setChat(new Chat());
        message.setFrom(new User());
        // Don't set text
        update.setMessage(message);

        bot.onUpdateReceived(update);

        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString());
    }

    @DisplayName("Should handle update without message")
    @Test
    void testUpdateWithoutMessage() {
        Update update = new Update();
        // Don't set message

        bot.onUpdateReceived(update);

        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyLong());
        verify(jobProducerMock, never()).enqueue(anyLong(), anyLong(), anyString());
    }

    @DisplayName("Should propagate job producer errors")
    @Test
    void testJobProducerError() {
        when(jobProducerMock.enqueue(123L, 123L, 456L))
                .thenThrow(new RuntimeException("Redis connection failed"));

        Update update = createUpdate("/export 456");

        // Should handle exception gracefully
        bot.onUpdateReceived(update);
    }

    @DisplayName("Should handle /export with t.me link")
    @Test
    void testExportCommandWithTmeLink() {
        when(jobProducerMock.enqueue(123L, 123L, "strbypass"))
                .thenReturn("export_task_123");

        Update update = createUpdate("/export https://t.me/strbypass");

        bot.onUpdateReceived(update);

        verify(jobProducerMock).enqueue(123L, 123L, "strbypass");
    }

    @DisplayName("Should handle /export with @username")
    @Test
    void testExportCommandWithAtUsername() {
        when(jobProducerMock.enqueue(123L, 123L, "durov"))
                .thenReturn("export_task_123");

        Update update = createUpdate("/export @durov");

        bot.onUpdateReceived(update);

        verify(jobProducerMock).enqueue(123L, 123L, "durov");
    }

    @DisplayName("extractUsername — t.me link")
    @Test
    void testExtractUsernameFromLink() {
        assertThat(ExportBot.extractUsername("https://t.me/strbypass")).isEqualTo("strbypass");
        assertThat(ExportBot.extractUsername("http://t.me/durov")).isEqualTo("durov");
    }

    @DisplayName("extractUsername — @username")
    @Test
    void testExtractUsernameFromAt() {
        assertThat(ExportBot.extractUsername("@durov")).isEqualTo("durov");
    }

    @DisplayName("extractUsername — числовой ID возвращает null")
    @Test
    void testExtractUsernameFromNumericId() {
        assertThat(ExportBot.extractUsername("-1001234567890")).isNull();
        assertThat(ExportBot.extractUsername("123456789")).isNull();
    }

    @DisplayName("extractUsername — простой username")
    @Test
    void testExtractUsernameFromPlainUsername() {
        assertThat(ExportBot.extractUsername("strbypass")).isEqualTo("strbypass");
    }

    /**
     * Helper to create Update with message.
     */
    private Update createUpdate(String text) {
        Update update = new Update();
        Message message = new Message();
        User from = new User();
        from.setId(123L);
        from.setIsBot(false);

        Chat chat = new Chat();
        chat.setId(123L);

        message.setFrom(from);
        message.setChat(chat);
        message.setText(text);
        message.setMessageId(1);

        update.setMessage(message);
        return update;
    }
}
