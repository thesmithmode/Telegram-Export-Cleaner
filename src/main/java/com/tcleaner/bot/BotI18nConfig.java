package com.tcleaner.bot;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Конфиг отдельного {@link MessageSource} для текстов бота.
 *
 * <p>Дашборд и бот держат переводы в разных bundle-ах:
 * {@code messages.properties} (дашборд) и {@code bot_messages.properties} (бот) —
 * разные владельцы, разные циклы обновления, меньше конфликтов при мердже.
 *
 * <p>Fallback: {@code en} (базовый файл без суффикса) — возвращается при отсутствии
 * ключа в запрошенной локали.
 */
@Configuration
public class BotI18nConfig {

    @Bean(name = "botMessageSource")
    public MessageSource botMessageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:bot_messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.ENGLISH);
        // Перечитываем ресурсы раз в 10 минут без рестарта (-1 в dev — всегда свежее).
        source.setCacheSeconds(600);
        return source;
    }
}
