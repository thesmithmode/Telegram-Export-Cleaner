package com.tcleaner.dashboard.domain;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.repository.BotUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRUD-smoke для {@link BotUser}.
 * {@code @Transactional} откатывает inserts — in-memory SQLite общая на
 * @SpringBootTest-ы и не должна протекать в smoke-тест с проверкой пустоты.
 */
@SpringBootTest
@Transactional
@DisplayName("BotUserRepository")
class BotUserRepositoryTest {

    @Autowired
    private BotUserRepository repository;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("save → findById возвращает сохранённую сущность")
    void saveAndFindById() {
        Instant now = Instant.parse("2026-04-15T12:00:00Z");
        BotUser saved = repository.save(BotUser.builder()
                .botUserId(42L)
                .username("alice")
                .displayName("Alice")
                .firstSeen(now)
                .lastSeen(now)
                .totalExports(0)
                .totalMessages(0L)
                .totalBytes(0L)
                .build());

        BotUser found = repository.findById(saved.getBotUserId()).orElseThrow();

        assertThat(found.getUsername()).isEqualTo("alice");
        assertThat(found.getDisplayName()).isEqualTo("Alice");
        assertThat(found.getFirstSeen()).isEqualTo(now);
    }

    @Test
    @DisplayName("findByUsername находит по username")
    void findByUsername() {
        Instant now = Instant.parse("2026-04-15T12:00:00Z");
        repository.save(BotUser.builder()
                .botUserId(43L)
                .username("bob")
                .firstSeen(now)
                .lastSeen(now)
                .build());

        assertThat(repository.findByUsername("bob")).isPresent();
        assertThat(repository.findByUsername("missing")).isEmpty();
    }
}
