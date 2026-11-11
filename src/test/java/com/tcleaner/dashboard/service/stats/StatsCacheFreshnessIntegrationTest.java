package com.tcleaner.dashboard.service.stats;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.dto.TimeSeriesPointDto;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.service.ingestion.ExportEventIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StatsCacheFreshnessIntegrationTest {
    @Autowired private StatsQueryService stats;
    @Autowired private ExportEventIngestionService ingestion;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CacheManager caches;
    @MockitoBean private TelegramExporter exporter;

    @Test
    void cachedZeroRefreshesAfterCommittedExportAndLateMetricsWithoutWaitingForTtl() {
        long user = 9_100_000_001L;
        String task = "cache-freshness-" + UUID.randomUUID();
        String chat = "@" + task;
        StatsPeriod period = new StatsPeriod(LocalDate.now().minusDays(1), LocalDate.now(),
                StatsPeriod.Granularity.DAY);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        try {
            assertThat(total(stats.timeSeries(period, "exports", user))).isZero();
            long globalBefore = total(stats.timeSeries(period, "exports", null));
            List<TimeSeriesPointDto> otherUser = stats.timeSeries(period, "exports", user + 1);
            transaction.executeWithoutResult(status -> ingestion.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_STARTED).taskId(task).botUserId(user)
                    .chatIdRaw(chat).ts(Instant.now()).build()));
            assertThat(total(stats.timeSeries(period, "exports", user))).isEqualTo(1);
            assertThat(total(stats.timeSeries(period, "exports", null))).isEqualTo(globalBefore + 1);
            assertThat(stats.timeSeries(period, "exports", user + 1)).isSameAs(otherUser);

            assertThat(total(stats.timeSeries(period, "bytes", user))).isZero();
            // Late metrics omit bot_user_id: invalidate using the persisted event owner.
            transaction.executeWithoutResult(status -> ingestion.ingest(StatsEventPayload.builder()
                    .type(StatsEventType.EXPORT_COMPLETED).taskId(task).bytesCount(64L)
                    .ts(Instant.now()).build()));
            assertThat(total(stats.timeSeries(period, "bytes", user))).isEqualTo(64);

            List<TimeSeriesPointDto> beforeRollback = stats.timeSeries(period, "bytes", user);
            transaction.executeWithoutResult(status -> {
                ingestion.ingest(StatsEventPayload.builder().type(StatsEventType.EXPORT_BYTES_MEASURED)
                        .taskId(task).bytesCount(128L).ts(Instant.now()).build());
                status.setRollbackOnly();
            });
            assertThat(stats.timeSeries(period, "bytes", user)).isSameAs(beforeRollback);
            assertThat(total(beforeRollback)).isEqualTo(64);
        } finally {
            jdbc.update("DELETE FROM export_events WHERE task_id = ?", task);
            jdbc.update("DELETE FROM bot_users WHERE bot_user_id = ?", user);
            jdbc.update("DELETE FROM chats WHERE canonical_chat_id = ?", chat);
            caches.getCache(com.tcleaner.dashboard.config.CacheConfig.HISTORICAL).clear();
        }
    }

    private static long total(List<TimeSeriesPointDto> points) {
        return points.stream().mapToLong(TimeSeriesPointDto::value).sum();
    }
}
