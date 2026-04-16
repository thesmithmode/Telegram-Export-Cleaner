package com.tcleaner.dashboard.config;

import com.tcleaner.dashboard.auth.DashboardUserDetailsService;
import com.tcleaner.dashboard.auth.LoginAttemptService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.DefaultRedirectStrategy;

/**
 * Spring Security для дашборда: stateful сессии, FormLogin, CSRF, RBAC по ролям.
 * {@code @Order(1)} — обрабатывается раньше API-цепочки (см. {@code SecurityConfig}).
 * Статика и страница логина доступны без аутентификации.
 */
@Configuration
@Order(1)
public class DashboardSecurityConfig {

    private final DashboardUserDetailsService userDetailsService;
    private final LoginAttemptService loginAttemptService;

    public DashboardSecurityConfig(DashboardUserDetailsService userDetailsService,
                                   LoginAttemptService loginAttemptService) {
        this.userDetailsService = userDetailsService;
        this.loginAttemptService = loginAttemptService;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider dashboardAuthProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public SecurityFilterChain dashboardFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/dashboard/**")
            .authenticationProvider(dashboardAuthProvider())
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self'; " +
                    "style-src 'self' 'unsafe-inline'; img-src 'self' data:"))
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/dashboard/login", "/dashboard/login/**",
                    "/dashboard/assets/**", "/dashboard/css/**",
                    "/dashboard/js/**", "/dashboard/vendor/**").permitAll()
                .requestMatchers("/dashboard/users", "/dashboard/api/stats/users").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/dashboard/login")
                .loginProcessingUrl("/dashboard/login")
                .failureUrl("/dashboard/login?error")
                .successHandler((request, response, authentication) -> {
                    loginAttemptService.recordSuccess(authentication.getName());
                    new DefaultRedirectStrategy().sendRedirect(
                            request, response,
                            request.getContextPath() + "/dashboard/overview");
                })
                .failureHandler((request, response, exception) -> {
                    String username = request.getParameter("username");
                    if (username != null && !username.isBlank()) {
                        loginAttemptService.recordFailure(username);
                    }
                    new DefaultRedirectStrategy().sendRedirect(
                            request, response,
                            request.getContextPath() + "/dashboard/login?error");
                })
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/dashboard/logout")
                .logoutSuccessUrl("/dashboard/login?logout")
                .deleteCookies("JSESSIONID")
                .permitAll()
            );

        return http.build();
    }
}
