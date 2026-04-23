package com.tcleaner.dashboard.dto;

import com.tcleaner.dashboard.domain.ChatSubscription;

import java.time.Instant;

/**
 * DTO подписки для REST API дашборда.
 * Все поля берутся из {@link ChatSubscription} без изменений;
 * статус представлен строкой (имя {@code SubscriptionStatus}).
 */
public record SubscriptionDto(
        Long id,
        Long botUserId,
        Long chatRefId,
        String chatDisplay,
        Integer periodHours,
        String desiredTimeMsk,
        Instant sinceDate,
        String status,
        Instant lastRunAt,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        Integer consecutiveFailures,
        Instant lastConfirmAt,
        Instant confirmSentAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static SubscriptionDto fromEntity(ChatSubscription s) {
        return fromEntity(s, null);
    }

    public static SubscriptionDto fromEntity(ChatSubscription s, String chatDisplay) {
        return new SubscriptionDto(
                s.getId(),
                s.getBotUserId(),
                s.getChatRefId(),
                chatDisplay,
                s.getPeriodHours(),
                s.getDesiredTimeMsk(),
                s.getSinceDate(),
                s.getStatus().name(),
                s.getLastRunAt(),
                s.getLastSuccessAt(),
                s.getLastFailureAt(),
                s.getConsecutiveFailures(),
                s.getLastConfirmAt(),
                s.getConfirmSentAt(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
