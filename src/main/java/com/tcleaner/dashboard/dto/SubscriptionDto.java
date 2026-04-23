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
        Integer periodHours,
        String desiredTimeMsk,
        Instant sinceDate,
        String status,
        Instant lastRunAt,
        Instant lastSuccessAt,
        Integer consecutiveFailures,
        Instant lastConfirmAt,
        Instant confirmSentAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static SubscriptionDto fromEntity(ChatSubscription s) {
        return new SubscriptionDto(
                s.getId(),
                s.getBotUserId(),
                s.getChatRefId(),
                s.getPeriodHours(),
                s.getDesiredTimeMsk(),
                s.getSinceDate(),
                s.getStatus().name(),
                s.getLastRunAt(),
                s.getLastSuccessAt(),
                s.getConsecutiveFailures(),
                s.getLastConfirmAt(),
                s.getConfirmSentAt(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
