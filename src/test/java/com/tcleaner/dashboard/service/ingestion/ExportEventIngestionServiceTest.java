package com.tcleaner.dashboard.service.ingestion;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.ExportEvent;
import com.tcleaner.dashboard.domain.ExportStatus;
import com.tcleaner.dashboard.domain.SubscriptionStatus;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.repository.BotUserRepository;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ChatSubscriptionRepository;
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

    @Autowired
    private ChatSubscriptionRepository subscriptions;

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

    @Test
    @DisplayName("fromDate/toDate в формате datetime (T00:00:00) корректно парсятся")
    void dateTimeFormatParsedAsLocalDate() {
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId(TASK).botUserId(USER_ID)
                .chatIdRaw("@chat").canonicalChatId("-100777")
                .chatTitle("Test Chat").source("bot").status("queued")
                .fromDate("2026-04-14T00:00:00")
                .toDate("2026-04-16T00:00:00")
                .ts(TS).build());

        ExportEvent ev = events.findByTaskId(TASK).orElseThrow();
        assertThat(ev.getFromDate()).isNotNull();
        assertThat(ev.getFromDate().toString()).isEqualTo("2026-04-14");
        assertThat(ev.getToDate()).isNotNull();
        assertThat(ev.getToDate().toString()).isEqualTo("2026-04-16");
    }

    @Test
    @DisplayName("fromDate в формате yyyy-MM-dd также корректно парсится")
    void plainDateFormatParsed() {
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId(TASK).botUserId(USER_ID)
                .chatIdRaw("@chat").canonicalChatId("-100777")
                .chatTitle("Test Chat").source("bot").status("queued")
                .fromDate("2026-04-14")
                .ts(TS).build());

        ExportEvent ev = events.findByTaskId(TASK).orElseThrow();
        assertThat(ev.getFromDate()).isNotNull();
        assertThat(ev.getFromDate().toString()).isEqualTo("2026-04-14");
    }

    // ─── updateSubscriptionOnTerminal: мост ingestion → subscription lifecycle ─
    //
    // Без этих проверок цикл подписки (1 экспорт раз в 5 мин вместо раз в 24ч)
    // мог бы вернуться: если recordSuccess перестанет вызываться на COMPLETED,
    // scheduler.isPeriodElapsed никогда не "упрётся" в свежий lastSuccessAt.

    @Test
    @DisplayName("terminal COMPLETED с subscriptionId → recordSuccess (lastSuccessAt выставлен, consecutiveFailures=0)")
    void completedUpdatesSubscriptionLifecycle() {
        ChatSubscription sub = newActiveSubscription(USER_ID, 2);

        service.ingest(started(sub.getId()));
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_COMPLETED)
                .taskId(TASK).botUserId(USER_ID)
                .subscriptionId(sub.getId())
                .messagesCount(10L).bytesCount(100L)
                .status("completed").ts(TS.plusSeconds(30)).build());

        ChatSubscription after = subscriptions.findById(sub.getId()).orElseThrow();
        assertThat(after.getLastSuccessAt()).isNotNull();
        assertThat(after.getConsecutiveFailures()).isZero();
        assertThat(after.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("terminal FAILED с subscriptionId → recordFailure (consecutiveFailures++)")
    void failedIncrementsConsecutiveFailures() {
        ChatSubscription sub = newActiveSubscription(USER_ID, 0);

        service.ingest(started(sub.getId()));
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_FAILED)
                .taskId(TASK).botUserId(USER_ID)
                .subscriptionId(sub.getId())
                .error("boom").status("failed")
                .ts(TS.plusSeconds(30)).build());

        ChatSubscription after = subscriptions.findById(sub.getId()).orElseThrow();
        assertThat(after.getLastFailureAt()).isNotNull();
        assertThat(after.getConsecutiveFailures()).isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("две подряд FAILED → подписка PAUSED")
    void twoFailuresInARowPause() {
        ChatSubscription sub = newActiveSubscription(USER_ID, 1);

        service.ingest(started(sub.getId()));
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_FAILED)
                .taskId(TASK).botUserId(USER_ID)
                .subscriptionId(sub.getId())
                .error("boom").status("failed")
                .ts(TS.plusSeconds(30)).build());

        ChatSubscription after = subscriptions.findById(sub.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(after.getConsecutiveFailures()).isEqualTo(2);
    }

    @Test
    @DisplayName("terminal CANCELLED с subscriptionId → lifecycle не трогается (ручная отмена юзером)")
    void cancelledDoesNotMutateSubscription() {
        ChatSubscription sub = newActiveSubscription(USER_ID, 0);
        Instant preUpdated = sub.getUpdatedAt();

        service.ingest(started(sub.getId()));
        service.ingest(StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_CANCELLED)
                .taskId(TASK).botUserId(USER_ID)
                .subscriptionId(sub.getId())
                .status("cancelled")
                .ts(TS.plusSeconds(30)).build());

        ChatSubscription after = subscriptions.findById(sub.getId()).orElseThrow();
        assertThat(after.getLastSuccessAt()).isNull();
        assertThat(after.getLastFailureAt()).isNull();
        assertThat(after.getConsecutiveFailures()).isZero();
        assertThat(after.getUpdatedAt()).isEqualTo(preUpdated);
    }

    private ChatSubscription newActiveSubscription(long botUserId, int failures) {
        Instant now = Instant.now();

        // FK constraints: bot_user_id → bot_users, chat_ref_id → chats
        users.save(BotUser.builder()
                .botUserId(botUserId)
                .firstSeen(now).lastSeen(now)
                .totalExports(0).totalMessages(0L).totalBytes(0L)
                .build());
        Chat chat = chats.save(Chat.builder()
                .canonicalChatId("-100888").chatIdRaw("-100888")
                .firstSeen(now).lastSeen(now)
                .build());

        return subscriptions.save(ChatSubscription.builder()
                .botUserId(botUserId)
                .chatRefId(chat.getId())
                .periodHours(24)
                .desiredTimeMsk("09:00")
                .sinceDate(now.minusSeconds(25 * 3600L))
                .status(SubscriptionStatus.ACTIVE)
                .consecutiveFailures(failures)
                .lastConfirmAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private static StatsEventPayload started(Long subscriptionId) {
        return StatsEventPayload.builder()
                .type(StatsEventType.EXPORT_STARTED)
                .taskId(TASK)
                .botUserId(USER_ID)
                .chatIdRaw("@chat")
                .canonicalChatId("-100777")
                .chatTitle("Test Chat")
                .source("bot")
                .status("queued")
                .subscriptionId(subscriptionId)
                .ts(TS)
                .build();
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
