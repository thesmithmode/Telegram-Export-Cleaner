package com.tcleaner.dashboard.domain;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.repository.BotUserRepository;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ExportEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRUD для {@link ExportEvent} + проверка lookup по {@code taskId}.
 * Parent-строки в bot_users/chats засеиваются в тесте — FK foreign_keys=ON
 * из Hikari init-sql не даёт вставить event без них.
 */
@SpringBootTest
@Transactional
@DisplayName("ExportEventRepository")
class ExportEventRepositoryTest {

    @Autowired
    private ExportEventRepository events;

    @Autowired
    private BotUserRepository users;

    @Autowired
    private ChatRepository chats;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("save → findByTaskId возвращает ту же сущность с корректным enum-mapping")
    void saveAndFindByTaskId() {
        Instant now = Instant.parse("2026-04-15T12:00:00Z");
        BotUser botUser = users.save(BotUser.builder()
                .botUserId(100L).firstSeen(now).lastSeen(now).build());
        Chat chat = chats.save(Chat.builder()
                .canonicalChatId("-100777").chatIdRaw("@c").firstSeen(now).lastSeen(now).build());

        ExportEvent saved = events.save(ExportEvent.builder()
                .taskId("task-abc")
                .botUserId(botUser.getBotUserId())
                .chatRefId(chat.getId())
                .startedAt(now)
                .status(ExportStatus.QUEUED)
                .source(ExportSource.BOT)
                .createdAt(now)
                .updatedAt(now)
                .build());

        ExportEvent found = events.findByTaskId("task-abc").orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getStatus()).isEqualTo(ExportStatus.QUEUED);
        assertThat(found.getSource()).isEqualTo(ExportSource.BOT);
    }
}
