package com.tcleaner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class TelegramCleanerApplication {

    /**
     * Точка входа приложения.
     *
     * <p>Если передан аргумент --cli, запускается в режиме командной строки
     * без старта веб-сервера. Иначе стартует Spring Boot с REST API.</p>
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
