package com.tcleaner.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 2000: запас до TG-лимита 4096 под заголовок с метаданными.
public record FeedbackRequest(
        @NotBlank
        @Size(max = 2000, message = "message must be at most 2000 characters")
        String message
) {
}
