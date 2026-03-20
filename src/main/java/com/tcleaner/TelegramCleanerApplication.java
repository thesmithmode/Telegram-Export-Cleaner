package com.tcleaner;

import com.tcleaner.cli.Main;
import com.tcleaner.storage.StorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

/**
 * Точка входа приложения.
 *
 * <p>Поддерживает два режима:</p>
 * <ul>
 *   <li>Web API (по умолчанию) — Spring Boot с REST API</li>
 *   <li>CLI — аргумент {@code --cli} запускает {@link Main} без веб-сервера</li>
 * </ul>
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} исключён,
 * чтобы Spring не генерировал случайный пароль и не печатал его в лог.</p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(StorageConfig.class)
public class TelegramCleanerApplication {

    /**
     * Точка входа приложения.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        boolean isCli = Arrays.stream(args).anyMatch("--cli"::equals);

        if (isCli) {
            String[] cliArgs = Arrays.stream(args)
                    .filter(arg -> !"--cli".equals(arg))
                    .toArray(String[]::new);
            Main.main(cliArgs);
        } else {
            SpringApplication.run(TelegramCleanerApplication.class, args);
        }
    }
}
