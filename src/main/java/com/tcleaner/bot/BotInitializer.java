package com.tcleaner.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Инициализатор Telegram-бота.
 *
 * <p>Регистрирует бот с TelegramBotsApi при старте Spring контекста.
 * Без Spring Boot стартера нужно вручную инициализировать API и регистрировать бот.</p>
 */
@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class BotInitializer {

    private static final Logger log = LoggerFactory.getLogger(BotInitializer.class);

    private final ExportBot exportBot;

    /**
     * Конструктор.
     *
     * @param exportBot инстанс бота
     */
    public BotInitializer(ExportBot exportBot) {
        this.exportBot = exportBot;
    }

    /**
     * Регистрирует бот при готовности контекста.
     *
     * @param event событие готовности контекста
     */
    @EventListener(ContextRefreshedEvent.class)
    public void init(ContextRefreshedEvent event) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(exportBot);
            log.info("Telegram bot successfully registered and started polling");
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot: {}", e.getMessage(), e);
        }
    }
}
