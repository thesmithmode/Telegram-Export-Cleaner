package com.tcleaner.dashboard.service.stats;

import com.tcleaner.dashboard.domain.ExportStatus;
import com.tcleaner.dashboard.dto.ChatStatsRow;
import com.tcleaner.dashboard.dto.EventRowDto;
import com.tcleaner.dashboard.dto.OverviewDto;
import com.tcleaner.dashboard.dto.TimeSeriesPointDto;
import com.tcleaner.dashboard.dto.UserDetailDto;
import com.tcleaner.dashboard.dto.UserStatsRow;
import com.tcleaner.dashboard.util.PaginationUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tcleaner.dashboard.config.CacheConfig.HISTORICAL;
import static com.tcleaner.dashboard.config.CacheConfig.LIVE;
import static com.tcleaner.dashboard.config.CacheConfig.PROFILE;

/**
 * Читающая сторона дашборда: агрегации через native SQL (SQLite + strftime-bucket'ы).
 * Все методы read-only — транзакция не открывается; JdbcTemplate использует DataSource
 * бин из {@code DashboardDataSourceConfig}.
 */
@Service
public class StatsQueryService {

    private final JdbcTemplate jdbc;
    // Self-reference через Spring proxy — без этого nested вызовы (overview → topUsers)
    // игнорируют @Cacheable advice и бьют SQL на каждый запрос. @Lazy ломает цикл бинов.
    private final StatsQueryService self;

    public StatsQueryService(JdbcTemplate jdbc, @Lazy @Autowired StatsQueryService self) {
        this.jdbc = jdbc;
        this.self = self;
    }

    // Единая точка фильтрации: склеивает SQL-фрагмент bot_user_id и добавляет параметр,
    // устраняя if/else-дубликаты во всех топ-методах.
    private static boolean byUser(Long botUserId) {
        return botUserId != null && botUserId > 0;
    }

    @Cacheable(value = LIVE, key = "#period.toString() + '_' + #botUserId")
    public OverviewDto overview(StatsPeriod period, Long botUserId) {
        long[] totals = periodTotals(period, botUserId);
        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM bot_users", Long.class);

        return new OverviewDto(
                totals[0], totals[1], totals[2],
                users != null ? users : 0,
                self.topUsers(10, botUserId),
                self.topChats(period, botUserId, 10),
                self.statusBreakdown(period, botUserId),
                null, null, null, null);
    }

    /**
     * Три агрегата (exports, messages, bytes) за период — без top/breakdown.
     */
    private long[] periodTotals(StatsPeriod period, Long botUserId) {
        String from = period.fromSql();
        String to = period.toSql();
        String sql = "SELECT COUNT(*) AS exports, "
                + "COALESCE(SUM(messages_count), 0) AS messages, "
                + "COALESCE(SUM(bytes_count), 0) AS bytes "
                + "FROM export_events WHERE started_at >= ? AND started_at <= ?"
                + (byUser(botUserId) ? " AND bot_user_id = ?" : "");
        Object[] args = byUser(botUserId)
                ? new Object[]{from, to, botUserId}
                : new Object[]{from, to};
        Long[] result = jdbc.queryForObject(sql,
                (rs, n) -> new Long[]{
                        rs.getLong("exports"),
                        rs.getLong("messages"),
                        rs.getLong("bytes")},
                args);
        return result != null
                ? new long[]{result[0], result[1], result[2]}
                : new long[]{0, 0, 0};
    }

    /**
     * Overview + дельта vs предыдущий период той же длины.
     * Delta = ((current - prev) / prev) * 100. prev==0 → null.
     */
    @Cacheable(value = LIVE, key = "'wd_' + #period.toString() + '_' + #botUserId")
    public OverviewDto overviewWithDelta(StatsPeriod period, Long botUserId) {
        long[] current = periodTotals(period, botUserId);
        long[] prev = periodTotals(period.previous(), botUserId);
        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM bot_users", Long.class);
        return new OverviewDto(
                current[0], current[1], current[2],
                users != null ? users : 0,
                self.topUsers(10, botUserId),
                self.topChats(period, botUserId, 10),
                self.statusBreakdown(period, botUserId),
                computeDeltaPercent(current[0], prev[0]),
                computeDeltaPercent(current[1], prev[1]),
                computeDeltaPercent(current[2], prev[2]),
                null);
    }

    private static Double computeDeltaPercent(long current, long previous) {
        if (previous == 0) {
            return null;
        }
        return ((double) (current - previous) / previous) * 100.0;
    }

