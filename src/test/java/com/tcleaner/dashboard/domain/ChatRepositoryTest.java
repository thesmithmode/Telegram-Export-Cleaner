package com.tcleaner.dashboard.domain;

import com.tcleaner.core.TelegramExporter;
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
 * CRUD + lookup по составному ключу для {@link Chat}.
 */
@SpringBootTest
@Transactional
@DisplayName("ChatRepository")
class ChatRepositoryTest {

    @Autowired
    private ChatRepository repository;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("findByCanonicalChatIdAndTopicId различает NULL и конкретный topicId")
    void findByCanonicalAndTopic() {
        Instant now = Instant.parse("2026-04-15T12:00:00Z");
        repository.save(Chat.builder()
                .canonicalChatId("-100555")
                .chatIdRaw("@channel")
                .topicId(null)
                .firstSeen(now)
                .lastSeen(now)
                .build());
        repository.save(Chat.builder()
                .canonicalChatId("-100555")
                .chatIdRaw("@channel")
                .topicId(42)
                .firstSeen(now)
                .lastSeen(now)
                .build());

        assertThat(repository.findByCanonicalChatIdAndTopicId("-100555", null))
                .isPresent()
                .get()
                .extracting(Chat::getTopicId).isNull();
        assertThat(repository.findByCanonicalChatIdAndTopicId("-100555", 42))
                .isPresent()
                .get()
                .extracting(Chat::getTopicId).isEqualTo(42);
        assertThat(repository.findByCanonicalChatIdAndTopicId("-100555", 99)).isEmpty();
    }
}
