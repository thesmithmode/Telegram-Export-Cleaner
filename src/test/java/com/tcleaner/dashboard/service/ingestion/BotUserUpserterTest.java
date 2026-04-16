package com.tcleaner.dashboard.service.ingestion;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.domain.BotUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Идемпотентность upsert {@link BotUser}: первое появление сохраняет {@code firstSeen},
 * повторные вызовы обновляют только {@code lastSeen}+denorm-метаданные без сдвига first_seen.
 */
@SpringBootTest
@Transactional
@DisplayName("BotUserUpserter")
class BotUserUpserterTest {

    @Autowired
    private BotUserUpserter upserter;

    @MockitoBean
    private TelegramExporter mockExporter;

    @Test
    @DisplayName("первый upsert создаёт запись с firstSeen = seenAt и нулевыми счётчиками")
    void firstUpsertCreatesRecord() {
        Instant ts = Instant.parse("2026-04-15T10:00:00Z");
        BotUser user = upserter.upsert(42L, "alice", "Alice A", ts);

        assertThat(user.getBotUserId()).isEqualTo(42L);
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getDisplayName()).isEqualTo("Alice A");
        assertThat(user.getFirstSeen()).isEqualTo(ts);
        assertThat(user.getLastSeen()).isEqualTo(ts);
        assertThat(user.getTotalExports()).isZero();
        assertThat(user.getTotalMessages()).isZero();
        assertThat(user.getTotalBytes()).isZero();
    }

    @Test
    @DisplayName("повторный upsert не меняет firstSeen, обновляет lastSeen и username")
    void subsequentUpsertUpdatesButPreservesFirstSeen() {
        Instant t1 = Instant.parse("2026-04-15T10:00:00Z");
        Instant t2 = Instant.parse("2026-04-15T11:00:00Z");

        upserter.upsert(42L, "old", "Old Name", t1);
        BotUser user = upserter.upsert(42L, "new", "New Name", t2);

        assertThat(user.getFirstSeen()).isEqualTo(t1);
        assertThat(user.getLastSeen()).isEqualTo(t2);
        assertThat(user.getUsername()).isEqualTo("new");
        assertThat(user.getDisplayName()).isEqualTo("New Name");
    }

    @Test
    @DisplayName("blank username/displayName не перетирают существующие значения")
    void blankFieldsPreserveExisting() {
        Instant ts = Instant.parse("2026-04-15T10:00:00Z");
        upserter.upsert(42L, "alice", "Alice", ts);
        BotUser user = upserter.upsert(42L, "  ", null, ts.plusSeconds(60));

        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getDisplayName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("событие с устаревшим ts не откатывает lastSeen назад")
    void staleEventDoesNotRegressLastSeen() {
        Instant t1 = Instant.parse("2026-04-15T11:00:00Z");
        Instant tStale = Instant.parse("2026-04-15T10:00:00Z");

        upserter.upsert(42L, "alice", "A", t1);
        BotUser user = upserter.upsert(42L, "alice", "A", tStale);

        assertThat(user.getLastSeen()).isEqualTo(t1);
    }

    @Test
    @DisplayName("null seenAt подставляет текущее время")
    void nullSeenAtUsesNow() {
        Instant before = Instant.now();
        BotUser user = upserter.upsert(42L, "a", "A", null);
        Instant after = Instant.now();

        assertThat(user.getFirstSeen()).isBetween(before, after);
    }
}
