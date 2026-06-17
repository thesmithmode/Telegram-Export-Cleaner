package com.tcleaner.bot;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lightweight Java-side preflight for Telegram targets before the export wizard
 * enters date-range selection. It rejects target formats that are known to
 * represent private chats, private invite links, internal chat ids, or personal
 * user deep links without creating an export job.
 */
@Service
public class ChatEligibilityService {

    private static final Pattern NUMERIC_CHAT_ID = Pattern.compile("^-?\\d+$");
    private static final Pattern PRIVATE_INVITE_LINK = Pattern.compile(
            "^https?://t\\.me/(?:\\+|joinchat/).+", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERNAL_PRIVATE_LINK = Pattern.compile(
            "^https?://t\\.me/c/\\d+(?:/\\d+)?(?:\\?.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSONAL_DEEP_LINK = Pattern.compile(
            "^tg://(?:user|resolve)\\?.*", Pattern.CASE_INSENSITIVE);

    public ChatEligibility check(String rawInput, String canonicalIdentifier, Integer topicId) {
        if (rawInput == null || rawInput.isBlank()) {
            return ChatEligibility.invalid();
        }

        String input = rawInput.trim();
        if (isKnownPrivateOrPersonalTarget(input)) {
            return ChatEligibility.ineligible("private_chat_forbidden");
        }
        if (canonicalIdentifier == null) {
            return ChatEligibility.invalid();
        }
        return ChatEligibility.eligible(canonicalIdentifier, "@" + canonicalIdentifier, topicId);
    }

    private boolean isKnownPrivateOrPersonalTarget(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return NUMERIC_CHAT_ID.matcher(input).matches()
                || PRIVATE_INVITE_LINK.matcher(input).matches()
                || INTERNAL_PRIVATE_LINK.matcher(input).matches()
                || PERSONAL_DEEP_LINK.matcher(input).matches()
                || normalized.startsWith("tg://privatepost");
    }

    public record ChatEligibility(
            boolean eligible,
            String errorCode,
            String canonicalIdentifier,
            String displayName,
            Integer topicId
    ) {
        static ChatEligibility eligible(String canonicalIdentifier, String displayName, Integer topicId) {
            return new ChatEligibility(true, null, canonicalIdentifier, displayName, topicId);
        }

        static ChatEligibility ineligible(String errorCode) {
            return new ChatEligibility(false, errorCode, null, null, null);
        }

        static ChatEligibility invalid() {
            return ineligible("invalid_format");
        }
    }
}
