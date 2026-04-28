package com.tcleaner.dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@Order(1)
public class DashboardSecurityConfig {

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain dashboardFilterChain(HttpSecurity http,
                                                     DashboardAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
            .securityMatcher("/dashboard/**")
            .headers(headers -> headers
                // X-Frame-Options отключён намеренно: он не поддерживает allowlist — только
                // DENY или SAMEORIGIN. Framing-политика задаётся через CSP frame-ancestors ниже,
                // что позволяет разрешить web.telegram.org и 'self' одновременно.
                // Для браузеров без CSP (IE 11) clickjacking защита отсутствует — приемлемо
                // для Telegram Mini App (целевая аудитория использует современные браузеры).
                .frameOptions(frame -> frame.disable())
                // no-store для HTML дашборда — см. DashboardAccessIsolationIntegrationTest#htmlPagesAreNoStore
                .cacheControl(Customizer.withDefaults())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self' https://telegram.org; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: https://t.me; " +
                    "frame-ancestors 'self' https://web.telegram.org"))
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/dashboard/login", "/dashboard/login/telegram",
                    "/dashboard/mini-app",
                    "/dashboard/assets/**", "/dashboard/css/**",
                    "/dashboard/js/**", "/dashboard/vendor/**",
                    "/dashboard/img/**").permitAll()
                // Admin-only: глобальные страницы и API (USER → silent redirect на /me)
                .requestMatchers(
                    "/dashboard/overview",
                    "/dashboard/users", "/dashboard/user/**",
                    "/dashboard/chats", "/dashboard/events",
                    "/dashboard/api/stats/users",
                    "/dashboard/api/stats/chats",
                    "/dashboard/api/stats/timeseries",
                    "/dashboard/api/stats/status-breakdown",
                    "/dashboard/api/admin/**").hasRole("ADMIN")
                // USER может видеть свои данные; IDOR-контроль — в BotUserAccessPolicy, не здесь
                .requestMatchers(
                    "/dashboard/api/stats/overview",
                    "/dashboard/api/stats/user/**",
                    "/dashboard/api/stats/recent").authenticated()
                // Подписки: RBAC внутри SubscriptionController через BotUserAccessPolicy
                .requestMatchers(
                    "/dashboard/api/subscriptions",
                    "/dashboard/api/subscriptions/**").authenticated()
                // Страница подписок: доступна любому авторизованному (USER + ADMIN)
                .requestMatchers("/dashboard/subscriptions").authenticated()
                // Личный кабинет доступен любому авторизованному
                .requestMatchers(
                    "/dashboard/me", "/dashboard/me/**",
                    "/dashboard/api/me", "/dashboard/api/me/**").authenticated()
                // "О проекте" + feedback endpoint — любому аутентифицированному.
                // POST-логика сама блокирует ADMIN/unbound.
                .requestMatchers("/dashboard/about").authenticated()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/dashboard/login"))
                .accessDeniedHandler(accessDeniedHandler))
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/dashboard/login/telegram"))
            .logout(logout -> logout
                .logoutUrl("/dashboard/logout")
                .logoutSuccessUrl("/dashboard/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "tg_uid")
                .permitAll()
            );

        return http.build();
    }
}
