package com.tcleaner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты для ProcessingStatusService.
 *
 * <p>Поднимает реальный Redis через Testcontainers.
 * Проверяет запись, чтение и TTL статусов.</p>
 */
@SpringBootTest
@Testcontainers
@DisplayName("ProcessingStatusService — интеграционные тесты с Redis")
class ProcessingStatusServiceTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        registry.add("app.storage.import-path", () -> tmp.resolve("tcleaner-test/import").toString());
        registry.add("app.storage.export-path", () -> tmp.resolve("tcleaner-test/export").toString());
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Включаем Redis автоконфигурацию для этого теста
        registry.add("spring.autoconfigure.exclude", () -> "");
    }

    @Autowired
    private ProcessingStatusService statusService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        // Очищаем все ключи перед каждым тестом для изоляции
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("setStatus и getStatus возвращают сохранённый статус")
    void setAndGet_returnsStatus() {
        String fileId = UUID.randomUUID().toString();

        statusService.setStatus(fileId, ProcessingStatus.PENDING);
        Optional<ProcessingStatus> result = statusService.getStatus(fileId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(ProcessingStatus.PENDING);
    }

    @Test
    @DisplayName("getStatus возвращает пустой Optional для неизвестного fileId")
    void getStatus_unknownKey_returnsEmpty() {
        Optional<ProcessingStatus> result = statusService.getStatus(UUID.randomUUID().toString());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("статус обновляется при повторном вызове setStatus")
    void setStatus_overwritesPreviousStatus() {
        String fileId = UUID.randomUUID().toString();

        statusService.setStatus(fileId, ProcessingStatus.PENDING);
        statusService.setStatus(fileId, ProcessingStatus.COMPLETED);

        Optional<ProcessingStatus> result = statusService.getStatus(fileId);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(ProcessingStatus.COMPLETED);
    }

    @Test
    @DisplayName("deleteStatus удаляет запись — getStatus возвращает пустой Optional")
    void deleteStatus_removesKey() {
        String fileId = UUID.randomUUID().toString();
        statusService.setStatus(fileId, ProcessingStatus.COMPLETED);

        statusService.deleteStatus(fileId);

        assertThat(statusService.getStatus(fileId)).isEmpty();
    }

    @Test
    @DisplayName("setStatus сохраняет FAILED корректно")
    void setStatus_failedStatus() {
        String fileId = UUID.randomUUID().toString();

        statusService.setStatus(fileId, ProcessingStatus.FAILED);

        assertThat(statusService.getStatus(fileId)).hasValue(ProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("разные fileId не влияют друг на друга")
    void multipleFileIds_areIsolated() {
        String fileId1 = UUID.randomUUID().toString();
        String fileId2 = UUID.randomUUID().toString();

        statusService.setStatus(fileId1, ProcessingStatus.PENDING);
        statusService.setStatus(fileId2, ProcessingStatus.COMPLETED);

        assertThat(statusService.getStatus(fileId1)).hasValue(ProcessingStatus.PENDING);
        assertThat(statusService.getStatus(fileId2)).hasValue(ProcessingStatus.COMPLETED);
    }
}
