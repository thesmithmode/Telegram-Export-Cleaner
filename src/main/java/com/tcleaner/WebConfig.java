package com.tcleaner;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Value("${SPRING_ASYNC_REQUEST_TIMEOUT_MS:900000}")
    private long asyncRequestTimeoutMs;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Конечный timeout защищает от зависших стримов и занятого пула потоков.
        configurer.setDefaultTimeout(asyncRequestTimeoutMs);
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
