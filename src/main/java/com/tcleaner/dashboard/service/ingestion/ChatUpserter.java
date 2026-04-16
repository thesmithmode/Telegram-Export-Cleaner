package com.tcleaner.dashboard.service.ingestion;

import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.repository.ChatRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Idempotent upsert для {@link Chat} по составному ключу
 * {@code (canonicalChatId, topicId)}. Если {@code canonicalChatId} пуст
 * (Python ещё не резолвил username) — используем {@code chatIdRaw} как fallback,
 * чтобы не терять событие и не плодить NULL-ы в UNIQUE-индексе.
 */
@Component
public class ChatUpserter {

    private final ChatRepository repository;

    public ChatUpserter(ChatRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Chat upsert(String canonicalChatId, String chatIdRaw, Integer topicId,
                       String chatTitle, Instant seenAt) {
        String canonical = (canonicalChatId != null && !canonicalChatId.isBlank())
                ? canonicalChatId
                : chatIdRaw;
        String raw = (chatIdRaw != null && !chatIdRaw.isBlank()) ? chatIdRaw : canonical;
        Instant ts = seenAt != null ? seenAt : Instant.now();

        final String fCanonical = canonical;
        final String fRaw = raw;
        Chat chat = repository.findByCanonicalChatIdAndTopicId(canonical, topicId)
                .orElseGet(() -> Chat.builder()
                        .canonicalChatId(fCanonical)
                        .chatIdRaw(fRaw)
                        .topicId(topicId)
                        .firstSeen(ts)
                        .lastSeen(ts)
                        .build());
        if (chatTitle != null && !chatTitle.isBlank()) {
            chat.setChatTitle(chatTitle);
        }
        if (chat.getLastSeen() == null || ts.isAfter(chat.getLastSeen())) {
            chat.setLastSeen(ts);
        }
        return repository.save(chat);
    }
}
