package com.tcleaner.dashboard.config;

import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * Web-конфигурация дашборда. Регистрирует {@link LocaleResolver},
 * читающий язык из {@code BotUser.language} — единый источник правды с ботом.
 *
 * <p>Намеренно НЕ {@code implements WebMvcConfigurer}: иначе {@code @WebMvcTest}
 * slice-тесты (TelegramControllerTest и др.) авто-подхватят этот класс и потребуют
 * {@code BotUserUpserter} — которого нет в web-слайсе. Чистый {@code @Configuration}
 * подтягивается только через обычный {@code @ComponentScan} (main app).
 */
@Configuration
public class DashboardWebConfig {

    /**
     * Bean name {@code localeResolver} — обязательное имя, его ищет
     * {@code DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME} для резолва локали
     * в каждом запросе (Thymeleaf {@code #{...}} использует результат).
     */
    @Bean(name = "localeResolver")
    public LocaleResolver localeResolver(BotUserUpserter userUpserter) {
        return new BotUserLocaleResolver(userUpserter, new Locale("ru"));
    }
}
