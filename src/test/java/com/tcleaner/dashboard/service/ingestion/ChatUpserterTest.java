package com.tcleaner.dashboard.service.ingestion;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.repository.ChatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Идемпотентность upsert {@link Chat} по {@code (canonicalChatId, topicId)}.
 * При отсутствии canonical — fallback на raw, чтобы NULL не шёл в UNIQUE-индекс.
 */
@SpringBootTest
@Transactional
@DisplayName("ChatUpserter")
class ChatUpserterTest {

    @Autowired
    private ChatUpserter upserter;

    @Autowired
    private ChatRepository repository;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("первый upsert создаёт запись, второй с теми же ключами — обновляет ту же")
    void upsertIdempotentByCanonicalAndTopic() {
        Instant t1 = Instant.parse("2026-04-15T10:00:00Z");
        Instant t2 = Instant.parse("2026-04-15T11:00:00Z");

        Chat first = upserter.upsert("-100123", "@c", null, "Old Title", t1);
        Chat second = upserter.upsert("-100123", "@c", null, "New Title", t2);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getChatTitle()).isEqualTo("New Title");
        assertThat(second.getFirstSeen()).isEqualTo(t1);
        assertThat(second.getLastSeen()).isEqualTo(t2);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("разные topicId на одном canonical — отдельные записи")
    void differentTopicsCreateSeparateRows() {
        Instant ts = Instant.parse("2026-04-15T10:00:00Z");
        Chat main = upserter.upsert("-100123", "@c", null, "Main", ts);
        Chat topic7 = upserter.upsert("-100123", "@c", 7, "Topic 7", ts);

        assertThat(topic7.getId()).isNotEqualTo(main.getId());
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("пустой canonicalChatId → fallback на chatIdRaw")
    void blankCanonicalFallsBackToRaw() {
        Instant ts = Instant.parse("2026-04-15T10:00:00Z");
        Chat chat = upserter.upsert("  ", "@unresolved", null, null, ts);

        assertThat(chat.getCanonicalChatId()).isEqualTo("@unresolved");
        assertThat(chat.getChatIdRaw()).isEqualTo("@unresolved");
    }

    @Test
    @DisplayName("blank chatTitle не перетирает существующий")
    void blankTitlePreservesExisting() {
        Instant ts = Instant.parse("2026-04-15T10:00:00Z");
        upserter.upsert("-100123", "@c", null, "Original", ts);
        Chat chat = upserter.upsert("-100123", "@c", null, null, ts.plusSeconds(60));

        assertThat(chat.getChatTitle()).isEqualTo("Original");
    }
}
