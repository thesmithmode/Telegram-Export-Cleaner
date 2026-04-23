package com.tcleaner.dashboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.time.LocalDate;

/**
 * Единичное событие экспорта — single source of truth статистики.
 * {@code taskId} — идемпотентный ключ из {@code ExportJobProducer}; UNIQUE-индекс
 * страхует от дублей при late-arriving completed-событиях и повторных публикациях
 * из Redis Stream (см. docs/DASHBOARD.md).
 */
@Entity
@Table(name = "export_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ExportEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "task_id", nullable = false, unique = true)
    private String taskId;

    @Column(name = "bot_user_id", nullable = false)
    private Long botUserId;

    @Column(name = "chat_ref_id", nullable = false)
    private Long chatRefId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExportStatus status;

    @Column(name = "messages_count")
    private Long messagesCount;

    @Column(name = "bytes_count")
    private Long bytesCount;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "keywords")
    private String keywords;

    @Column(name = "exclude_keywords")
    private String excludeKeywords;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private ExportSource source;

    @Column(name = "error_message")
    private String errorMessage;

    /**
     * Ссылка на {@link ChatSubscription} если экспорт инициирован подпиской,
     * иначе NULL (ручной экспорт). Для аудита итераций подписки.
     */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
