package com.tcleaner.dashboard.auth.telegram;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Проверяет подлинность данных Telegram Login Widget.
 * Алгоритм (core.telegram.org/widgets/login):
 *   secret_key = SHA256(bot_token)
 *   hash = HMAC-SHA256(data_check_string, secret_key)
 * Плюс проверка auth_date (не старше {@link #MAX_AGE}).
 */
public class TelegramAuthVerifier {

    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final byte[] secretKey;
    private final Clock clock;

    public TelegramAuthVerifier(String botToken, Clock clock) {
        try {
            this.secretKey = MessageDigest.getInstance("SHA-256")
                    .digest(botToken.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        this.clock = clock;
    }

    public void verify(TelegramLoginData data) {
        if (data.hash() == null || data.hash().isBlank()) {
            throw new TelegramAuthenticationException("Отсутствует hash");
        }
        String expected = computeHash(data.toDataCheckString());
        if (!constantTimeEquals(expected, data.hash())) {
            throw new TelegramAuthenticationException("Невалидный hash — данные подделаны");
        }
        long now = clock.instant().getEpochSecond();
        long age = now - data.authDate();
        if (age > MAX_AGE.toSeconds() || age < -MAX_AGE.toSeconds()) {
            throw new TelegramAuthenticationException("Протухший auth_date (age=" + age + "s)");
        }
    }

    private String computeHash(String dataCheckString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] raw = mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
