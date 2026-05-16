package com.tcleaner;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Изолированный пул для {@code @Async}-методов (например, StatsStreamPublisher.publish).
 * <p>
 * Bean назван {@code applicationTaskExecutor} — Spring {@code @EnableAsync} использует его
 * как default executor. До его появления @Async уходил на единственный TaskExecutor —
 * {@code mvcTaskExecutor} (WebConfig), который обслуживает StreamingResponseBody. Burst
 * stats-событий мог переполнить очередь MVC и уронить streaming-эндпоинты.
 * <p>
 * Параметры (core/max/queue) равны mvcTaskExecutor намеренно: цель — только изоляция
 * pool, не tuning. Реальные числа подберём после prod-замеров (RejectedExecutionException
 * counter, queue latency). AbortPolicy + best-effort lose в caller-catch.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("app-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return applicationTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
