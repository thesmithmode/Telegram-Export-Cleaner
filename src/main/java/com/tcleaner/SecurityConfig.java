package com.tcleaner;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Конфигурация безопасности приложения.
 *
 * <p>Все endpoints публичные — аутентификация не требуется.
 * UserDetailsServiceAutoConfiguration исключён в {@link TelegramCleanerApplication},
 * чтобы Spring Boot не генерировал случайный пароль при старте.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Фильтр цепочки безопасности для HTTP запросов.
     *
     * <p>CSRF отключён (stateless REST API).
     * Все запросы разрешены без аутентификации.</p>
     *
     * @param http конфигурация HTTP безопасности
     * @return настроенный SecurityFilterChain
     * @throws Exception при ошибке конфигурации
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll());

        return http.build();
    }
}
