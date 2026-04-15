package com.tcleaner.dashboard.service.stats;

import com.tcleaner.dashboard.dto.ChatStatsRow;
import com.tcleaner.dashboard.dto.OverviewDto;
import com.tcleaner.dashboard.dto.TimeSeriesPointDto;
import com.tcleaner.dashboard.dto.UserDetailDto;
import com.tcleaner.dashboard.dto.UserStatsRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Читающая сторона дашборда: агрегации через native SQL (SQLite + strftime-bucket'ы).
 * Все методы read-only — транзакция не открывается; JdbcTemplate использует DataSource
 * бин из {@code DashboardDataSourceConfig}.
 */
@Service
public class StatsQueryService {

    private final JdbcTemplate jdbc;

    public StatsQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─── Overview ────────────────────────────────────────────────────────────

    public OverviewDto overview(StatsPeriod period, Long botUserId) {
        String from = period.from().toString();
        String to = period.to().toString() + "T23:59:59Z";

        String baseWhere = botUserId != null && botUserId > 0
                ? " WHERE started_at >= ? AND started_at <= ? AND bot_user_id = ?"
                : " WHERE started_at >= ? AND started_at <= ?";
        Object[] args = botUserId != null && botUserId > 0
                ? new Object[]{from, to, botUserId}
                : new Object[]{from, to};

        Long exports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM export_events" + baseWhere, Long.class, args);
        Long messages = jdbc.queryForObject(
                "SELECT COALESCE(SUM(messages_count), 0) FROM export_events" + baseWhere, Long.class, args);
        Long bytes = jdbc.queryForObject(
                "SELECT COALESCE(SUM(bytes_count), 0) FROM export_events" + baseWhere, Long.class, args);
        Long users = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bot_users", Long.class);

        List<UserStatsRow> topUsers = topUsers(10, botUserId);
        List<ChatStatsRow> topChats = topChats(period, botUserId, 10);
        Map<String, Long> breakdown = statusBreakdown(period, botUserId);

        return new OverviewDto(
                exports != null ? exports : 0,
                messages != null ? messages : 0,
                bytes != null ? bytes : 0,
                users != null ? users : 0,
                topUsers, topChats, breakdown);
    }

    // ─── Users ───────────────────────────────────────────────────────────────

    public List<UserStatsRow> topUsers(int limit, Long botUserId) {
        if (botUserId != null && botUserId > 0) {
            return jdbc.query(
                    "SELECT bot_user_id, username, display_name, total_exports, " +
                    "total_messages, total_bytes, last_seen FROM bot_users " +
                    "WHERE bot_user_id = ? LIMIT ?",
                    (rs, n) -> new UserStatsRow(
                            rs.getLong("bot_user_id"), rs.getString("username"),
                            rs.getString("display_name"), rs.getInt("total_exports"),
                            rs.getLong("total_messages"), rs.getLong("total_bytes"),
                            rs.getString("last_seen")),
                    botUserId, limit);
        }
        return jdbc.query(
                "SELECT bot_user_id, username, display_name, total_exports, " +
                "total_messages, total_bytes, last_seen FROM bot_users " +
                "ORDER BY total_exports DESC LIMIT ?",
                (rs, n) -> new UserStatsRow(
                        rs.getLong("bot_user_id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getInt("total_exports"),
                        rs.getLong("total_messages"), rs.getLong("total_bytes"),
                        rs.getString("last_seen")),
                limit);
    }

    // ─── Chats ───────────────────────────────────────────────────────────────

    public List<ChatStatsRow> topChats(StatsPeriod period, Long botUserId, int limit) {
        String from = period.from().toString();
        String to = period.to().toString() + "T23:59:59Z";
        if (botUserId != null && botUserId > 0) {
            return jdbc.query(
                    "SELECT e.chat_ref_id, c.canonical_chat_id, c.chat_title, " +
                    "COUNT(*) AS export_count, " +
                    "COALESCE(SUM(e.messages_count), 0) AS total_messages, " +
                    "COALESCE(SUM(e.bytes_count), 0) AS total_bytes " +
                    "FROM export_events e LEFT JOIN chats c ON e.chat_ref_id = c.id " +
                    "WHERE e.started_at >= ? AND e.started_at <= ? AND e.bot_user_id = ? " +
                    "GROUP BY e.chat_ref_id ORDER BY total_bytes DESC LIMIT ?",
                    (rs, n) -> new ChatStatsRow(
                            rs.getLong("chat_ref_id"), rs.getString("canonical_chat_id"),
                            rs.getString("chat_title"), rs.getLong("export_count"),
                            rs.getLong("total_messages"), rs.getLong("total_bytes")),
                    from, to, botUserId, limit);
        }
        return jdbc.query(
                "SELECT e.chat_ref_id, c.canonical_chat_id, c.chat_title, " +
                "COUNT(*) AS export_count, " +
                "COALESCE(SUM(e.messages_count), 0) AS total_messages, " +
                "COALESCE(SUM(e.bytes_count), 0) AS total_bytes " +
                "FROM export_events e LEFT JOIN chats c ON e.chat_ref_id = c.id " +
                "WHERE e.started_at >= ? AND e.started_at <= ? " +
                "GROUP BY e.chat_ref_id ORDER BY total_bytes DESC LIMIT ?",
                (rs, n) -> new ChatStatsRow(
                        rs.getLong("chat_ref_id"), rs.getString("canonical_chat_id"),
                        rs.getString("chat_title"), rs.getLong("export_count"),
                        rs.getLong("total_messages"), rs.getLong("total_bytes")),
                from, to, limit);
    }

    // ─── Status breakdown ────────────────────────────────────────────────────

    public Map<String, Long> statusBreakdown(StatsPeriod period, Long botUserId) {
        String from = period.from().toString();
        String to = period.to().toString() + "T23:59:59Z";
        List<Map<String, Object>> rows;
        if (botUserId != null && botUserId > 0) {
            rows = jdbc.queryForList(
                    "SELECT status, COUNT(*) AS cnt FROM export_events " +
                    "WHERE started_at >= ? AND started_at <= ? AND bot_user_id = ? " +
                    "GROUP BY status",
                    from, to, botUserId);
        } else {
            rows = jdbc.queryForList(
                    "SELECT status, COUNT(*) AS cnt FROM export_events " +
                    "WHERE started_at >= ? AND started_at <= ? GROUP BY status",
                    from, to);
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(
                    (String) row.get("status"),
                    ((Number) row.get("cnt")).longValue());
        }
        return result;
    }

    // ─── Time series ─────────────────────────────────────────────────────────

    public List<TimeSeriesPointDto> timeSeries(StatsPeriod period, String metric, Long botUserId) {
        String fmt = period.strftimeFormat();
        String from = period.from().toString();
        String to = period.to().toString() + "T23:59:59Z";
        String aggregate = switch (metric == null ? "exports" : metric) {
            case "messages" -> "COALESCE(SUM(messages_count), 0)";
            case "bytes" -> "COALESCE(SUM(bytes_count), 0)";
            default -> "COUNT(*)";
        };
        String groupBucket = "strftime('" + fmt + "', started_at)";

        if (botUserId != null && botUserId > 0) {
            return jdbc.query(
                    "SELECT " + groupBucket + " AS period, " + aggregate + " AS value " +
                    "FROM export_events WHERE started_at >= ? AND started_at <= ? " +
                    "AND bot_user_id = ? GROUP BY period ORDER BY period",
                    (rs, n) -> new TimeSeriesPointDto(rs.getString("period"), rs.getLong("value")),
                    from, to, botUserId);
        }
        return jdbc.query(
                "SELECT " + groupBucket + " AS period, " + aggregate + " AS value " +
                "FROM export_events WHERE started_at >= ? AND started_at <= ? " +
                "GROUP BY period ORDER BY period",
                (rs, n) -> new TimeSeriesPointDto(rs.getString("period"), rs.getLong("value")),
                from, to);
    }

    // ─── User detail ─────────────────────────────────────────────────────────

    public UserDetailDto userDetail(long botUserId) {
        return jdbc.queryForObject(
                "SELECT bot_user_id, username, display_name, total_exports, " +
                "total_messages, total_bytes, first_seen, last_seen " +
                "FROM bot_users WHERE bot_user_id = ?",
                (rs, n) -> new UserDetailDto(
                        rs.getLong("bot_user_id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getInt("total_exports"),
                        rs.getLong("total_messages"), rs.getLong("total_bytes"),
                        rs.getString("first_seen"), rs.getString("last_seen")),
                botUserId);
    }
}
