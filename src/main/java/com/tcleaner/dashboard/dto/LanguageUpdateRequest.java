package com.tcleaner.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Запрос на смену языка UI. Код ограничен ISO 639-1 (опционально с региональным
 * суффиксом, напр. {@code pt-BR}); семантическую проверку делает
 * {@link com.tcleaner.core.BotLanguage#fromCode(String)}.
 */
public record LanguageUpdateRequest(
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z]{2}(-[a-zA-Z]{2})?$",
                message = "language must be a language tag like 'en' or 'pt-BR'")
        String language
) {
}
