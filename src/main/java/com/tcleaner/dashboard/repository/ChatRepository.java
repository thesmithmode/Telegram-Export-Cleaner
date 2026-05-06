package com.tcleaner.dashboard.repository;

import com.tcleaner.dashboard.domain.Chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * JPA-репозиторий для {@link Chat}.
 * {@link #findByCanonicalChatIdAndTopicId} — поиск по составному ключу,
 * совместимый с UNIQUE-индексом {@code (canonical_chat_id, COALESCE(topic_id, -1))}.
 * Ingestion вызывает его перед вставкой, чтобы не плодить дубликаты.
 */
@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {

    @Query("""
            SELECT c FROM Chat c
            WHERE c.canonicalChatId = :canonicalChatId
              AND ((:topicId IS NULL AND c.topicId IS NULL)
                   OR c.topicId = :topicId)
            """)
    Optional<Chat> findByCanonicalChatIdAndTopicId(
            @Param("canonicalChatId") String canonicalChatId,
            @Param("topicId") Integer topicId);

    List<Chat> findAllByCanonicalChatIdIn(Collection<String> canonicalChatIds);
}
