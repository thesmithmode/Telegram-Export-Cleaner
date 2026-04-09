package com.tcleaner.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.Mockito.*;

class ApiKeyFilterTest {

    private ApiKeyFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;
    private StringWriter responseContent;

    @BeforeEach
    void setUp() throws IOException {
        filter = new ApiKeyFilter("secret-key");
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        responseContent = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseContent));
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
    void testNoKeyConfiguredBlocksAll() throws ServletException, IOException {
        filter = new ApiKeyFilter("");
        when(request.getRequestURI()).thenReturn("/api/convert");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
