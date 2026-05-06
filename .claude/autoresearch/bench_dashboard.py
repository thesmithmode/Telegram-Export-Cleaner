"""
Bench: dashboard.db SQLite query performance.

Создаёт in-memory SQLite с реальной schema dashboard.db,
засеивает реалистичными данными (50k users, 200k events),
измеряет latency ключевых queries из StatsQueryService.

Метрики:
- p50_overview_ms / p99_overview_ms (primary): /stats/overview endpoint queries
- p50_topchats_ms / p99_topchats_ms: topChats() query
- p50_status_ms / p99_status_ms: statusBreakdown()
- p50_resolve_ms / p99_resolve_ms: resolveChatMeta() N+1 pattern
- peak_rss_mb

Run: python .claude/autoresearch/bench_dashboard.py
"""
import os
import random
import sqlite3
import statistics
import sys
import time

try:
    import psutil
    _proc = psutil.Process()
except ImportError:
    _proc = None

DURATION_SEC = 30
N_USERS = 5000
N_CHATS = 500
N_EVENTS = 50000
TOP_N = 50


def peak_rss_mb():
    if _proc is None:
        return -1
    return _proc.memory_info().rss / 1024 / 1024


def build_db() -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    conn.executescript("""
        PRAGMA journal_mode=WAL;
        PRAGMA synchronous=NORMAL;
        PRAGMA cache_size=-8192;
        PRAGMA temp_store=MEMORY;

        CREATE TABLE bot_users (
            bot_user_id BIGINT PRIMARY KEY,
            username TEXT, display_name TEXT,
            first_seen TEXT NOT NULL, last_seen TEXT NOT NULL,
            total_exports INTEGER NOT NULL DEFAULT 0,
            total_messages BIGINT NOT NULL DEFAULT 0,
            total_bytes BIGINT NOT NULL DEFAULT 0
        );
        CREATE TABLE chats (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            canonical_chat_id TEXT NOT NULL,
            chat_id_raw TEXT NOT NULL,
            topic_id INTEGER,
            chat_title TEXT, chat_type TEXT,
            first_seen TEXT NOT NULL, last_seen TEXT NOT NULL
        );
        CREATE TABLE export_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL UNIQUE,
            bot_user_id BIGINT NOT NULL,
            chat_ref_id INTEGER NOT NULL,
            started_at TEXT NOT NULL,
            finished_at TEXT,
            status TEXT NOT NULL,
            messages_count BIGINT, bytes_count BIGINT,
            from_date TEXT, to_date TEXT,
            keywords TEXT, exclude_keywords TEXT,
            source TEXT NOT NULL DEFAULT 'bot',
            error_message TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        );

        CREATE INDEX idx_events_user_started ON export_events (bot_user_id, started_at DESC);
        CREATE INDEX idx_events_chat_started ON export_events (chat_ref_id, started_at DESC);
        CREATE INDEX idx_events_started ON export_events (started_at DESC);
        CREATE INDEX idx_events_status ON export_events (status);
        CREATE INDEX idx_events_overview_covering ON export_events (started_at DESC, bot_user_id, messages_count, bytes_count);
        CREATE INDEX idx_events_topchats_covering ON export_events (started_at DESC, chat_ref_id, messages_count, bytes_count);
        CREATE INDEX idx_events_status_covering ON export_events (started_at DESC, status);
        CREATE UNIQUE INDEX uk_chats_canonical_topic
            ON chats (canonical_chat_id, COALESCE(topic_id, -1));
    """)

    # Seed users
    conn.executemany(
        "INSERT INTO bot_users VALUES (?,?,?,?,?,?,?,?)",
        [(i, f"user{i}", f"User {i}",
          "2024-01-01T00:00:00", "2025-01-01T00:00:00",
          random.randint(1, 100), random.randint(100, 100000),
          random.randint(10000, 10000000))
         for i in range(1, N_USERS + 1)]
    )

    # Seed chats
    conn.executemany(
        "INSERT INTO chats(canonical_chat_id, chat_id_raw, topic_id, chat_title, chat_type, first_seen, last_seen) VALUES (?,?,?,?,?,?,?)",
        [(f"-100{i}", f"-100{i}", None, f"Chat {i}",
          random.choice(["supergroup", "channel", "group"]),
          "2024-01-01T00:00:00", "2025-01-01T00:00:00")
         for i in range(1, N_CHATS + 1)]
    )

    # Seed events
    statuses = ["COMPLETED", "FAILED", "IN_PROGRESS"]
    weights = [0.85, 0.1, 0.05]
    events = []
    for i in range(1, N_EVENTS + 1):
        day = random.randint(1, 365)
        hour = random.randint(0, 23)
        started = f"2024-{(day // 30 + 1):02d}-{(day % 28 + 1):02d}T{hour:02d}:00:00"
        status = random.choices(statuses, weights)[0]
        events.append((
            f"task-{i}", random.randint(1, N_USERS), random.randint(1, N_CHATS),
            started, started if status != "IN_PROGRESS" else None,
            status,
            random.randint(100, 50000) if status == "COMPLETED" else None,
            random.randint(10000, 5000000) if status == "COMPLETED" else None,
        ))
    conn.executemany(
        "INSERT INTO export_events(task_id, bot_user_id, chat_ref_id, started_at, finished_at, status, messages_count, bytes_count) VALUES (?,?,?,?,?,?,?,?)",
        events
    )
    conn.commit()
    return conn


