package com.tcleaner.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ApiKeyFilterTest {

    private ApiKeyFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;
    private StringWriter responseContent;

    // Logback ListAppender для проверки содержимого лог-сообщений
    private ListAppender<ILoggingEvent> logAppender;
    private Logger filterLogger;

    @BeforeEach
    void setUp() throws IOException {
        filter = new ApiKeyFilter("secret-key");
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        responseContent = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseContent));

        // Подключаемся к логгеру фильтра, чтобы перехватывать записи
        filterLogger = (Logger) LoggerFactory.getLogger(ApiKeyFilter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        filterLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (filterLogger != null && logAppender != null) {
            filterLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @Test
    void testValidApiKey() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("X-API-Key")).thenReturn("secret-key");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void testInvalidApiKey() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("X-API-Key")).thenReturn("wrong-key");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void testMissingApiKey() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void testHealthEndpointBypassesFilter() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/health");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void testNonApiEndpointBypassesFilter() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/index.html");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void testEmptyKeyFailsFast() {
        // SECURITY: fail-open при пустом ключе → бан. Конструктор должен бросить.
        assertThatThrownBy(() -> new ApiKeyFilter(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JAVA_API_KEY");
        assertThatThrownBy(() -> new ApiKeyFilter(null))
                .isInstanceOf(IllegalStateException.class);
    }

    // ================================================================
    // Regression tests — защищают security-фиксы C3 и C4 от отката.
    // Смотри также UrlValidatorTest.BlocksProtocolRelative (C5).
    // ================================================================

    @Test
    void invalidKeyIsNeverLogged() throws ServletException, IOException {
        // REGRESSION: до фикса C3 лог содержал полный неверный ключ
        // log.warn("..., provided={}", ..., providedKey)
        // Это попадало в SIEM / ELK и было bounty-мишенью.
        String leakedKey = "super-secret-attacker-guess-abc123xyz";
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("X-API-Key")).thenReturn(leakedKey);

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // Лог-сообщение НЕ должно содержать сам ключ ни в одном из полей.
        List<ILoggingEvent> logs = logAppender.list;
        assertThat(logs).isNotEmpty();
        for (ILoggingEvent event : logs) {
            assertThat(event.getFormattedMessage())
                    .as("Лог-сообщение не должно содержать неверный ключ")
                    .doesNotContain(leakedKey);
            for (Object arg : event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray()) {
                assertThat(String.valueOf(arg))
                        .as("Аргумент лога не должен содержать неверный ключ")
                        .doesNotContain(leakedKey);
            }
        }
    }

    @Test
    void failedAttemptLogsPathAndHeaderPresenceFlag() throws ServletException, IOException {
        // Логируем именно факт попытки и флаг, был ли header — этого достаточно
        // для мониторинга без утечки payload.
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("X-API-Key")).thenReturn("wrong");

        filter.doFilterInternal(request, response, filterChain);

        List<ILoggingEvent> warns = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns)
                .as("должен быть warn о неверной попытке")
                .isNotEmpty();
        ILoggingEvent warn = warns.get(0);
        assertThat(warn.getFormattedMessage()).contains("/api/convert");
        assertThat(warn.getFormattedMessage()).contains("header_present=true");
    }

    @Test
    void missingHeaderLogsHeaderPresentFalse() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        List<ILoggingEvent> warns = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).isNotEmpty();
        assertThat(warns.get(0).getFormattedMessage()).contains("header_present=false");
    }

    @Test
    void nullHeaderDoesNotThrow() throws ServletException, IOException {
        // REGRESSION: при переходе на MessageDigest.isEqual надо проверить,
        // что null-header не даёт NPE до явной проверки.
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        // Не должно бросать
        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void keyOfDifferentLengthStillRejected() throws ServletException, IOException {
        // REGRESSION: MessageDigest.isEqual безопасен для массивов разной длины
        // (возвращает false без throw). Проверяем явно.
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("X-API-Key")).thenReturn("x"); // 1 байт vs 10 байт

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void keyWithCaseDifferenceIsRejected() throws ServletException, IOException {
        // API-ключи case-sensitive — никакого нормализации.
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(request.getHeader("X-API-Key")).thenReturn("SECRET-KEY"); // != "secret-key"

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
