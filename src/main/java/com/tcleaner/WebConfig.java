package com.tcleaner;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Устанавливаем бесконечный таймаут для асинхронных запросов (StreamingResponseBody)
        configurer.setDefaultTimeout(-1);
        // Назначаем выделенный пул потоков для асинхронной обработки, чтобы не забивать основной Tomcat
        configurer.setTaskExecutor(mvcTaskExecutor());
    }

    @Bean
    public ThreadPoolTaskExecutor mvcTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mvc-async-");
        executor.initialize();
        return executor;
    }
}
