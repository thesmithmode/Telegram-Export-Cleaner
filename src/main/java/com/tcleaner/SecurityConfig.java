package com.tcleaner;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Цепочка безопасности для REST API ({@code /api/**}): STATELESS, CSRF off.
 * {@code @Order(2)} — применяется после дашборд-цепочки (см. {@code DashboardSecurityConfig}).
 */
@Configuration
@EnableWebSecurity
@Order(2)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable()) // Stateless API
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny()) // Clickjacking protection
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'")) // CSP
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll());

        return http.build();
    }
}
