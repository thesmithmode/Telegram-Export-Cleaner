package com.tcleaner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Конфигурация безопасности приложения.
 *
 * <p>Все endpoints публичные — аутентификация не требуется.
 * UserDetailsServiceAutoConfiguration исключён в {@link TelegramCleanerApplication},
 * чтобы Spring Boot не генерировал случайный пароль при старте.</p>
 *
 * <p>CORS конфигурирован для безопасного взаимодействия с фронтенд приложением.
 * По умолчанию разрешает localhost в разработке, требует явного конфига для production.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:8081}")
    private String allowedOriginsStr;

    @Value("${app.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethodsStr;

    @Value("${app.cors.allowed-headers:Content-Type,Authorization}")
    private String allowedHeadersStr;

    @Value("${app.cors.max-age:3600}")
    private long maxAge;

    /**
     * Конфигурирует CORS для публичных endpoints.
     *
     * По умолчанию разрешены origins: http://localhost:3000, http://localhost:8081 (разработка).
     * Credentials НЕ разрешены (stateless REST API).
     *
     * @return CorsConfigurationSource
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> allowedOrigins = Arrays.asList(allowedOriginsStr.split(","));
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(Arrays.asList(allowedMethodsStr.split(",")));
        configuration.setAllowedHeaders(Arrays.asList(allowedHeadersStr.split(",")));

        configuration.setAllowCredentials(false);
        configuration.setMaxAge(maxAge);
        configuration.setExposedHeaders(Arrays.asList("Content-Type", "X-Total-Count"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    /**
     * Фильтр цепочки безопасности для HTTP запросов.
     *
     * <p>Конфигурирует:
     * - CORS (через corsConfigurationSource)
     * - CSRF отключён (stateless REST API)
     * - Все запросы разрешены без аутентификации
     * - Stateless session management</p>
     *
     * @param http конфигурация HTTP безопасности
     * @return настроенный SecurityFilterChain
     * @throws Exception при ошибке конфигурации
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll());

        return http.build();
    }
}