    @Cacheable(value = HISTORICAL, key = "#limit + '_' + #botUserId")
    public List<UserStatsRow> topUsers(int limit, Long botUserId) {
        String base = "SELECT bot_user_id, username, display_name, total_exports, "
                + "total_messages, total_bytes, last_seen FROM bot_users ";
        if (byUser(botUserId)) {
            return jdbc.query(base + "WHERE bot_user_id = ? LIMIT ?",
                    userStatsMapper(), botUserId, limit);
        }
        return jdbc.query(base + "ORDER BY total_exports DESC LIMIT ?",
                userStatsMapper(), limit);
    }

    @Cacheable(value = HISTORICAL, key = "#period.toString() + '_users_' + #limit + '_' + #botUserId")
    public List<UserStatsRow> topUsersByPeriod(StatsPeriod period, int limit, Long botUserId) {
        String from = period.fromSql();
        String to = period.toSql();
        String sql = "SELECT e.bot_user_id, u.username, u.display_name, "
                + "COUNT(*) AS total_exports, "
                + "COALESCE(SUM(e.messages_count), 0) AS total_messages, "
                + "COALESCE(SUM(e.bytes_count), 0) AS total_bytes, "
                + "MAX(e.started_at) AS last_seen "
                + "FROM export_events e LEFT JOIN bot_users u ON e.bot_user_id = u.bot_user_id "
                + "WHERE e.started_at >= ? AND e.started_at <= ? "
                + (byUser(botUserId) ? "AND e.bot_user_id = ? " : "")
                + "GROUP BY e.bot_user_id ORDER BY total_exports DESC LIMIT ?";
        Object[] args = byUser(botUserId)
                ? new Object[]{from, to, botUserId, PaginationUtils.clamp(limit, 500)}
                : new Object[]{from, to, PaginationUtils.clamp(limit, 500)};
        return jdbc.query(sql, userStatsMapper(), args);
    }

    private static org.springframework.jdbc.core.RowMapper<UserStatsRow> userStatsMapper() {
        return (rs, n) -> new UserStatsRow(
                rs.getLong("bot_user_id"), rs.getString("username"),
                rs.getString("display_name"), rs.getInt("total_exports"),
                rs.getLong("total_messages"), rs.getLong("total_bytes"),
                rs.getString("last_seen"));
    }

    @Cacheable(value = HISTORICAL, key = "#period.toString() + '_' + #botUserId + '_' + #limit")
    public List<ChatStatsRow> topChats(StatsPeriod period, Long botUserId, int limit) {
        String from = period.fromSql();
        String to = period.toSql();
        String sql = "SELECT e.chat_ref_id, c.canonical_chat_id, c.chat_title, "
                + "COUNT(*) AS export_count, "
                + "COALESCE(SUM(e.messages_count), 0) AS total_messages, "
                + "COALESCE(SUM(e.bytes_count), 0) AS total_bytes "
                + "FROM export_events e LEFT JOIN chats c ON e.chat_ref_id = c.id "
                + "WHERE e.started_at >= ? AND e.started_at <= ? "
                + (byUser(botUserId) ? "AND e.bot_user_id = ? " : "")
                + "GROUP BY e.chat_ref_id ORDER BY total_bytes DESC LIMIT ?";
        Object[] args = byUser(botUserId)
                ? new Object[]{from, to, botUserId, limit}
                : new Object[]{from, to, limit};
        return jdbc.query(sql,
                (rs, n) -> new ChatStatsRow(
                        rs.getLong("chat_ref_id"), rs.getString("canonical_chat_id"),
                        rs.getString("chat_title"), rs.getLong("export_count"),
                        rs.getLong("total_messages"), rs.getLong("total_bytes")),
                args);
    }

