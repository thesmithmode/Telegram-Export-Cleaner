package com.tcleaner.dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * Spring Security для дашборда: stateful сессии, вход через Telegram Mini App.
 * {@code @Order(1)} — обрабатывается раньше API-цепочки (см. {@code SecurityConfig}).
 * Аутентификация через POST /dashboard/login/telegram с initData Mini App
 * (см. TelegramAuthController).
 */
@Configuration
@Order(1)
public class DashboardSecurityConfig {

    @Bean
    public SecurityFilterChain dashboardFilterChain(HttpSecurity http,
                                                     DashboardAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
            .securityMatcher("/dashboard/**")
            .headers(headers -> headers
                .frameOptions(frame -> frame.disable())
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
                    "/dashboard/api/stats/status-breakdown").hasRole("ADMIN")
                // USER может видеть свои данные; IDOR-контроль — в BotUserAccessPolicy, не здесь
                .requestMatchers(
                    "/dashboard/api/stats/overview",
                    "/dashboard/api/stats/user/**",
                    "/dashboard/api/stats/recent").authenticated()
                // Личный кабинет доступен любому авторизованному
                .requestMatchers(
                    "/dashboard/me", "/dashboard/me/**",
                    "/dashboard/api/me", "/dashboard/api/me/**").authenticated()
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
                .deleteCookies("JSESSIONID")
                .permitAll()
            );

        return http.build();
    }
}
