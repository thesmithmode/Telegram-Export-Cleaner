package com.tcleaner;

import com.tcleaner.dashboard.events.StatsStreamProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(StatsStreamProperties.class)
public class TelegramCleanerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelegramCleanerApplication.class, args);
    }
}
