package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Публикация событий статистики в Redis Stream.
 * Каждое сообщение — single-field map {@code {"payload": "<json>"}},
 * чтобы Python/Java-консьюмеры читали единый формат.
 * <p>
 * Используем низкоуровневый {@code xAdd(...)} через {@link RedisConnection}:
 * {@code StreamOperations.add} не принимает {@link XAddOptions} — а нам нужен
 * approximate MAXLEN trim, иначе стрим растёт неограниченно.
 */
@Component
public class StatsStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(StatsStreamPublisher.class);
    private static final String PAYLOAD_FIELD = "payload";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final StatsStreamProperties props;

    public StatsStreamPublisher(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            StatsStreamProperties props
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * Отправляет событие в стрим. Ошибки сериализации/сети логируются,
     * но не пробрасываются — статистика не должна валить основной flow
     * (экспорт идёт дальше даже если стрим недоступен).
     *
     * @param event payload события
     * @return id записи в стриме или {@code null}, если отправка не удалась
     */
    public String publish(StatsEventPayload event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            MapRecord<byte[], byte[], byte[]> record = MapRecord.create(
                    props.key().getBytes(StandardCharsets.UTF_8),
                    Map.of(
                            PAYLOAD_FIELD.getBytes(StandardCharsets.UTF_8),
                            json.getBytes(StandardCharsets.UTF_8)
                    ));
            XAddOptions options = XAddOptions.maxlen(props.maxlen()).approximateTrimming(true);
            RecordId id = redis.execute((RedisConnection conn) ->
                    conn.streamCommands().xAdd(record, options));
            return id != null ? id.getValue() : null;
        } catch (JsonProcessingException ex) {
            log.error("Не удалось сериализовать событие {}: {}", event.getType(), ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.warn("XADD в {} упал: {}", props.key(), ex.getMessage());
            return null;
        }
    }
}
