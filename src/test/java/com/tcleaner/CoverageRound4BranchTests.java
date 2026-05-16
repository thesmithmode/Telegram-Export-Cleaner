package com.tcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcleaner.core.MessageFilter;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ExportEvent;
import com.tcleaner.dashboard.domain.ExportSource;
import com.tcleaner.dashboard.domain.ExportStatus;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ExportEventRepository;
import com.tcleaner.dashboard.service.cache.CacheMetricsService;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import com.tcleaner.dashboard.service.ingestion.ChatUpserter;
import com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService;
import com.tcleaner.dashboard.service.subscription.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round 4 branch coverage: целенаправленный набор тестов для классов
 * с наибольшим BRANCH_MISSED в jacoco-отчёте. Чистый Mockito без
 * Spring-контекста — быстрые unit-тесты по конкретным if/catch/switch ветвям.
 * Целевой gate: BUNDLE BRANCH 0.85→0.90, LINE 0.94→0.95.
 */
@DisplayName("Round 4 — branch coverage gaps")
class CoverageRound4BranchTests {

    // ─── ExportEventIngestionService (18 BRANCH_MISSED) ─────────────────────

    @Nested
    @DisplayName("ExportEventIngestionService — branch gaps")
    class IngestionGaps {

        private ExportEventRepository events;
        private BotUserUpserter userUpserter;
        private ChatUpserter chatUpserter;
        private SubscriptionService subs;
        private ExportEventIngestionService svc;

        @BeforeEach
        void init() {
            events = mock(ExportEventRepository.class);
            userUpserter = mock(BotUserUpserter.class);
            chatUpserter = mock(ChatUpserter.class);
            subs = mock(SubscriptionService.class);
            svc = new ExportEventIngestionService(events, userUpserter, chatUpserter, subs);
        }

        @Test
        @DisplayName("payload null → early return, никаких side-effects")
        void payloadNullEarlyReturn() {
            svc.ingest(null);

            verify(events, never()).save(any());
            verify(userUpserter, never()).upsert(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("payload.type null → early return")
        void typeNullEarlyReturn() {
            svc.ingest(StatsEventPayload.builder().taskId("t").build());

            verify(events, never()).save(any());
        }

        @Test
        @DisplayName("BOT_USER_SEEN с botUserId=null → skip без upsert")
        void botUserSeenNullId() {
            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.BOT_USER_SEEN)
                    .username("alice").build());

            verify(userUpserter, never()).upsert(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("EXPORT_STARTED с null task_id → skip, не пишем в БД")
        void startedNullTaskId() {
            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_STARTED).build());

            verify(events, never()).save(any());
        }

        @Test
        @DisplayName("EXPORT_STARTED с blank task_id → skip")
        void startedBlankTaskId() {
            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_STARTED).taskId("   ").build());

