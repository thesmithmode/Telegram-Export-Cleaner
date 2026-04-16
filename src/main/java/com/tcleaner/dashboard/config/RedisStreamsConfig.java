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
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;

/**
 * Конфигурация Redis Streams event-bus для статистики.
 * <p>
 * Создаёт consumer group при старте (идемпотентно — BUSYGROUP игнорируется),
 * поднимает {@link StreamMessageListenerContainer} и подписывает
 * {@link StatsStreamConsumer} на {@code StreamOffset.create(key, ReadOffset.lastConsumed())} —
 * с нуля читать не нужно: back-fill не делаем (см. docs/DASHBOARD.md).
 */
@Configuration
@ConditionalOnProperty(prefix = "dashboard.stats.stream", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class RedisStreamsConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsConfig.class);

    private final StringRedisTemplate redis;
    private final StatsStreamProperties props;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    public RedisStreamsConfig(StringRedisTemplate redis, StatsStreamProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * Создаёт consumer group один раз при старте.
     * BUSYGROUP (уже существует) — не ошибка, подавляем.
     */
    @PostConstruct
    void ensureConsumerGroup() {
        try {
            redis.opsForStream().createGroup(props.key(), ReadOffset.from("0"), props.group());
            log.info("Создана consumer group {}:{}", props.key(), props.group());
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("BUSYGROUP")) {
                log.debug("Consumer group {}:{} уже существует", props.key(), props.group());
            } else {
                log.warn("Не удалось создать consumer group {}:{}: {}", props.key(), props.group(), msg);
            }
        }
    }

    /**
     * {@link StreamMessageListenerContainer} читает стрим пачками по 10 сообщений
     * с блокировкой 2с — это даёт быструю реакцию в проде и не жжёт CPU на пустом стриме.
     */
    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            statsStreamContainer(RedisConnectionFactory connectionFactory, StatsStreamConsumer consumer) {
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
                        .errorHandler(ex -> log.warn("StreamMessageListener error: {}", ex.getMessage()))
                        .build();

        container.register(request, consumer);
        log.info("Подписан consumer {}:{}:{}", props.key(), props.group(), props.consumer());
        container.start();
        return container;
    }

    @PreDestroy
    void shutdown() {
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }
}
