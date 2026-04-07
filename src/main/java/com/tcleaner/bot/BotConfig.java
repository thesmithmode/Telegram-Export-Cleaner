package com.tcleaner.bot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Конфигурация для Telegram бота.
 *
 * <p>Создаёт единый {@code TelegramClient} bean для всех сервисов,
 * вместо того чтобы каждый сервис создавал свой HTTP клиент.</p>
 */
@Configuration
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class BotConfig {

    /**
     * Создаёт единый HTTP клиент для взаимодействия с Telegram Bot API.
     *
     * @param botToken токен бота из конфигурации
     * @return единый {@code TelegramClient} bean
     */
    @Bean
    public TelegramClient telegramClient(@Value("${telegram.bot.token}") String botToken) {
        return new OkHttpTelegramClient(botToken);
    }
}