    @Cacheable(value = HISTORICAL, key = "#period.toString() + '_' + #botUserId")
    public Map<String, Long> statusBreakdown(StatsPeriod period, Long botUserId) {
        String from = period.fromSql();
        String to = period.toSql();
        String sql = "SELECT status, COUNT(*) AS cnt FROM export_events "
                + "WHERE started_at >= ? AND started_at <= ? "
                + (byUser(botUserId) ? "AND bot_user_id = ? " : "")
                + "GROUP BY status";
        Object[] args = byUser(botUserId)
                ? new Object[]{from, to, botUserId}
                : new Object[]{from, to};
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(
                    (String) row.get("status"),
                    ((Number) row.get("cnt")).longValue());
        }
        return result;
    }

    @Cacheable(value = HISTORICAL, key = "#period.toString() + '_' + #metric + '_' + #botUserId")
    public List<TimeSeriesPointDto> timeSeries(StatsPeriod period, String metric, Long botUserId) {
        String fmt = period.strftimeFormat();
        String from = period.fromSql();
        String to = period.toSql();
        // aggregate и fmt — whitelist через switch/enum, не пользовательский ввод.
        String aggregate = switch (metric == null ? "exports" : metric) {
            case "messages" -> "COALESCE(SUM(messages_count), 0)";
            case "bytes" -> "COALESCE(SUM(bytes_count), 0)";
            default -> "COUNT(*)";
        };
        String groupBucket = "strftime('" + fmt + "', started_at)";
        String sql = "SELECT " + groupBucket + " AS period, " + aggregate + " AS value "
                + "FROM export_events WHERE started_at >= ? AND started_at <= ? "
                + (byUser(botUserId) ? "AND bot_user_id = ? " : "")
                + "GROUP BY period ORDER BY period";
        Object[] args = byUser(botUserId)
                ? new Object[]{from, to, botUserId}
                : new Object[]{from, to};
        return jdbc.query(sql,
                (rs, n) -> new TimeSeriesPointDto(rs.getString("period"), rs.getLong("value")),
                args);
    }

    /**
     * Последние N событий с опциональными фильтрами. Чувствительно к RBAC —
     * контроллер обязан передать эффективный botUserId (0 = «все», только ADMIN).
     */
    @Cacheable(value = LIVE, key = "#botUserId + '_' + #chatRefId + '_' + #status + '_' + #limit")
    public List<EventRowDto> recentEvents(Long botUserId, Long chatRefId,
                                          String status, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT e.task_id, e.bot_user_id, u.username, "
                + "c.chat_title, c.canonical_chat_id, "
                + "e.started_at, e.finished_at, e.status, "
                + "e.messages_count, e.bytes_count, e.source, e.error_message "
                + "FROM export_events e "
                + "LEFT JOIN bot_users u ON e.bot_user_id = u.bot_user_id "
                + "LEFT JOIN chats c ON e.chat_ref_id = c.id "
                + "WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (byUser(botUserId)) {
            sql.append("AND e.bot_user_id = ? ");
            args.add(botUserId);
        }
        if (chatRefId != null && chatRefId > 0) {
            sql.append("AND e.chat_ref_id = ? ");
            args.add(chatRefId);
        }
        if (status != null && !status.isBlank()) {
            try {
                ExportStatus normalized = ExportStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
                sql.append("AND e.status = ? ");
                args.add(normalized.name());
            } catch (IllegalArgumentException ex) {
                return java.util.List.of();
            }
        }
        sql.append("ORDER BY e.started_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));

        return jdbc.query(sql.toString(),
                (rs, n) -> new EventRowDto(
                        rs.getString("task_id"), rs.getLong("bot_user_id"),
                        rs.getString("username"), rs.getString("chat_title"),
                        rs.getString("canonical_chat_id"),
                        toIso(rs.getString("started_at")), toIso(rs.getString("finished_at")),
                        rs.getString("status"),
                        nullableLong(rs.getObject("messages_count")),
                        nullableLong(rs.getObject("bytes_count")),
                        rs.getString("source"), rs.getString("error_message")),
                args.toArray());
    }

    private static Long nullableLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    /** SQLite "YYYY-MM-DD HH:MM:SS.sss" → ISO-8601 "YYYY-MM-DDTHH:MM:SS.sssZ" для JS Date. */
    private static String toIso(String sqlite) {
        if (sqlite == null) return null;
        return sqlite.replace(' ', 'T') + "Z";
    }

    @Cacheable(value = PROFILE, key = "#botUserId")
    public UserDetailDto userDetail(long botUserId) {
        return jdbc.queryForObject(
                "SELECT bot_user_id, username, display_name, total_exports, "
                + "total_messages, total_bytes, first_seen, last_seen "
                + "FROM bot_users WHERE bot_user_id = ?",
                (rs, n) -> new UserDetailDto(
                        rs.getLong("bot_user_id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getInt("total_exports"),
                        rs.getLong("total_messages"), rs.getLong("total_bytes"),
                        rs.getString("first_seen"), rs.getString("last_seen")),
                botUserId);
    }
}
