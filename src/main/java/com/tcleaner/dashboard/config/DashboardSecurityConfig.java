package com.tcleaner.dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * Spring Security для дашборда: stateful сессии, вход через Telegram Login Widget.
 * {@code @Order(1)} — обрабатывается раньше API-цепочки (см. {@code SecurityConfig}).
 * FormLogin/BCrypt удалены — аутентификация через /dashboard/login/telegram
 * (см. TelegramAuthController).
 */
@Configuration
@Order(1)
public class DashboardSecurityConfig {

    @Bean
    public SecurityFilterChain dashboardFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/dashboard/**")
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self' https://telegram.org; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: https://t.me; " +
                    "frame-src https://oauth.telegram.org"))
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/dashboard/login", "/dashboard/login/telegram",
                    "/dashboard/assets/**", "/dashboard/css/**",
                    "/dashboard/js/**", "/dashboard/vendor/**").permitAll()
                .requestMatchers("/dashboard/users", "/dashboard/api/stats/users").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/dashboard/login")))
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
