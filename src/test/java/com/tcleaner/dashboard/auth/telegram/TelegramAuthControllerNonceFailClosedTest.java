package com.tcleaner.dashboard.auth.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TelegramAuthController — fail-closed nonce check")
class TelegramAuthControllerNonceFailClosedTest {

    private static final String TOKEN = "123456:TEST_BOT_TOKEN";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_000_100L), ZoneOffset.UTC);

    @Test
    @DisplayName("Redis-fail на setIfAbsent → редирект на ?error=infra и логин не выполняется")
    void redisFailureOnNonceCheck_failsClosed_andDoesNotInvokeLogin() throws Exception {
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(TOKEN, FIXED_CLOCK);
        TelegramLoginService loginService = mock(TelegramLoginService.class);
        SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), eq("1"), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        TelegramAuthController controller = new TelegramAuthController(
                verifier, loginService, contextRepository, redis, 999L);

        String initData = TelegramAuthTestUtils.buildMiniAppInitData(
                TOKEN, 111L, "John", "johnny", 1_000_000L);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/dashboard/login/telegram");
        request.setRemoteAddr("203.0.113.42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.callback(initData, request, response);

        assertThat(view).isEqualTo("redirect:/dashboard/login?error=infra");
        verify(loginService, never()).loginOrCreate(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(contextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    @DisplayName("успешный nonce-set → логин продолжается (sanity check baseline)")
    void successfulNonceSet_proceedsWithLogin() throws Exception {
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(TOKEN, FIXED_CLOCK);
        TelegramLoginService loginService = mock(TelegramLoginService.class);
        SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), eq("1"), any())).thenReturn(true);
        when(loginService.loginOrCreate(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new RuntimeException("stop here — past nonce check"));

        TelegramAuthController controller = new TelegramAuthController(
                verifier, loginService, contextRepository, redis, 999L);

        String initData = TelegramAuthTestUtils.buildMiniAppInitData(
                TOKEN, 111L, "John", "johnny", 1_000_000L);

        try {
            controller.callback(initData, new MockHttpServletRequest("POST", "/dashboard/login/telegram"),
                    new MockHttpServletResponse());
        } catch (RuntimeException e) {
            assertThat(e).hasMessage("stop here — past nonce check");
        }
        verify(loginService).loginOrCreate(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("повторный nonce (replay detected) → редирект на ?error=invalid")
    void replayDetected_redirectsInvalid() throws Exception {
        TelegramMiniAppAuthVerifier verifier = new TelegramMiniAppAuthVerifier(TOKEN, FIXED_CLOCK);
        TelegramLoginService loginService = mock(TelegramLoginService.class);
        SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), eq("1"), any())).thenReturn(false);

        TelegramAuthController controller = new TelegramAuthController(
                verifier, loginService, contextRepository, redis, 999L);

        String initData = TelegramAuthTestUtils.buildMiniAppInitData(
                TOKEN, 111L, "John", "johnny", 1_000_000L);

        String view = controller.callback(initData,
                new MockHttpServletRequest("POST", "/dashboard/login/telegram"),
                new MockHttpServletResponse());

        assertThat(view).isEqualTo("redirect:/dashboard/login?error=invalid");
        verify(loginService, never()).loginOrCreate(any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