            verify(events, never()).save(any());
        }

        @Test
        @DisplayName("EXPORT_STARTED без bot_user_id/chat (минимальные поля) → skip insert")
        void startedMissingMinimalFields() {
            when(events.findByTaskId("t1")).thenReturn(Optional.empty());

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_STARTED).taskId("t1").build());

            verify(events, never()).save(any());
            verify(chatUpserter, never()).upsert(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("ingest бросает (chatUpserter throw) → @Transactional rollback → exception проброшен наружу")
        void ingestRethrowsOnFailure() {
            when(events.findByTaskId(anyString())).thenReturn(Optional.empty());
            when(chatUpserter.upsert(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB fail"));

            try {
                svc.ingest(StatsEventPayload.builder()
                        .type(StatsEventType.EXPORT_STARTED).taskId("tX")
                        .botUserId(1L).chatIdRaw("@c").build());
                org.junit.jupiter.api.Assertions.fail("ожидался RuntimeException");
            } catch (RuntimeException ex) {
                assertThat(ex.getMessage()).contains("DB fail");
            }
        }

        @Test
        @DisplayName("updateSubscriptionOnTerminal: recordSuccess бросает → swallowed, не рушит ingest")
        void subscriptionUpdateSwallowsException() {
            // ивент EXPORT_COMPLETED с subscriptionId — терминал → recordSuccess
            when(events.findByTaskId("t2")).thenReturn(Optional.empty());
            Chat chatRet = Chat.builder().id(1L).canonicalChatId("@c").build();
            BotUser userRet = BotUser.builder().botUserId(1L).build();
            when(chatUpserter.upsert(any(), any(), any(), any(), any())).thenReturn(chatRet);
            when(userUpserter.upsert(anyLong(), any(), any(), any())).thenReturn(userRet);
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("DB sub error"))
                    .when(subs).recordSuccess(anyLong());

            // Не должно бросить
            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_COMPLETED).taskId("t2")
                    .botUserId(1L).chatIdRaw("@c")
                    .subscriptionId(99L)
                    .messagesCount(10L).bytesCount(100L)
                    .build());

            verify(subs).recordSuccess(99L);
        }

        @Test
        @DisplayName("updateSubscriptionOnTerminal: FAILED → recordFailure (не recordSuccess)")
        void subscriptionUpdateFailedCallsRecordFailure() {
            when(events.findByTaskId("t3")).thenReturn(Optional.empty());
            Chat chatRet = Chat.builder().id(1L).canonicalChatId("@c").build();
            BotUser userRet = BotUser.builder().botUserId(1L).build();
            when(chatUpserter.upsert(any(), any(), any(), any(), any())).thenReturn(chatRet);
            when(userUpserter.upsert(anyLong(), any(), any(), any())).thenReturn(userRet);
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_FAILED).taskId("t3")
                    .botUserId(1L).chatIdRaw("@c").subscriptionId(50L)
                    .build());

            verify(subs).recordFailure(50L);
            verify(subs, never()).recordSuccess(anyLong());
        }

        @Test
        @DisplayName("updateSubscriptionOnTerminal: CANCELLED — НЕ дёргает recordSuccess/recordFailure")
        void cancelledDoesNotUpdateSubscription() {
            when(events.findByTaskId("tC")).thenReturn(Optional.empty());
            Chat chatRet = Chat.builder().id(1L).canonicalChatId("@c").build();
            BotUser userRet = BotUser.builder().botUserId(1L).build();
            when(chatUpserter.upsert(any(), any(), any(), any(), any())).thenReturn(chatRet);
            when(userUpserter.upsert(anyLong(), any(), any(), any())).thenReturn(userRet);
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_CANCELLED).taskId("tC")
                    .botUserId(1L).chatIdRaw("@c").subscriptionId(7L)
                    .build());

            verify(subs, never()).recordSuccess(anyLong());
            verify(subs, never()).recordFailure(anyLong());
        }

        @Test
        @DisplayName("subscriptionId=null → ветка raннего return в updateSubscriptionOnTerminal")
        void nullSubscriptionIdSkipsUpdate() {
            when(events.findByTaskId("tNull")).thenReturn(Optional.empty());
            Chat chatRet = Chat.builder().id(1L).canonicalChatId("@c").build();
            BotUser userRet = BotUser.builder().botUserId(1L).build();
            when(chatUpserter.upsert(any(), any(), any(), any(), any())).thenReturn(chatRet);
            when(userUpserter.upsert(anyLong(), any(), any(), any())).thenReturn(userRet);
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_COMPLETED).taskId("tNull")
                    .botUserId(1L).chatIdRaw("@c")
                    .build());

            verify(subs, never()).recordSuccess(anyLong());
        }

        @Test
        @DisplayName("EXPORT_BYTES_MEASURED для existing — обновляет bytes, статус не меняет")
        void bytesMeasuredOnlyUpdatesBytes() {
            ExportEvent existing = ExportEvent.builder()
                    .taskId("tb").botUserId(1L).chatRefId(1L)
                    .status(ExportStatus.COMPLETED)
                    .startedAt(Instant.now())
                    .build();
            when(events.findByTaskId("tb")).thenReturn(Optional.of(existing));
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_BYTES_MEASURED).taskId("tb")
                    .bytesCount(5000L).build());

            assertThat(existing.getBytesCount()).isEqualTo(5000L);
            // статус не меняется
            assertThat(existing.getStatus()).isEqualTo(ExportStatus.COMPLETED);
        }

        @Test
        @DisplayName("Late STARTED после COMPLETED — статус НЕ откатывается (canAdvanceStatus prev=terminal)")
        void lateStartedDoesNotOverwriteTerminal() {
            ExportEvent existing = ExportEvent.builder()
                    .taskId("tL").botUserId(1L).chatRefId(1L)
                    .status(ExportStatus.COMPLETED)
                    .startedAt(Instant.now())
                    .finishedAt(Instant.now())
                    .build();
            when(events.findByTaskId("tL")).thenReturn(Optional.of(existing));
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_STARTED).taskId("tL")
                    .botUserId(1L).chatIdRaw("@c").build());

            assertThat(existing.getStatus()).isEqualTo(ExportStatus.COMPLETED);
        }

        @Test
        @DisplayName("Existing event: chat metadata update (chatTitle != null) дёргает upsert чата")
        void existingEventUpdatesChatMetadata() {
            ExportEvent existing = ExportEvent.builder()
                    .taskId("tCh").botUserId(1L).chatRefId(1L)
                    .status(ExportStatus.QUEUED)
                    .startedAt(Instant.now())
                    .build();
            when(events.findByTaskId("tCh")).thenReturn(Optional.of(existing));
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_BYTES_MEASURED).taskId("tCh")
                    .chatTitle("Renamed Chat").build());

            verify(chatUpserter).upsert(any(), any(), any(), eq("Renamed Chat"), any());
        }

        @Test
        @DisplayName("coalesce: blank string не перетирает существующее поле")
        void coalesceBlankSkipped() {
            ExportEvent existing = ExportEvent.builder()
                    .taskId("tCo").botUserId(1L).chatRefId(1L)
                    .status(ExportStatus.QUEUED)
                    .startedAt(Instant.now())
                    .keywords("original")
                    .build();
            when(events.findByTaskId("tCo")).thenReturn(Optional.of(existing));
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_BYTES_MEASURED).taskId("tCo")
                    .keywords("   ").build());

            assertThat(existing.getKeywords()).isEqualTo("original");
        }

        @Test
        @DisplayName("parseDate: datetime fallback — '2026-04-14T00:00:00' → 2026-04-14")
        void parseDateDateTimeFallback() {
            when(events.findByTaskId("tDt")).thenReturn(Optional.empty());
            Chat chatRet = Chat.builder().id(1L).canonicalChatId("@c").build();
            BotUser userRet = BotUser.builder().botUserId(1L).build();
            when(chatUpserter.upsert(any(), any(), any(), any(), any())).thenReturn(chatRet);
            when(userUpserter.upsert(anyLong(), any(), any(), any())).thenReturn(userRet);
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_STARTED).taskId("tDt")
                    .botUserId(1L).chatIdRaw("@c")
                    .fromDate("2026-04-14T00:00:00")
                    .toDate("garbage")  // невалидный → ex2 → null
                    .build());

            org.mockito.ArgumentCaptor<ExportEvent> cap = org.mockito.ArgumentCaptor.forClass(ExportEvent.class);
            verify(events).save(cap.capture());
            assertThat(cap.getValue().getFromDate()).isEqualTo(LocalDate.of(2026, 4, 14));
            assertThat(cap.getValue().getToDate()).isNull();
        }

        @Test
        @DisplayName("parseSource: blank → BOT default")
        void parseSourceBlankDefault() {
            when(events.findByTaskId("tS1")).thenReturn(Optional.empty());
            Chat chatRet = Chat.builder().id(1L).canonicalChatId("@c").build();
            BotUser userRet = BotUser.builder().botUserId(1L).build();
            when(chatUpserter.upsert(any(), any(), any(), any(), any())).thenReturn(chatRet);
            when(userUpserter.upsert(anyLong(), any(), any(), any())).thenReturn(userRet);
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_STARTED).taskId("tS1")
                    .botUserId(1L).chatIdRaw("@c")
                    .source("   ").build());

            org.mockito.ArgumentCaptor<ExportEvent> cap = org.mockito.ArgumentCaptor.forClass(ExportEvent.class);
            verify(events).save(cap.capture());
            assertThat(cap.getValue().getSource()).isEqualTo(ExportSource.BOT);
        }

        @Test
        @DisplayName("parseSource: невалидное значение → BOT fallback (catch IllegalArgumentException)")
        void parseSourceInvalidFallback() {
            when(events.findByTaskId("tS2")).thenReturn(Optional.empty());
            Chat chatRet = Chat.builder().id(1L).canonicalChatId("@c").build();
            BotUser userRet = BotUser.builder().botUserId(1L).build();
            when(chatUpserter.upsert(any(), any(), any(), any(), any())).thenReturn(chatRet);
            when(userUpserter.upsert(anyLong(), any(), any(), any())).thenReturn(userRet);
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_STARTED).taskId("tS2")
                    .botUserId(1L).chatIdRaw("@c")
                    .source("unknown_xyz").build());

            org.mockito.ArgumentCaptor<ExportEvent> cap = org.mockito.ArgumentCaptor.forClass(ExportEvent.class);
            verify(events).save(cap.capture());
            assertThat(cap.getValue().getSource()).isEqualTo(ExportSource.BOT);
        }

        @Test
        @DisplayName("maybeBumpUserTotals: COMPLETED с null messages/bytes — totalExports++, остальные не трогаются")
        void totalsBumpNullCountsNotApplied() {
            when(events.findByTaskId("tT")).thenReturn(Optional.empty());
            Chat chatRet = Chat.builder().id(1L).canonicalChatId("@c").build();
            BotUser userRet = BotUser.builder().botUserId(1L)
                    .totalExports(5).totalMessages(100L).totalBytes(1000L).build();
            when(chatUpserter.upsert(any(), any(), any(), any(), any())).thenReturn(chatRet);
            when(userUpserter.upsert(anyLong(), any(), any(), any())).thenReturn(userRet);
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_COMPLETED).taskId("tT")
                    .botUserId(1L).chatIdRaw("@c")
                    // messages/bytes намеренно null
                    .build());

            assertThat(userRet.getTotalExports()).isEqualTo(6);
            assertThat(userRet.getTotalMessages()).isEqualTo(100L);
            assertThat(userRet.getTotalBytes()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("Insert через canonicalChatId (chatIdRaw отсутствует) — минимальные поля приняты")
        void insertWithCanonicalOnly() {
            when(events.findByTaskId("tCanon")).thenReturn(Optional.empty());
            Chat chatRet = Chat.builder().id(1L).canonicalChatId("-100xyz").build();
            BotUser userRet = BotUser.builder().botUserId(7L).build();
            when(chatUpserter.upsert(any(), any(), any(), any(), any())).thenReturn(chatRet);
            when(userUpserter.upsert(anyLong(), any(), any(), any())).thenReturn(userRet);
            when(events.save(any(ExportEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            svc.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_STARTED).taskId("tCanon")
                    .botUserId(7L).canonicalChatId("-100xyz")
                    // chatIdRaw отсутствует — но canonical задан → hasMinimalFieldsForInsert=true
                    .build());

            verify(events).save(any(ExportEvent.class));
        }
    }

    // ─── MessageFilter (7 BRANCH_MISSED) ────────────────────────────────────

    @Nested
    @DisplayName("MessageFilter — branch gaps")
    class MessageFilterGaps {

        private final ObjectMapper json = new ObjectMapper();

        @Test
        @DisplayName("matches(null) → false")
        void matchesNullReturnsFalse() {
            MessageFilter f = new MessageFilter().withStartDate(LocalDate.of(2024, 1, 1));
            assertThat(f.matches(null)).isFalse();
        }

        @Test
        @DisplayName("filter(null) → empty list, без NPE")
        void filterNullReturnsEmpty() {
            MessageFilter f = new MessageFilter();
            assertThat(f.filter(null)).isEmpty();
        }

        @Test
        @DisplayName("filter(empty) → empty list (ранний выход)")
        void filterEmptyReturnsEmpty() {
            MessageFilter f = new MessageFilter();
            assertThat(f.filter(List.of())).isEmpty();
        }

        @Test
        @DisplayName("Невалидная дата в сообщении → matches=false (early exit по messageDate=null)")
        void invalidMessageDate() throws Exception {
            MessageFilter f = new MessageFilter().withStartDate(LocalDate.of(2024, 1, 1));
            JsonNode bad = json.readTree("{\"id\":1,\"type\":\"message\",\"date\":\"not-a-date\",\"text\":\"x\"}");
            assertThat(f.matches(bad)).isFalse();
        }

        @Test
        @DisplayName("fromParameters: startDate > endDate → IllegalArgumentException")
        void fromParametersInvalidRange() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                    MessageFilter.fromParameters(
                            LocalDate.of(2024, 12, 31), LocalDate.of(2024, 1, 1), null, null));
        }

        @Test
        @DisplayName("fromParameters: все null → null (нет фильтров)")
        void fromParametersAllNullReturnsNull() {
            assertThat(MessageFilter.fromParameters((LocalDate) null, null, null, null)).isNull();
            assertThat(MessageFilter.fromParameters((LocalDate) null, null, "  ", "")).isNull();
        }

        @Test
        @DisplayName("fromParameters(String): валидные ISO-даты парсятся")
        void fromParametersStringOverload() {
            MessageFilter f = MessageFilter.fromParameters("2024-01-01", "2024-12-31", "a,b", null);
            assertThat(f).isNotNull();
        }

        @Test
        @DisplayName("fromParameters(String): blank/null → null parsedStart/parsedEnd")
        void fromParametersStringBlankDates() {
            assertThat(MessageFilter.fromParameters("", "", null, null)).isNull();
        }

        @Test
        @DisplayName("matches: endDate-only фильтр (startDate=null branch)")
        void onlyEndDateFilter() throws Exception {
            MessageFilter f = new MessageFilter().withEndDate(LocalDate.of(2024, 6, 1));
            JsonNode before = json.readTree(
                    "{\"id\":1,\"type\":\"message\",\"date\":\"2024-01-15T10:00:00\",\"text\":\"x\"}");
            JsonNode after = json.readTree(
                    "{\"id\":2,\"type\":\"message\",\"date\":\"2024-12-01T10:00:00\",\"text\":\"x\"}");
            assertThat(f.matches(before)).isTrue();
            assertThat(f.matches(after)).isFalse();
        }

        @Test
        @DisplayName("includeTypes + excludeTypes комбинированно: type не в include → false")
        void includeAndExcludeTypesCombination() throws Exception {
            MessageFilter f = new MessageFilter()
                    .withIncludeType("message")
                    .withExcludeType("service");
            JsonNode svc = json.readTree(
                    "{\"id\":1,\"type\":\"service\",\"date\":\"2024-01-01T00:00:00\",\"text\":\"x\"}");
            JsonNode msg = json.readTree(
                    "{\"id\":2,\"type\":\"message\",\"date\":\"2024-01-01T00:00:00\",\"text\":\"x\"}");
            assertThat(f.matches(svc)).isFalse();   // не в include
            assertThat(f.matches(msg)).isTrue();
        }

        @Test
        @DisplayName("excludeKeywords-only (без include): сообщение с excluded словом → false")
        void onlyExcludeKeywords() throws Exception {
            MessageFilter f = new MessageFilter().withExcludeKeyword("spam");
            JsonNode bad = json.readTree(
                    "{\"id\":1,\"type\":\"message\",\"date\":\"2024-01-01T00:00:00\",\"text\":\"this is SPAM here\"}");
            JsonNode ok = json.readTree(
                    "{\"id\":2,\"type\":\"message\",\"date\":\"2024-01-01T00:00:00\",\"text\":\"clean message\"}");
            assertThat(f.matches(bad)).isFalse();
            assertThat(f.matches(ok)).isTrue();
        }

        @Test
        @DisplayName("customPredicate возвращает false → matches=false (loop break)")
        void customPredicateRejects() throws Exception {
            MessageFilter f = new MessageFilter()
                    .withPredicate(node -> node.get("id").asInt() > 100);
            JsonNode small = json.readTree(
                    "{\"id\":1,\"type\":\"message\",\"date\":\"2024-01-01T00:00:00\",\"text\":\"x\"}");
            assertThat(f.matches(small)).isFalse();
        }
    }

    // ─── ChatUpserter (6 BRANCH_MISSED) — чистые ветви input-fallback ────

    @Nested
    @DisplayName("ChatUpserter — input fallback branches")
    class ChatUpserterGaps {

        private com.tcleaner.dashboard.repository.ChatRepository repo;
        private ChatUpserter upserter;

        @BeforeEach
        void init() {
            repo = mock(com.tcleaner.dashboard.repository.ChatRepository.class);
            when(repo.save(any(Chat.class))).thenAnswer(inv -> inv.getArgument(0));
            upserter = new ChatUpserter(repo);
        }

        @Test
        @DisplayName("canonical null + chatIdRaw set → canonical=raw")
        void nullCanonicalFallsBackToRaw() {
            when(repo.findByCanonicalChatIdAndTopicId(eq("@x"), any())).thenReturn(Optional.empty());

            Chat chat = upserter.upsert(null, "@x", null, "T", Instant.parse("2024-01-01T00:00:00Z"));

            assertThat(chat.getCanonicalChatId()).isEqualTo("@x");
            assertThat(chat.getChatIdRaw()).isEqualTo("@x");
        }

        @Test
        @DisplayName("blank chatIdRaw + valid canonical → raw=canonical")
        void blankRawFallsBackToCanonical() {
            when(repo.findByCanonicalChatIdAndTopicId(eq("@y"), any())).thenReturn(Optional.empty());

            Chat chat = upserter.upsert("@y", "  ", null, null, Instant.parse("2024-01-01T00:00:00Z"));

            assertThat(chat.getChatIdRaw()).isEqualTo("@y");
            assertThat(chat.getCanonicalChatId()).isEqualTo("@y");
        }

        @Test
        @DisplayName("seenAt=null → ts=Instant.now() (lastSeen свежий)")
        void nullSeenAtUsesNow() {
            when(repo.findByCanonicalChatIdAndTopicId(any(), any())).thenReturn(Optional.empty());

            Instant before = Instant.now();
            Chat chat = upserter.upsert("@z", "@z", null, null, null);
            Instant after = Instant.now();

            assertThat(chat.getLastSeen()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        }

        @Test
        @DisplayName("Существующий чат с lastSeen=null → перезаписывается ts")
        void existingNullLastSeenIsUpdated() {
            Chat existing = Chat.builder()
                    .canonicalChatId("@e").chatIdRaw("@e")
                    .firstSeen(Instant.parse("2024-01-01T00:00:00Z"))
                    .lastSeen(null)   // явно null
                    .build();
            when(repo.findByCanonicalChatIdAndTopicId(eq("@e"), any())).thenReturn(Optional.of(existing));

            Instant ts = Instant.parse("2024-06-15T12:00:00Z");
            Chat chat = upserter.upsert("@e", "@e", null, null, ts);

            assertThat(chat.getLastSeen()).isEqualTo(ts);
        }

        @Test
        @DisplayName("Новый чат с blank title → chatTitle остаётся null (skip setter)")
        void newChatBlankTitleNotSet() {
            when(repo.findByCanonicalChatIdAndTopicId(eq("@bt"), any())).thenReturn(Optional.empty());

            Chat chat = upserter.upsert("@bt", "@bt", null, "  ", Instant.parse("2024-01-01T00:00:00Z"));

            assertThat(chat.getChatTitle()).isNull();
        }

        @Test
        @DisplayName("Новый чат с null title → chatTitle остаётся null")
        void newChatNullTitleNotSet() {
            when(repo.findByCanonicalChatIdAndTopicId(eq("@nt"), any())).thenReturn(Optional.empty());

            Chat chat = upserter.upsert("@nt", "@nt", null, null, Instant.parse("2024-01-01T00:00:00Z"));

            assertThat(chat.getChatTitle()).isNull();
        }

        @Test
        @DisplayName("Existing chat: новый ts старее → lastSeen не обновляется")
        void olderTsDoesNotRegressLastSeen() {
            Instant newer = Instant.parse("2024-06-15T12:00:00Z");
            Chat existing = Chat.builder()
                    .canonicalChatId("@o").chatIdRaw("@o")
                    .firstSeen(Instant.parse("2024-01-01T00:00:00Z"))
                    .lastSeen(newer)
                    .build();
            when(repo.findByCanonicalChatIdAndTopicId(eq("@o"), any())).thenReturn(Optional.of(existing));

            Instant older = Instant.parse("2024-03-01T12:00:00Z");
            Chat chat = upserter.upsert("@o", "@o", null, null, older);

            assertThat(chat.getLastSeen()).isEqualTo(newer);
        }
    }

    // ─── CacheMetricsService — gap branches (heatmap getOrDefault default values, …) ──

    @Nested
    @DisplayName("CacheMetricsService — branch gaps")
    class CacheMetricsGaps {

        private StringRedisTemplate redis;
        private ValueOperations<String, String> ops;
        private ChatRepository chatRepo;
        private CacheMetricsService svc;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void init() {
            redis = mock(StringRedisTemplate.class);
            ops = mock(ValueOperations.class);
            chatRepo = mock(ChatRepository.class);
            when(redis.opsForValue()).thenReturn(ops);
            svc = new CacheMetricsService(redis, chatRepo, new ObjectMapper());
        }

        @Test
        @DisplayName("blank snapshot string (одни пробелы) → unavailable")
        void blankSnapshotUnavailable() {
            when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn("   ");

            var dto = svc.get();
            assertThat(dto.available()).isFalse();
        }

        @Test
        @DisplayName("heatmap bucket БЕЗ chat_count/size_bytes → defaults=0 (getOrDefault branch)")
        void heatmapMissingKeysDefaultsToZero() {
            String json = """
                    {"used_bytes":0,"limit_bytes":0,"pct":0,"total_chats":0,"total_messages":0,
                     "generated_at":0,"top_chats":[],
                     "heatmap":{"empty_bucket":{}}}""";
            when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);

            var dto = svc.get();
            assertThat(dto.heatmap()).hasSize(1);
            assertThat(dto.heatmap().get(0).chatCount()).isZero();
            assertThat(dto.heatmap().get(0).sizeBytes()).isZero();
        }

        @Test
        @DisplayName("redisType явно задан (non-blank) → перекрывает DB chat_type")
        void redisTypeOverridesDbType() {
            String json = """
                    {"used_bytes":0,"limit_bytes":0,"pct":0,"total_chats":0,"total_messages":0,
                     "generated_at":0,
                     "top_chats":[{"chat_id":-1001,"topic_id":0,"msg_count":5,
                                   "size_bytes":100,"last_accessed":0,"pct":100.0}],
                     "heatmap":{}}""";
            when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
            when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList(null, "redis_type"));
            when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of(
                    Chat.builder().canonicalChatId("-1001").topicId(null)
                            .chatTitle("X").chatType("db_type").build()));

            var dto = svc.get();
            assertThat(dto.topChats().get(0).chatType()).isEqualTo("redis_type");
        }

        @Test
        @DisplayName("username = только цифры (без минуса) → null (numeric-id-like)")
        void allDigitUsernameRejected() {
            String json = """
                    {"used_bytes":0,"limit_bytes":0,"pct":0,"total_chats":0,"total_messages":0,
                     "generated_at":0,
                     "top_chats":[{"chat_id":-1001,"topic_id":0,"msg_count":5,
                                   "size_bytes":100,"last_accessed":0,"pct":100.0}],
                     "heatmap":{}}""";
            when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
            when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList("12345", null));
            when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of());

            var dto = svc.get();
            assertThat(dto.topChats().get(0).username()).isNull();
        }

        @Test
        @DisplayName("Валидный username (буквы) — НЕ отбрасывается (positive branch)")
        void validUsernameAccepted() {
            String json = """
                    {"used_bytes":0,"limit_bytes":0,"pct":0,"total_chats":0,"total_messages":0,
                     "generated_at":0,
                     "top_chats":[{"chat_id":-1001,"topic_id":0,"msg_count":5,
                                   "size_bytes":100,"last_accessed":0,"pct":100.0}],
                     "heatmap":{}}""";
            when(ops.get(CacheMetricsService.SNAPSHOT_KEY)).thenReturn(json);
            when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList("realuser", null));
            when(chatRepo.findAllByCanonicalChatIdIn(anyCollection())).thenReturn(List.of());

            var dto = svc.get();
            assertThat(dto.topChats().get(0).username()).isEqualTo("realuser");
        }
    }
}
