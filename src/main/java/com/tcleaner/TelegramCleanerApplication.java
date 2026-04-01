package com.tcleaner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Точка входа приложения.
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} исключён,
 * чтобы Spring не генерировал случайный пароль и не печатал его в лог.</p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class TelegramCleanerApplication {

    /**
     * Точка входа приложения.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        SpringApplication.run(TelegramCleanerApplication.class, args);
    }
}
