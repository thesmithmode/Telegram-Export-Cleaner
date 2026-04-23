package com.tcleaner.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Тело запроса для создания подписки ({@code POST /dashboard/api/subscriptions}).
 *
 * <p>Поле {@code botUserId} намеренно отсутствует: идентификатор пользователя
 * берётся из {@code principal} на стороне контроллера — USER не может создать
 * подписку за другого, а ADMIN не может создавать подписки вообще.
 *
 * <p>{@code sinceDate} опционально: если не передано, контроллер подставляет {@code Instant.now()}.
 */
public record CreateSubscriptionRequest(
        @NotNull Long chatRefId,
        @NotNull Integer periodHours,
        @NotBlank
        @jakarta.validation.constraints.Pattern(
                regexp = "^([01]\\d|2[0-3]):[0-5]\\d$",
                message = "desiredTimeMsk must be HH:MM (00:00-23:59)")
        String desiredTimeMsk,
        Instant sinceDate
) {}
