package com.tcleaner;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security chain для Spring Boot Actuator endpoint'ов.
 * {@code @Order(HIGHEST_PRECEDENCE)} — обрабатывается раньше DashboardSecurityConfig (/dashboard/**)
 * и SecurityConfig (/api/**), чтобы /actuator/** не цеплялся за их матчеры.
 *
 * <p>Публикуем только /actuator/health (+ liveness/readiness для k8s-style probe).
 * Остальное — denyAll. Traefik наружу не пускает /actuator/**, но defense-in-depth.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActuatorSecurityConfig {

    @Bean
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
