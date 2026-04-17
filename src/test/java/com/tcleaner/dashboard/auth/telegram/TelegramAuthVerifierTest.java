package com.tcleaner.dashboard.auth.telegram;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramAuthVerifierTest {

    private static final String BOT_TOKEN = "123456:TEST_BOT_TOKEN";

    private static String computeHash(String dataCheckString) throws Exception {
        byte[] secret = MessageDigest.getInstance("SHA-256")
                .digest(BOT_TOKEN.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));
    }

    private TelegramLoginData validData(long authDate) throws Exception {
        TelegramLoginData base = new TelegramLoginData(
                111L, "John", null, "johnny", null, authDate, "placeholder");
        String hash = computeHash(base.toDataCheckString());
        return new TelegramLoginData(111L, "John", null, "johnny", null, authDate, hash);
    }

    @Test
    void validHashAndFreshAuthDatePasses() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);
        TelegramAuthVerifier verifier = new TelegramAuthVerifier(BOT_TOKEN, clock);

        assertThatCode(() -> verifier.verify(validData(1_000_000L))).doesNotThrowAnyException();
    }

    @Test
    void tamperedDataHashThrows() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);
        TelegramAuthVerifier verifier = new TelegramAuthVerifier(BOT_TOKEN, clock);
        TelegramLoginData original = validData(1_000_000L);
        TelegramLoginData tampered = new TelegramLoginData(
                original.id(), "HACKED", original.lastName(),
                original.username(), original.photoUrl(),
                original.authDate(), original.hash());

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(TelegramAuthenticationException.class)
                .hasMessageContaining("hash");
    }

    @Test
    void expiredAuthDateThrows() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_001_000L), ZoneOffset.UTC);
        TelegramAuthVerifier verifier = new TelegramAuthVerifier(BOT_TOKEN, clock);

        assertThatThrownBy(() -> verifier.verify(validData(1_000_000L)))
                .isInstanceOf(TelegramAuthenticationException.class)
                .hasMessageContaining("auth_date");
    }

    @Test
    void futureAuthDateThrows() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);
        TelegramAuthVerifier verifier = new TelegramAuthVerifier(BOT_TOKEN, clock);

        assertThatThrownBy(() -> verifier.verify(validData(1_000_700L)))
                .isInstanceOf(TelegramAuthenticationException.class)
                .hasMessageContaining("auth_date");
    }

    @Test
    void missingHashThrows() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);
        TelegramAuthVerifier verifier = new TelegramAuthVerifier(BOT_TOKEN, clock);
        TelegramLoginData data = new TelegramLoginData(111L, "John", null, null, null, 1_000_000L, "");

        assertThatThrownBy(() -> verifier.verify(data))
                .isInstanceOf(TelegramAuthenticationException.class);
    }
}
