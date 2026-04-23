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

/**
 * Подписка пользователя на периодический автоматический экспорт чата.
 *
 * <p><b>Бизнес-логика:</b>
 * <ul>
 *   <li>Alpha-тест: не более одной {@link SubscriptionStatus#ACTIVE} подписки на пользователя.</li>
 *   <li>Периоды строго фиксированы: 24 / 48 / 72 / 168 часов — валидация в сервисном слое.</li>
 *   <li>{@code desiredTimeMsk} — желаемое время запуска по МСК (UTC+3), формат "HH:MM";
 *       планировщик делает поправку на часовой пояс самостоятельно.</li>
 *   <li>Окно экспорта = период: daily-подписка (24 ч) экспортирует последние 24 ч,
 *       weekly (168 ч) — последние 7 дней.</li>
 *   <li>Confirmation: раз в 7 дней бот отправляет confirm-запрос ({@code confirmSentAt});
 *       пользователь должен подтвердить в течение 48 ч, иначе статус → {@link SubscriptionStatus#ARCHIVED}.</li>
 *   <li>Две подряд неудачи ({@code consecutiveFailures} ≥ 2) переводят подписку в
 *       {@link SubscriptionStatus#PAUSED}.</li>
 * </ul>
 *
 * <p>FK-связи хранятся как Long-колонки (без {@code @ManyToOne}):
 * {@code botUserId} → {@code bot_users.bot_user_id},
 * {@code chatRefId} → {@code chats.id}.
 */
@Entity
@Table(name = "chat_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ChatSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "bot_user_id", nullable = false)
    private Long botUserId;

    @Column(name = "chat_ref_id", nullable = false)
    private Long chatRefId;

    /** Период экспорта в часах. Допустимые значения: 24, 48, 72, 168. */
    @Column(name = "period_hours", nullable = false)
    private Integer periodHours;

    /** Желаемое время запуска по МСК (UTC+3), формат "HH:MM". */
    @Column(name = "desired_time_msk", nullable = false)
    private String desiredTimeMsk;

    /** Начало отсчёта периода — с этой точки времени считается первое окно экспорта. */
    @Column(name = "since_date", nullable = false)
    private Instant sinceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubscriptionStatus status;

    /** Момент последнего запуска задачи экспорта (успешного или нет). */
    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** Момент последнего успешно завершённого экспорта. */
    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    /** Момент последней зафиксированной неудачи экспорта. */
    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    /**
     * Счётчик подряд идущих неудач. Сбрасывается в 0 при успехе.
     * При достижении 2 подписка переходит в {@link SubscriptionStatus#PAUSED}.
     */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    /** Момент последнего подтверждения подписки пользователем. */
    @Column(name = "last_confirm_at", nullable = false)
    private Instant lastConfirmAt;

    /** Момент отправки последнего confirm-запроса боту; NULL — запрос ещё не отправлялся. */
    @Column(name = "confirm_sent_at")
    private Instant confirmSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (lastConfirmAt == null) lastConfirmAt = now;
    }

    @jakarta.persistence.PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
