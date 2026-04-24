package com.tcleaner.dashboard.service.stats;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ExportEvent;
import com.tcleaner.dashboard.domain.ExportSource;
import com.tcleaner.dashboard.domain.ExportStatus;
import com.tcleaner.dashboard.dto.ChatStatsRow;
import com.tcleaner.dashboard.dto.OverviewDto;
import com.tcleaner.dashboard.dto.TimeSeriesPointDto;
import com.tcleaner.dashboard.dto.UserDetailDto;
import com.tcleaner.dashboard.dto.UserStatsRow;
import com.tcleaner.dashboard.repository.BotUserRepository;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ExportEventRepository;
import com.tcleaner.dashboard.service.stats.StatsPeriod.Granularity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты для {@link StatsQueryService}: засеиваем БД через JPA,
 * затем вызываем нативные SQL-запросы сервиса и проверяем агрегации.
 */
@SpringBootTest
@Transactional
@DisplayName("StatsQueryService")
class StatsQueryServiceTest {

    @Autowired
    private StatsQueryService svc;

    @Autowired
    private BotUserRepository botUserRepo;

    @Autowired
    private ChatRepository chatRepo;

    @Autowired
    private ExportEventRepository eventRepo;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private TelegramExporter mockExporter;

    // Тестовый период: 2026-04-08 .. 2026-04-15 (покрывает все тестовые события)
    private static final StatsPeriod PERIOD = new StatsPeriod(
            LocalDate.of(2026, 4, 8),
            LocalDate.of(2026, 4, 15),
            Granularity.DAY);

    private Long chatId;

    @BeforeEach
    void seed() {
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());

        Instant now = Instant.parse("2026-04-15T00:00:00Z");

        // Два бот-юзера
        botUserRepo.save(BotUser.builder()
                .botUserId(1L).username("alice").displayName("Alice")
                .firstSeen(Instant.parse("2026-01-01T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T12:00:00Z"))
                .totalExports(2).totalMessages(300L).totalBytes(3000L).build());

        botUserRepo.save(BotUser.builder()
                .botUserId(2L).username("bob").displayName("Bob")
                .firstSeen(Instant.parse("2026-02-01T00:00:00Z"))
                .lastSeen(Instant.parse("2026-04-14T15:00:00Z"))
                .totalExports(1).totalMessages(50L).totalBytes(500L).build());

        // Один чат
        Chat chat = chatRepo.save(Chat.builder()
                .canonicalChatId("-100chat1").chatIdRaw("@chat1")
                .chatTitle("Test Chat")
                .firstSeen(now).lastSeen(now).build());
        chatId = chat.getId();

        // Три экспорта в периоде
        eventRepo.save(makeEvent("t1", 1L, chatId,
                Instant.parse("2026-04-10T12:00:00Z"), ExportStatus.COMPLETED, 100L, 1000L));
        eventRepo.save(makeEvent("t2", 1L, chatId,
                Instant.parse("2026-04-12T12:00:00Z"), ExportStatus.COMPLETED, 200L, 2000L));
        eventRepo.save(makeEvent("t3", 2L, chatId,
                Instant.parse("2026-04-14T12:00:00Z"), ExportStatus.FAILED, 50L, 500L));

