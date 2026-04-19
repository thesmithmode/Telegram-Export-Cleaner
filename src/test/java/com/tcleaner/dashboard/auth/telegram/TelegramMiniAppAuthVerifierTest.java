package com.tcleaner.dashboard.auth.telegram;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramMiniAppAuthVerifierTest {

    private static final String BOT_TOKEN = "123456:TEST_BOT_TOKEN";

    private TelegramMiniAppLoginData validData(long authDate) throws Exception {
        String initData = TelegramAuthTestUtils.buildMiniAppInitData(
                BOT_TOKEN, 111L, "John", "johnny", authDate);
        return TelegramMiniAppLoginData.parse(initData);
    }

    @Test
    void validHashAndFreshAuthDatePasses() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(BOT_TOKEN, clock);

        assertThatCode(() -> verifier.verify(validData(1_000_000L))).doesNotThrowAnyException();
    }

    @Test
    void tamperedDataHashThrows() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(BOT_TOKEN, clock);
        TelegramMiniAppLoginData original = validData(1_000_000L);
        TreeMap<String, String> tamperedParams = new TreeMap<>(original.params());
        tamperedParams.put("first_name", "HACKED");
        String tamperedInitData = tamperedParams.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        TelegramMiniAppLoginData tampered = TelegramMiniAppLoginData.parse(tamperedInitData);

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(TelegramAuthenticationException.class)
                .hasMessageContaining("hash");
    }

    @Test
    void expiredAuthDateThrows() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_001_000L), ZoneOffset.UTC);
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(BOT_TOKEN, clock);

        assertThatThrownBy(() -> verifier.verify(validData(1_000_000L)))
                .isInstanceOf(TelegramAuthenticationException.class)
                .hasMessageContaining("auth_date");
    }

    @Test
    void futureAuthDateThrows() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(BOT_TOKEN, clock);

        assertThatThrownBy(() -> verifier.verify(validData(1_000_700L)))
                .isInstanceOf(TelegramAuthenticationException.class)
                .hasMessageContaining("auth_date");
    }

    @Test
    void missingHashThrows() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(BOT_TOKEN, clock);
        TelegramMiniAppLoginData data = TelegramMiniAppLoginData.parse("auth_date=1000000&id=111");

        assertThatThrownBy(() -> verifier.verify(data))
                .isInstanceOf(TelegramAuthenticationException.class);
    }
}
