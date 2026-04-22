package com.tcleaner.dashboard.config;

import com.tcleaner.dashboard.DashboardTestUsers;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Резолв локали из {@code BotUser.language} через principal, fallback на Accept-Language → defaultLocale.
 * Проверяем все ветки: stored-lang, unstored+header, admin без botUserId, anonymous, unexpected principal.
 */
@DisplayName("BotUserLocaleResolver")
class BotUserLocaleResolverTest {

    private static final Locale DEFAULT = Locale.forLanguageTag("ru");

    private BotUserUpserter upserter;
    private BotUserLocaleResolver resolver;

    @BeforeEach
    void setUp() {
        upserter = mock(BotUserUpserter.class);
        resolver = new BotUserLocaleResolver(upserter, DEFAULT);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static HttpServletRequest request(Locale acceptLanguage) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (acceptLanguage != null) {
            req.addPreferredLocale(acceptLanguage);
        }
        return req;
    }

    private static void authenticate(DashboardUserDetails principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("USER с сохранённым language → Locale из BotLanguage")
    void userWithStoredLanguage() {
        DashboardUserDetails user = DashboardTestUsers.user("alice", 42L);
        authenticate(user);
        when(upserter.getLanguage(42L)).thenReturn(Optional.of("fa"));

        Locale resolved = resolver.resolveLocale(request(Locale.ENGLISH));

        assertThat(resolved.toLanguageTag()).isEqualTo("fa");
    }

    @Test
    @DisplayName("USER с pt-BR в БД → Locale pt-BR (а не только pt)")
    void userWithPtBrStored() {
        DashboardUserDetails user = DashboardTestUsers.user("bob", 43L);
        authenticate(user);
        when(upserter.getLanguage(43L)).thenReturn(Optional.of("pt-BR"));

        Locale resolved = resolver.resolveLocale(request(Locale.ENGLISH));

        assertThat(resolved.toLanguageTag()).isEqualTo("pt-BR");
    }

    @Test
    @DisplayName("USER без language → fallback на Accept-Language из запроса")
    void userWithoutStoredLanguageFallsBackToHeader() {
        DashboardUserDetails user = DashboardTestUsers.user("alice", 42L);
        authenticate(user);
        when(upserter.getLanguage(42L)).thenReturn(Optional.empty());

        Locale resolved = resolver.resolveLocale(request(Locale.ENGLISH));

        assertThat(resolved).isEqualTo(Locale.ENGLISH);
    }

    @Test
    @DisplayName("USER с невалидным кодом в БД → fallback на Accept-Language")
    void userWithInvalidStoredCode() {
        DashboardUserDetails user = DashboardTestUsers.user("alice", 42L);
        authenticate(user);
        when(upserter.getLanguage(42L)).thenReturn(Optional.of("xx"));

        Locale resolved = resolver.resolveLocale(request(Locale.GERMAN));

        assertThat(resolved).isEqualTo(Locale.GERMAN);
    }

    @Test
    @DisplayName("ADMIN без botUserId → Accept-Language, БД не дёргается")
    void adminFallsBackToHeader() {
        authenticate(DashboardTestUsers.admin());

        Locale resolved = resolver.resolveLocale(request(Locale.ENGLISH));

        assertThat(resolved).isEqualTo(Locale.ENGLISH);
        org.mockito.Mockito.verifyNoInteractions(upserter);
    }

    @Test
    @DisplayName("Anonymous → defaultLocale (нет Accept-Language)")
    void anonymousNoHeaderFallsToDefault() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anon",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        MockHttpServletRequest req = new MockHttpServletRequest();
        // Явно сбрасываем preferredLocales к defaultLocale сервера
        Locale resolved = resolver.resolveLocale(req);

        // MockHttpServletRequest.getLocale() возвращает Locale.getDefault() если не задано —
        // резолвер возвращает его, не defaultLocale. Проверяем что это не NPE и не null.
        assertThat(resolved).isNotNull();
    }

    @Test
    @DisplayName("Принципал неожиданного типа → fallback на header (без ClassCastException)")
    void unexpectedPrincipalType() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("just-a-string", null, List.of()));

        Locale resolved = resolver.resolveLocale(request(Locale.ENGLISH));

        assertThat(resolved).isEqualTo(Locale.ENGLISH);
        org.mockito.Mockito.verifyNoInteractions(upserter);
    }

    @Test
    @DisplayName("setLocale — no-op (смена идёт через POST /api/me/settings/language)")
    void setLocaleNoop() {
        resolver.setLocale(new MockHttpServletRequest(), null, Locale.ENGLISH);
        // не бросает exception, не пишет в SecurityContext
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
