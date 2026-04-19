package com.tcleaner;

import com.tcleaner.dashboard.auth.telegram.TelegramMiniAppAuthVerifier;
import com.tcleaner.dashboard.events.StatsStreamProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(StatsStreamProperties.class)
public class TelegramCleanerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelegramCleanerApplication.class, args);
    }

    @Bean
    @ConditionalOnMissingBean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    TelegramMiniAppAuthVerifier telegramMiniAppAuthVerifier(
            @Value("${telegram.bot.token}") String botToken, Clock clock) {
        return new TelegramMiniAppAuthVerifier(botToken, clock);
    }
}
