package com.tcleaner.dashboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * Таргет экспорта — чат, канал, группа или forum-топик.
 * {@code canonicalChatId} — resolved id из Pyrogram (или raw input при сбое резолва);
 * {@code topicId} заполняется только для forum-топиков.
 * UNIQUE {@code (canonical_chat_id, COALESCE(topic_id, -1))} — ключ дедупликации.
 */
@Entity
@Table(name = "chats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "canonical_chat_id", nullable = false)
    private String canonicalChatId;

    @Column(name = "chat_id_raw", nullable = false)
    private String chatIdRaw;

    @Column(name = "topic_id")
    private Integer topicId;

    @Column(name = "chat_title")
    private String chatTitle;

    @Column(name = "chat_type")
    private String chatType;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;
}
