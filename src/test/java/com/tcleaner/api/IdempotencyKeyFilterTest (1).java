package com.tcleaner.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyKeyFilterTest {

    private IdempotencyKeyFilter filter;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseContent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        filter = new IdempotencyKeyFilter(redis);

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        responseContent = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseContent));
    }

    @Test
    void getRequestBypassesFilter() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/health");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(redis, never()).opsForValue();
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void headRequestBypassesFilter() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("HEAD");
        when(request.getRequestURI()).thenReturn("/dashboard/api/me");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(redis, never()).opsForValue();
    }

    @Test
    void postWithoutIdempotencyKeyPasses() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("Idempotency-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(redis, never()).opsForValue();
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void postWithBlankIdempotencyKeyPasses() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("Idempotency-Key")).thenReturn("   ");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(redis, never()).opsForValue();
    }

    @Test
    void postWithValidKeyFirstTimePasses() throws ServletException, IOException {
        String key = "abc123def456-789012345_XYZ";
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("Idempotency-Key")).thenReturn(key);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(ops).setIfAbsent(eq("idempotency:/api/convert:" + key), anyString(), eq(Duration.ofHours(24)));
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void postWithDuplicateKeyReturns409() throws ServletException, IOException {
        String key = "duplicate-key-aaaabbbbccccdddd";
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("Idempotency-Key")).thenReturn(key);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(HttpServletResponse.SC_CONFLICT);
        verify(response).setContentType("application/json");
        assertThat(responseContent.toString()).contains("duplicate_request");
    }

    @Test
    void invalidKeyFormatTooShortReturns400() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("Idempotency-Key")).thenReturn("short");

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(redis, never()).opsForValue();
    }

    @Test
    void invalidKeyFormatTooLongReturns400() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("Idempotency-Key")).thenReturn("a".repeat(200));

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    void invalidKeyFormatBadCharsReturns400() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("Idempotency-Key")).thenReturn("abc\ndef ghi; drop table; <script>");

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(redis, never()).opsForValue();
    }

    @Test
    void putRequestWithKeyIsChecked() throws ServletException, IOException {
        String key = "put-key-valid-1234567890abcd";
        when(request.getMethod()).thenReturn("PUT");
        when(request.getRequestURI()).thenReturn("/dashboard/api/me/settings");
        when(request.getHeader("Idempotency-Key")).thenReturn(key);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        verify(ops).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(chain).doFilter(request, response);
    }

    @Test
    void deleteRequestWithKeyIsChecked() throws ServletException, IOException {
        String key = "delete-valid-1234567890abcdef";
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRequestURI()).thenReturn("/dashboard/api/me/resource");
        when(request.getHeader("Idempotency-Key")).thenReturn(key);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        verify(ops).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(chain).doFilter(request, response);
    }

    @Test
    void patchRequestWithKeyIsChecked() throws ServletException, IOException {
        String key = "patch-valid-1234567890abcdefg";
        when(request.getMethod()).thenReturn("PATCH");
        when(request.getRequestURI()).thenReturn("/dashboard/api/me");
        when(request.getHeader("Idempotency-Key")).thenReturn(key);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        verify(ops).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(chain).doFilter(request, response);
    }

    @Test
    void redisFailureDoesNotBlockRequest() throws ServletException, IOException {
        String key = "redis-failure-valid-12345678";
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("Idempotency-Key")).thenReturn(key);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void differentUrisWithSameKeyAreIndependent() throws ServletException, IOException {
        String key = "independent-key-valid-1234567";
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("Idempotency-Key")).thenReturn(key);
        when(ops.setIfAbsent(eq("idempotency:/api/convert:" + key), anyString(), any(Duration.class)))
                .thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        verify(ops).setIfAbsent(eq("idempotency:/api/convert:" + key), anyString(), any(Duration.class));
    }
}