        // Один старый экспорт вне периода — не должен попадать в агрегации
        eventRepo.save(makeEvent("t0", 1L, chatId,
                Instant.parse("2026-01-01T12:00:00Z"), ExportStatus.COMPLETED, 9999L, 9999L));
    }

    // ─── overview ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("overview без фильтра: суммирует только события в периоде")
    void overviewAllUsers() {
        OverviewDto dto = svc.overview(PERIOD, null);

        assertThat(dto.totalExports()).isEqualTo(3);
        assertThat(dto.totalMessages()).isEqualTo(350L);   // 100+200+50
        assertThat(dto.totalBytes()).isEqualTo(3500L);     // 1000+2000+500
        assertThat(dto.totalUsers()).isEqualTo(2);         // 2 distinct активных юзера в периоде
    }

    @Test
    @DisplayName("overview с фильтром по botUserId=1: только события alice")
    void overviewSingleUser() {
        OverviewDto dto = svc.overview(PERIOD, 1L);

        assertThat(dto.totalExports()).isEqualTo(2);
        assertThat(dto.totalMessages()).isEqualTo(300L);
        assertThat(dto.totalBytes()).isEqualTo(3000L);
    }

    // ─── overviewWithDelta ───────────────────────────────────────────────────

    @Test
    @DisplayName("overviewWithDelta: previous period пустой → delta=null для всех метрик")
    void overviewWithDeltaNullWhenPreviousEmpty() {
        OverviewDto dto = svc.overviewWithDelta(PERIOD, null);

        assertThat(dto.totalExports()).isEqualTo(3);
        assertThat(dto.totalMessages()).isEqualTo(350L);
        assertThat(dto.totalBytes()).isEqualTo(3500L);
        assertThat(dto.deltaExports()).isNull();
        assertThat(dto.deltaMessages()).isNull();
        assertThat(dto.deltaBytes()).isNull();
        assertThat(dto.deltaUsers()).isNull();
    }

    @Test
    @DisplayName("overviewWithDelta: положительный рост → delta > 0")
    void overviewWithDeltaPositiveDelta() {
        // Добавим 1 экспорт в previous period (2026-03-31 .. 2026-04-07)
        eventRepo.save(makeEvent("prev1", 1L, chatId,
                Instant.parse("2026-04-05T12:00:00Z"),
                ExportStatus.COMPLETED, 100L, 1000L));

        OverviewDto dto = svc.overviewWithDelta(PERIOD, null);

        // current = 3 экспорта (350 msg, 3500 b), previous = 1 экспорт (100 msg, 1000 b)
        assertThat(dto.totalExports()).isEqualTo(3);
        assertThat(dto.totalMessages()).isEqualTo(350L);
        assertThat(dto.totalBytes()).isEqualTo(3500L);
        assertThat(dto.deltaExports()).isEqualTo(200.0);  // (3-1)/1*100
        assertThat(dto.deltaMessages()).isEqualTo(250.0); // (350-100)/100*100
        assertThat(dto.deltaBytes()).isEqualTo(250.0);    // (3500-1000)/1000*100
        // current: 2 активных юзера (1,2), prev: 1 (юзер 1 из prev1) → (2-1)/1*100
        assertThat(dto.deltaUsers()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("overviewWithDelta: падение (current < previous) → delta отрицательная")
    void overviewWithDeltaNegativeDelta() {
        // Добавим 5 экспортов в previous period (2026-03-31 .. 2026-04-07)
        // current период уже имеет 3 экспорта из seed()
        for (int i = 0; i < 5; i++) {
            eventRepo.save(makeEvent("prev" + i, 1L, chatId,
                    Instant.parse("2026-04-0" + (i + 1) + "T12:00:00Z"),
                    ExportStatus.COMPLETED, 100L, 1000L));
        }

        OverviewDto dto = svc.overviewWithDelta(PERIOD, null);

        // current=3, previous=5 → (3-5)/5*100 = -40.0
        assertThat(dto.totalExports()).isEqualTo(3);
        assertThat(dto.deltaExports()).isEqualTo(-40.0);
        assertThat(dto.deltaMessages()).isNegative();
        assertThat(dto.deltaBytes()).isNegative();
    }

    // ─── topUsers ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("topUsers без фильтра: alice первая (totalExports=2)")
    void topUsersOrdered() {
        List<UserStatsRow> rows = svc.topUsers(10, null);

        assertThat(rows).hasSizeGreaterThanOrEqualTo(2);
        assertThat(rows.get(0).botUserId()).isEqualTo(1L);   // alice
        assertThat(rows.get(0).username()).isEqualTo("alice");
    }

    @Test
    @DisplayName("topUsers с фильтром по botUserId=2: только bob")
    void topUsersFilteredToSingleUser() {
        List<UserStatsRow> rows = svc.topUsers(10, 2L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).botUserId()).isEqualTo(2L);
        assertThat(rows.get(0).username()).isEqualTo("bob");
    }

    // ─── topChats ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("topChats: один чат с 3 экспортами в периоде")
    void topChatsAllUsers() {
        List<ChatStatsRow> rows = svc.topChats(PERIOD, null, 10);

        assertThat(rows).hasSize(1);
        ChatStatsRow row = rows.get(0);
        assertThat(row.exportCount()).isEqualTo(3);
        assertThat(row.totalBytes()).isEqualTo(3500L);
        assertThat(row.chatTitle()).isEqualTo("Test Chat");
    }

    @Test
    @DisplayName("topChats для botUserId=1: 2 экспорта в периоде")
    void topChatsForUser1() {
        List<ChatStatsRow> rows = svc.topChats(PERIOD, 1L, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).exportCount()).isEqualTo(2);
        assertThat(rows.get(0).totalBytes()).isEqualTo(3000L);
    }

    // ─── statusBreakdown ─────────────────────────────────────────────────────

    @Test
    @DisplayName("statusBreakdown: COMPLETED=2, FAILED=1")
    void statusBreakdown() {
        Map<String, Long> bd = svc.statusBreakdown(PERIOD, null);

        assertThat(bd).containsEntry("COMPLETED", 2L)
                       .containsEntry("FAILED", 1L);
    }

    @Test
    @DisplayName("statusBreakdown для botUserId=1: только COMPLETED")
    void statusBreakdownForUser1() {
        Map<String, Long> bd = svc.statusBreakdown(PERIOD, 1L);

        assertThat(bd).containsEntry("COMPLETED", 2L);
        assertThat(bd).doesNotContainKey("FAILED");
    }

    // ─── timeSeries ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("timeSeries metric=exports (DAY): 3 точки по разным датам")
    void timeSeriesExports() {
        List<TimeSeriesPointDto> pts = svc.timeSeries(PERIOD, "exports", null);

        // 3 события в разные дни: 2026-04-10, 2026-04-12, 2026-04-14
        assertThat(pts).hasSize(3);
        assertThat(pts).extracting(TimeSeriesPointDto::value)
                .containsExactly(1L, 1L, 1L);
        assertThat(pts.get(0).period()).isEqualTo("2026-04-10");
    }

    @Test
    @DisplayName("timeSeries metric=bytes: суммирует bytes_count по бакету")
    void timeSeriesBytes() {
        List<TimeSeriesPointDto> pts = svc.timeSeries(PERIOD, "bytes", null);

        assertThat(pts).hasSize(3);
        // t1: 1000, t2: 2000, t3: 500
        assertThat(pts).extracting(TimeSeriesPointDto::value)
                .containsExactly(1000L, 2000L, 500L);
    }

    @Test
    @DisplayName("timeSeries metric=messages для botUserId=1: 2 точки")
    void timeSeriesMessagesForUser1() {
        List<TimeSeriesPointDto> pts = svc.timeSeries(PERIOD, "messages", 1L);

        assertThat(pts).hasSize(2);
        assertThat(pts).extracting(TimeSeriesPointDto::value)
                .containsExactly(100L, 200L);
    }

    // ─── recentEvents ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "status=\"{0}\" → expectedSize={1}")
    @CsvSource({
            "completed, 3",
            "cOmPlEtEd, 3",
            "COMPLETED, 3",
            "failed,    1",
            "garbage,   0",
    })
    @DisplayName("recentEvents: case-insensitive status фильтр + invalid → пустой список")
    void recentEventsStatusFilteringVariants(String status, int expectedSize) {
        List<com.tcleaner.dashboard.dto.EventRowDto> rows = svc.recentEvents(null, null, status, 10);

        assertThat(rows).hasSize(expectedSize);
        if (expectedSize > 0) {
            assertThat(rows).extracting(com.tcleaner.dashboard.dto.EventRowDto::status)
                    .containsOnly(status.toUpperCase(java.util.Locale.ROOT));
        }
    }

    // ─── userDetail ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("userDetail для botUserId=1: все поля корректны")
    void userDetail() {
        UserDetailDto dto = svc.userDetail(1L);

        assertThat(dto.botUserId()).isEqualTo(1L);
        assertThat(dto.username()).isEqualTo("alice");
        assertThat(dto.displayName()).isEqualTo("Alice");
        assertThat(dto.totalExports()).isEqualTo(2);
        assertThat(dto.totalMessages()).isEqualTo(300L);
        assertThat(dto.totalBytes()).isEqualTo(3000L);
        assertThat(dto.firstSeen()).isNotNull();
        assertThat(dto.lastSeen()).isNotNull();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ExportEvent makeEvent(String taskId, long botUserId, long chatRefId,
                                  Instant startedAt, ExportStatus status,
                                  Long messages, Long bytes) {
        return ExportEvent.builder()
                .taskId(taskId)
                .botUserId(botUserId)
                .chatRefId(chatRefId)
                .startedAt(startedAt)
                .status(status)
                .messagesCount(messages)
                .bytesCount(bytes)
                .source(ExportSource.BOT)
                .createdAt(startedAt)
                .updatedAt(startedAt)
                .build();
    }
}
