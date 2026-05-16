package com.tcleaner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AsyncConfig — изолированный пул для @Async")
class AsyncConfigTest {

    private AsyncConfig config;
    private ThreadPoolTaskExecutor exec;

    @BeforeEach
    void setUp() {
        config = new AsyncConfig();
        exec = config.applicationTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (exec != null) {
            exec.shutdown();
        }
    }

    @Test
    @DisplayName("applicationTaskExecutor: core=5 max=10 queue=100 prefix=app-async-")
    void executorHasIsolatedPoolParams() {
        assertThat(exec.getCorePoolSize()).isEqualTo(5);
        assertThat(exec.getMaxPoolSize()).isEqualTo(10);
        assertThat(exec.getQueueCapacity()).isEqualTo(100);
        assertThat(exec.getThreadNamePrefix()).isEqualTo("app-async-");
        assertThat(exec.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    @Test
    @DisplayName("getAsyncExecutor возвращает не-null Executor (ThreadPoolTaskExecutor)")
    void asyncExecutorIsConfigured() {
        Executor e = config.getAsyncExecutor();
        assertThat(e).isNotNull().isInstanceOf(ThreadPoolTaskExecutor.class);
    }

    @Test
    @DisplayName("uncaught handler — SimpleAsyncUncaughtExceptionHandler (логирует, не падает)")
    void uncaughtHandlerLogsOnly() {
        assertThat(config.getAsyncUncaughtExceptionHandler())
                .isInstanceOf(SimpleAsyncUncaughtExceptionHandler.class);
    }

    @Test
    @DisplayName("thread-name на запущенной задаче имеет префикс app-async-")
    void submittedTaskRunsOnIsolatedThread() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> threadName =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        exec.submit(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });
        assertThat(done.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("app-async-");
    }
}
