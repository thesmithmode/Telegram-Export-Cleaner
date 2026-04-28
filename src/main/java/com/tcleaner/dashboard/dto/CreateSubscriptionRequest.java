package com.tcleaner.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

// botUserId отсутствует намеренно — берётся из principal; USER не может создать за другого.
public record CreateSubscriptionRequest(
        @NotBlank @Size(max = 512)
        String chatIdentifier,
        @NotNull Integer periodHours,
        @NotBlank
        @jakarta.validation.constraints.Pattern(
                regexp = "^([01]\\d|2[0-3]):[0-5]\\d$",
                message = "desiredTimeMsk must be HH:MM (00:00-23:59)")
        String desiredTimeMsk,
        Instant sinceDate
) {}