def bench_query(conn, sql, params, n=200):
    lats = []
    for _ in range(n):
        t0 = time.perf_counter()
        conn.execute(sql, params).fetchall()
        lats.append((time.perf_counter() - t0) * 1000)
    return lats


def bench_n_plus_1(conn, n=50):
    """Simulate resolveChatMeta N+1: N separate queries per top-chat."""
    chat_ids = [row[0] for row in conn.execute(
        "SELECT DISTINCT chat_ref_id FROM export_events LIMIT ?", (TOP_N,)
    ).fetchall()]
    lats = []
    for _ in range(30):
        t0 = time.perf_counter()
        for cid in chat_ids:
            conn.execute(
                "SELECT canonical_chat_id, chat_title, chat_type FROM chats WHERE id=?",
                (cid,)
            ).fetchone()
        lats.append((time.perf_counter() - t0) * 1000)
    return lats


def bench_batch_resolve(conn):
    """Batch version: one IN() query instead of N+1."""
    chat_ids = [row[0] for row in conn.execute(
        "SELECT DISTINCT chat_ref_id FROM export_events LIMIT ?", (TOP_N,)
    ).fetchall()]
    placeholders = ",".join("?" * len(chat_ids))
    lats = []
    for _ in range(200):
        t0 = time.perf_counter()
        conn.execute(
            f"SELECT id, canonical_chat_id, chat_title, chat_type FROM chats WHERE id IN ({placeholders})",
            chat_ids
        ).fetchall()
        lats.append((time.perf_counter() - t0) * 1000)
    return lats


def p(lats, pct):
    return statistics.quantiles(lats, n=100)[pct - 1]


def main():
    conn = build_db()
    rss_before = peak_rss_mb()

    period = ("2024-01-01T00:00:00", "2025-01-01T00:00:00")

    overview_lats = bench_query(conn,
        "SELECT COUNT(*) AS exports, COALESCE(SUM(messages_count),0), COALESCE(SUM(bytes_count),0) "
        "FROM export_events WHERE started_at >= ? AND started_at <= ?",
        period)

    topchats_lats = bench_query(conn,
        "SELECT e.chat_ref_id, "
        "COUNT(*) AS export_count, "
        "COALESCE(SUM(e.messages_count),0) AS total_messages, "
        "COALESCE(SUM(e.bytes_count),0) AS total_bytes "
        "FROM export_events e "
        "WHERE e.started_at >= ? AND e.started_at <= ? "
        "GROUP BY e.chat_ref_id ORDER BY total_bytes DESC LIMIT 50",
        period)

    status_lats = bench_query(conn,
        "SELECT status, COUNT(*) FROM export_events "
        "WHERE started_at >= ? AND started_at <= ? GROUP BY status",
        period)

    active_users_lats = bench_query(conn,
        "SELECT COUNT(DISTINCT bot_user_id) FROM export_events "
        "WHERE started_at >= ? AND started_at <= ?",
        period)

    top_users_lats = bench_query(conn,
        "SELECT e.bot_user_id, "
        "COUNT(*) AS total_exports, "
        "COALESCE(SUM(e.messages_count),0) AS total_messages, "
        "COALESCE(SUM(e.bytes_count),0) AS total_bytes, "
        "MAX(e.started_at) AS last_seen "
        "FROM export_events e "
        "WHERE e.started_at >= ? AND e.started_at <= ? "
        "GROUP BY e.bot_user_id ORDER BY total_exports DESC LIMIT 10",
        period)

    timeseries_lats = bench_query(conn,
        "SELECT strftime('%Y-%m-%d', started_at) AS period, COUNT(*) AS value "
        "FROM export_events WHERE started_at >= ? AND started_at <= ? "
        "GROUP BY period ORDER BY period",
        period)

    n1_lats = bench_n_plus_1(conn)
    batch_lats = bench_batch_resolve(conn)

    rss_after = peak_rss_mb()

    print("---")
    print(f"bench_name:           bench_dashboard")
    print(f"p50_overview_ms:      {p(overview_lats, 50):.2f}")
    print(f"p99_overview_ms:      {p(overview_lats, 99):.2f}")
    print(f"p50_topchats_ms:      {p(topchats_lats, 50):.2f}")
    print(f"p99_topchats_ms:      {p(topchats_lats, 99):.2f}")
    print(f"p50_status_ms:        {p(status_lats, 50):.2f}")
    print(f"p99_status_ms:        {p(status_lats, 99):.2f}")
    print(f"p50_active_users_ms:  {p(active_users_lats, 50):.2f}")
    print(f"p99_active_users_ms:  {p(active_users_lats, 99):.2f}")
    print(f"p50_top_users_ms:     {p(top_users_lats, 50):.2f}")
    print(f"p99_top_users_ms:     {p(top_users_lats, 99):.2f}")
    print(f"p50_timeseries_ms:    {p(timeseries_lats, 50):.2f}")
    print(f"p99_timeseries_ms:    {p(timeseries_lats, 99):.2f}")
    print(f"p50_n1_resolve_ms:    {p(n1_lats, 50):.2f}")
    print(f"p99_n1_resolve_ms:    {p(n1_lats, 99):.2f}")
    print(f"p50_batch_resolve_ms: {p(batch_lats, 50):.2f}")
    print(f"p99_batch_resolve_ms: {p(batch_lats, 99):.2f}")
    print(f"peak_rss_mb:          {max(rss_before, rss_after):.1f}")
    print(f"n_events:             {N_EVENTS}")
    print(f"n_users:              {N_USERS}")
    print(f"n_chats:              {N_CHATS}")


if __name__ == "__main__":
    main()
