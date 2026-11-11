package com.tcleaner.dashboard.config;

import com.tcleaner.dashboard.events.StatsStreamConsumer;
import com.tcleaner.dashboard.events.StatsStreamProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Existing groups resume their offset; new groups read retained events from 0.
@Configuration
@ConditionalOnProperty(prefix = "dashboard.stats.stream", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class RedisStreamsConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsConfig.class);

    private final StringRedisTemplate redis;
    private final StatsStreamProperties props;
    private final CountDownLatch shutdownSignal = new CountDownLatch(1);
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private StatsStreamConsumer streamConsumer;

    public RedisStreamsConfig(StringRedisTemplate redis, StatsStreamProperties props) {
        this.redis = redis;
        this.props = props;
    }

    // BUSYGROUP = already exists → ok. Spring Data Redis оборачивает в generic Error, поэтому
    // идём по всей цепочке causes, не ловим по типу.
    @PostConstruct
    void ensureConsumerGroup() {
        try {
            redis.execute((RedisConnection connection) -> connection.streamCommands().xGroupCreate(
                    props.key().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    props.group(),
                    ReadOffset.from("0"),
                    true));
            log.info("Создана consumer group {}:{}", props.key(), props.group());
        } catch (Exception ex) {
            if (isBusyGroup(ex)) {
                log.debug("Consumer group {}:{} уже существует", props.key(), props.group());
            } else {
                log.warn("Не удалось создать consumer group {}:{}: {}",
                        props.key(), props.group(), ex.getMessage());
            }
        }
    }

    private static boolean isBusyGroup(Throwable ex) {
        return hasRedisError(ex, "BUSYGROUP");
    }

    private static boolean hasRedisError(Throwable ex, String code) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains(code)) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            statsStreamContainer(RedisConnectionFactory connectionFactory, StatsStreamConsumer consumer) {
        this.streamConsumer = consumer;
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String,
                MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .batchSize(10)
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();

        this.container = StreamMessageListenerContainer.create(connectionFactory, options);

        StreamMessageListenerContainer.StreamReadRequest<String> request =
                StreamMessageListenerContainer.StreamReadRequest
                        .builder(StreamOffset.create(props.key(), ReadOffset.lastConsumed()))
                        .consumer(Consumer.from(props.group(), props.consumer()))
                        .autoAcknowledge(false)
                        // The default cancels the subscription permanently on a read error,
                        // even while container.isRunning() remains true.
                        .cancelOnError(ex -> false)
                        .errorHandler(this::handleReadError)
                        .build();

        container.register(request, consumer);
        log.info("Подписан consumer {}:{}:{}", props.key(), props.group(), props.consumer());
        container.start();
        return container;
    }

    void handleReadError(Throwable ex) {
        if (container == null || !container.isRunning()) {
            return;
        }
        log.warn("Stats stream read failed; retry in 30s: {}", ex.getMessage());
        try {
            // Only the dedicated stream reader waits, never a shared scheduler.
            // Healthy reads incur no additional commands or background polling.
            if (!awaitReadRetry()) {
                if (hasRedisError(ex, "NOGROUP")) {
                    ensureConsumerGroup();
                }
                // Redis may have delivered a batch into PEL before its reply was lost.
                // Those record IDs are unknown to the normal per-record retry path.
                streamConsumer.recoverPendingAfterReadFailure();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    boolean awaitReadRetry() throws InterruptedException {
        return shutdownSignal.await(30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        shutdownSignal.countDown();
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }
}
