package com.tcleaner.dashboard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardApiCacheHeadersFilterTest {

    private DashboardApiCacheHeadersFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new DashboardApiCacheHeadersFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    @Test
    void dashboardApiRequestGetsNoStoreHeaders() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/dashboard/api/me");

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader("Cache-Control", "private, no-store, no-cache, must-revalidate, max-age=0");
        verify(response).setHeader("Pragma", "no-cache");
        verify(response).setHeader("Expires", "0");
        verify(chain).doFilter(request, response);
    }

    @Test
    void meStatsRequestGetsNoStoreHeaders() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/dashboard/api/me/overview");

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader("Cache-Control", "private, no-store, no-cache, must-revalidate, max-age=0");
    }

    @Test
    void statsRequestGetsNoStoreHeaders() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/dashboard/api/stats/overview");

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader("Cache-Control", "private, no-store, no-cache, must-revalidate, max-age=0");
    }

    @Test
    void nonDashboardApiBypassesHeaders() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/dashboard/overview");

        filter.doFilterInternal(request, response, chain);

        verify(response, never()).setHeader(anyString(), anyString());
        verify(chain).doFilter(request, response);
    }

    @Test
    void staticAssetsBypassHeaders() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/dashboard/js/app.js");

        filter.doFilterInternal(request, response, chain);

        verify(response, never()).setHeader(anyString(), anyString());
        verify(chain).doFilter(request, response);
    }

    @Test
    void apiInternalBypassHeaders() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/convert");

        filter.doFilterInternal(request, response, chain);

        verify(response, never()).setHeader(anyString(), anyString());
        verify(chain).doFilter(request, response);
    }
}
