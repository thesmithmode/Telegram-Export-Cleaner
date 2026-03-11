package com.tcleaner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Сервис для хранения статусов обработки файлов в Redis.
 *
 * <p>Ключ: {@code status:<fileId>}, значение: имя {@link ProcessingStatus}.</p>
 * <p>TTL каждой записи совпадает с {@code app.storage.export-ttl-minutes} —
 * статус живёт столько же, сколько сам файл в Export.</p>
 */
@Service
public class ProcessingStatusService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingStatusService.class);
    private static final String KEY_PREFIX = "status:";

    private final StringRedisTemplate redisTemplate;
    private final StorageConfig storageConfig;

    /**
     * Конструктор.
     *
     * @param redisTemplate клиент Redis
     * @param storageConfig конфигурация хранилища (используется TTL)
     */
    public ProcessingStatusService(StringRedisTemplate redisTemplate, StorageConfig storageConfig) {
        this.redisTemplate = redisTemplate;
        this.storageConfig = storageConfig;
    }

    /**
     * Сохраняет статус обработки файла в Redis с TTL.
     *
     * @param fileId идентификатор файла (UUID)
     * @param status статус обработки
     */
    public void setStatus(String fileId, ProcessingStatus status) {
        String key = KEY_PREFIX + fileId;
        Duration ttl = Duration.ofMinutes(storageConfig.getExportTtlMinutes());
        redisTemplate.opsForValue().set(key, status.name(), ttl);
        log.debug("Статус {} сохранён для fileId={}, TTL={}", status, fileId, ttl);
    }

    /**
     * Возвращает статус обработки файла из Redis.
     *
     * @param fileId идентификатор файла (UUID)
     * @return {@link Optional} со статусом, или пустой если запись истекла / не найдена
     */
    public Optional<ProcessingStatus> getStatus(String fileId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + fileId);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ProcessingStatus.valueOf(value));
        } catch (IllegalArgumentException ex) {
            log.warn("Неизвестный статус в Redis для fileId={}: {}", fileId, value);
            return Optional.empty();
        }
    }

    /**
     * Удаляет статус из Redis (например при очистке).
     *
     * @param fileId идентификатор файла (UUID)
     */
    public void deleteStatus(String fileId) {
        redisTemplate.delete(KEY_PREFIX + fileId);
        log.debug("Статус удалён для fileId={}", fileId);
    }
}
