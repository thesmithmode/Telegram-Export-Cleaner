package com.tcleaner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Конфигурация безопасности приложения.
 * Включает базовую аутентификацию для защиты API endpoints.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Фильтр цепочки безопасности для HTTP запросов.
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
                // Публичные endpoints
                .requestMatchers("/api/health").permitAll()
                // Требуют аутентификации
                .requestMatchers("/api/files/**").authenticated()
                .requestMatchers("/api/convert/**").authenticated()
                .anyRequest().authenticated())
            .httpBasic(basic -> {});

        return http.build();
    }

    /**
     * Кодировщик паролей.
     *
     * @return BCryptPasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Пользовательский сервис для аутентификации.
     * Читает учётные данные из переменных окружения APP_ADMIN_USER и APP_ADMIN_PASSWORD.
     * В production обязательно установить переменные окружения.
     * Для development используются значения по умолчанию: admin/password
     *
     * @return InMemoryUserDetailsManager с пользователем admin
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        String adminUser = System.getenv("APP_ADMIN_USER");
        String adminPass = System.getenv("APP_ADMIN_PASSWORD");
        
        // Используем значения по умолчанию для development
        if (adminUser == null || adminUser.isBlank()) {
            adminUser = "admin";
            log.warn("⚠️  APP_ADMIN_USER не задан — используется дефолтное значение 'admin'. Не используйте в production!");
        }
        if (adminPass == null || adminPass.isBlank()) {
            adminPass = "password";
            log.warn("⚠️  APP_ADMIN_PASSWORD не задан — используется дефолтный пароль 'password'. Не используйте в production!");
        }
        
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        manager.createUser(
            User.builder()
                .username(adminUser)
                .password(passwordEncoder.encode(adminPass))
                .roles("USER")
                .build()
        );
        return manager;
    }
}
