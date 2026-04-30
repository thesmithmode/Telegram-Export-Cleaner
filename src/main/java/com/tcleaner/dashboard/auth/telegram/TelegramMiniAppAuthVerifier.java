package com.tcleaner.dashboard.auth.telegram;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

public class TelegramMiniAppAuthVerifier {

    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final byte[] secretKey;
    private final Clock clock;

    public TelegramMiniAppAuthVerifier(String botToken, Clock clock) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            this.secretKey = mac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
        this.clock = clock;
    }

    /**
     * Проверка initData по протоколу Telegram Mini App:
     * <ol>
     *   <li>HMAC-SHA256 hash совпадает (защита от подделки полей).</li>
     *   <li>auth_date в окне ±MAX_AGE от текущего времени —
     *       симметричная проверка покрывает и старые initData (replay вне окна)
     *       и подозрительно «будущие» (clock skew атакующего).</li>
     * </ol>
     * Replay в пределах окна закрывается отдельно nonce'ом в {@code TelegramAuthController}.
     */
    public void verify(TelegramMiniAppLoginData data) {
        if (data.hash() == null || data.hash().isBlank()) {
            throw new TelegramAuthenticationException("Отсутствует hash");
        }
        byte[] expected = computeHash(data.toDataCheckString());
        byte[] provided = decodeHexOrNull(data.hash());
        if (provided == null || !MessageDigest.isEqual(expected, provided)) {
            throw new TelegramAuthenticationException("Невалидный hash — данные подделаны");
        }
        long now = clock.instant().getEpochSecond();
        long age = now - data.authDate();
        if (age > MAX_AGE.toSeconds() || age < -MAX_AGE.toSeconds()) {
            throw new TelegramAuthenticationException("Протухший auth_date (age=" + age + "s)");
        }
    }

    private byte[] computeHash(String dataCheckString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            return mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] decodeHexOrNull(String hex) {
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
