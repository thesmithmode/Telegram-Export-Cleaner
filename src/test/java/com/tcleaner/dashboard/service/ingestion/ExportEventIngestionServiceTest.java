package com.tcleaner.dashboard.service.ingestion;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.domain.ExportEvent;
import com.tcleaner.dashboard.domain.ExportStatus;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.repository.BotUserRepository;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ExportEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет merge-семантику ingestion-сервиса:
 * <ul>
 *   <li>повтор события по {@code task_id} → одна строка (идемпотентность)</li>
 *   <li>late {@code completed} до {@code started} → строка создаётся со всеми полями</li>
 *   <li>terminal → terminal повторно не инкрементит {@code totalExports}</li>
 *   <li>{@code bytes_measured} дописывает bytes, не меняя статус</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@DisplayName("ExportEventIngestionService")
class ExportEventIngestionServiceTest {

    @Autowired
    private ExportEventIngestionService service;

    @Autowired
    private ExportEventRepository events;

    @Autowired
    private BotUserRepository users;

    @Autowired
    private ChatRepository chats;

    @MockitoBean
    private TelegramExporter mockExporter;

    private static final long USER_ID = 42L;
    private static final String TASK = "task-xyz";
    private static final Instant TS = Instant.parse("2026-04-15T12:00:00Z");

    @Test
    @DisplayName("bot_user.seen создаёт/обновляет BotUser")
    void handlesBotUserSeen() {
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.BOT_USER_SEEN)
                .botUserId(USER_ID).username("alice").displayName("Alice")
                .ts(TS).build());

        BotUser user = users.findById(USER_ID).orElseThrow();
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getFirstSeen()).isEqualTo(TS);
    }

    @Test
    @DisplayName("export.started создаёт событие со статусом QUEUED и привязкой к chat/user")
    void startedCreatesQueuedRow() {
        service.ingest(started());

        ExportEvent ev = events.findByTaskId(TASK).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(ExportStatus.QUEUED);
        assertThat(ev.getBotUserId()).isEqualTo(USER_ID);
        assertThat(chats.findById(ev.getChatRefId())).isPresent();
        assertThat(users.findById(USER_ID)).isPresent();
    }

    @Test
    @DisplayName("повторный started по тому же task_id не плодит дубликатов")
    void repeatedStartedIsIdempotent() {
        service.ingest(started());
        service.ingest(started());

        assertThat(events.findByTaskId(TASK)).isPresent();
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("started → completed: статус и финальные метрики сохранены, totalExports += 1")
    void startedThenCompletedAggregates() {
        service.ingest(started());
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_COMPLETED)
                .taskId(TASK).botUserId(USER_ID)
                .messagesCount(100L).bytesCount(2048L)
                .status("completed").ts(TS.plusSeconds(30)).build());

        ExportEvent ev = events.findByTaskId(TASK).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(ExportStatus.COMPLETED);
        assertThat(ev.getMessagesCount()).isEqualTo(100L);
        assertThat(ev.getBytesCount()).isEqualTo(2048L);
        assertThat(ev.getFinishedAt()).isEqualTo(TS.plusSeconds(30));

        BotUser user = users.findById(USER_ID).orElseThrow();
        assertThat(user.getTotalExports()).isEqualTo(1);
        assertThat(user.getTotalMessages()).isEqualTo(100L);
        assertThat(user.getTotalBytes()).isEqualTo(2048L);
    }

    @Test
    @DisplayName("late completed до started: запись создаётся со статусом COMPLETED (recovery)")
    void lateCompletedBeforeStartedRecovers() {
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_COMPLETED)
                .taskId(TASK).botUserId(USER_ID)
                .chatIdRaw("@chat").canonicalChatId("-100777")
                .messagesCount(50L).bytesCount(1024L)
                .status("completed").ts(TS).build());

        ExportEvent ev = events.findByTaskId(TASK).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(ExportStatus.COMPLETED);
        assertThat(ev.getBytesCount()).isEqualTo(1024L);
    }

    @Test
    @DisplayName("completed повторно не инкрементит totalExports второй раз")
    void doubleCompletedDoesNotDoubleBump() {
        service.ingest(started());
        StatsEventPayload completed = StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_COMPLETED)
                .taskId(TASK).botUserId(USER_ID)
                .messagesCount(10L).bytesCount(100L)
                .status("completed").ts(TS.plusSeconds(30)).build();

        service.ingest(completed);
        service.ingest(completed);

        BotUser user = users.findById(USER_ID).orElseThrow();
        assertThat(user.getTotalExports()).isEqualTo(1);
        assertThat(user.getTotalMessages()).isEqualTo(10L);
    }

    @Test
    @DisplayName("bytes_measured дописывает байты, не меняя статус")
    void bytesMeasuredUpdatesBytesOnly() {
        service.ingest(started());
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_BYTES_MEASURED)
                .taskId(TASK).bytesCount(4096L)
                .ts(TS.plusSeconds(10)).build());

        ExportEvent ev = events.findByTaskId(TASK).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(ExportStatus.QUEUED);
        assertThat(ev.getBytesCount()).isEqualTo(4096L);
    }

    @Test
    @DisplayName("failed/cancelled после completed не откатывают статус")
    void terminalStatusIsSticky() {
        service.ingest(started());
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_COMPLETED)
                .taskId(TASK).botUserId(USER_ID)
                .messagesCount(10L).bytesCount(100L)
                .status("completed").ts(TS.plusSeconds(30)).build());
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_FAILED)
                .taskId(TASK).error("late fail").status("failed")
                .ts(TS.plusSeconds(60)).build());

        ExportEvent ev = events.findByTaskId(TASK).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(ExportStatus.COMPLETED);
    }

    @Test
    @DisplayName("событие без task_id — просто пропуск, без исключения")
    void missingTaskIdSkipped() {
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .botUserId(USER_ID).chatIdRaw("@c").ts(TS).build());

        assertThat(events.count()).isZero();
    }

    @Test
    @DisplayName("первое событие без bot_user_id/chat — пропуск")
    void firstEventWithoutKeyFieldsSkipped() {
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_BYTES_MEASURED)
                .taskId(TASK).bytesCount(10L).ts(TS).build());

        assertThat(events.findByTaskId(TASK)).isEmpty();
    }

    private static StatsEventPayload started() {
        return StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId(TASK)
                .botUserId(USER_ID)
                .chatIdRaw("@chat")
                .canonicalChatId("-100777")
                .chatTitle("Test Chat")
                .source("bot")
                .status("queued")
                .ts(TS)
                .build();
    }
}
