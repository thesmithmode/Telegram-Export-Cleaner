"""Round 5 coverage: добиваем последние gap'ы до 95% (line + branch).

Целимся в самые крупные uncovered блоки:

message_cache.py (92%):
- _db is None guards: 138, 205, 475, 722
- exception swallow blocks: 161-162, 182-183, 228-229, 361-364, 419-420,
  458-459, 563-564, 793-798, 812-814
- empty-list early returns: 669 (_merge_intervals), 701 (_merge_date_intervals)
- evict_if_needed busy-retry exhaust: 768
- partial branches: 235->exit (cache enabled false), 328->333 (no dates), etc.

pyrogram_client.py (92%):
- try_reconnect: 188-201 (success + failure branches)
- _get_topic_history exception/empty-message paths: 428-429, 464, 486-487,
  509, 547-554
- get_chat_messages_count / get_date_range_count / get_topic_messages_count
  null/exception: 605, 716, 728-729, 738-739, 781-782, 792-796, 805
- _resolve_via_canonical_mapping no-redis / exception: 881, 922-924, 926
- ensure_utc with naive datetime: branch 297->303 etc.

main.py (94%):
- _run_batch_loop exception fallback storing partial batch: 137
- vault recovery edges 292-294, 301-302
- _verify_and_normalize_chat private/topic edges 453-455, 465-467
- _send_completed_result 520, 877 (n/a — wrong file)
- _fetch_all_messages ValueError date parse: trivial cover via mock 898-900
- run/cleanup edges: 1154-1155, 1159-1161, 1166, 1227, 1317, 1319, 1325

ВАЖНО: используем простые unit-тесты, не sandboxим целиком pipeline.
"""

from __future__ import annotations

import asyncio
import json
import pytest
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import aiosqlite

from main import ExportWorker
from message_cache import MessageCache
from models import ExportRequest, ExportedMessage, SendResponsePayload
from pyrogram_client import (
    TelegramClient, ExportCancelled, ensure_utc, cancellable_floodwait_sleep,
)


# ─────────────────────────────────────────────────────────────────────────────
# helpers
# ─────────────────────────────────────────────────────────────────────────────

def make_job(**overrides) -> ExportRequest:
    base = dict(
        task_id="t_r5_1",
        user_id=42,
        user_chat_id=42,
        chat_id=-1001234567890,
        limit=0,
    )
    base.update(overrides)
    return ExportRequest(**base)


def _bare_worker() -> ExportWorker:
    w = ExportWorker.__new__(ExportWorker)
    w.telegram_client = None
    w.queue_consumer = None
    w.java_client = None
    w.message_cache = None
    w.control_redis = None
    w._redis_pool = None
    w.running = False
    w.jobs_processed = 0
    w.jobs_failed = 0
    w._cache_stats_task = None
    return w


def _bare_cache() -> MessageCache:
    """MessageCache без open db — для тестов guard-блоков `if self._db is None`."""
    c = MessageCache.__new__(MessageCache)
    c.db_path = ":memory:"
    c.max_disk_bytes = 1024
    c.max_messages_per_chat = 100
    c.ttl = 3600
    c.enabled = True
    c._db = None
    c._read_pool = None
    c._read_pool_size = 0
    c._read_conns = []
    c._chat_locks = {}
    c.redis_client = None
    return c


class _AsyncIter:
    def __init__(self, items):
        self._it = iter(items)

    def __aiter__(self):
        return self

    async def __anext__(self):
        try:
            return next(self._it)
        except StopIteration:
            raise StopAsyncIteration


# ─────────────────────────────────────────────────────────────────────────────
# MessageCache — guard и edge-case ветки
# ─────────────────────────────────────────────────────────────────────────────

class TestMessageCacheGuards:
    """`if self._db is None: return` и подобные ранние выходы."""

    async def test_migrate_page_size_no_db_returns(self):
        c = _bare_cache()
        # _db=None → ранний return на line 138
        await c._migrate_page_size_if_needed()

    async def test_log_startup_state_no_db_returns(self):
        c = _bare_cache()
        # _db=None → ранний return (line ~205)
        await c._log_startup_state()

    async def test_count_messages_no_db_returns_zero(self):
        c = _bare_cache()
        # _db=None → 0 (line ~475)
        result = await c.count_messages(123, 0, 100)
        assert result == 0

    async def test_count_messages_disabled_returns_zero(self):
        c = _bare_cache()
        c.enabled = False
        result = await c.count_messages(123, 0, 100)
        assert result == 0

    async def test_touch_no_db_returns(self):
        c = _bare_cache()
        # _db=None → return (line ~722)
        await c._touch(123, 0)

    async def test_evict_impl_no_db_returns_zero(self):
        c = _bare_cache()
        n = await c._evict_impl()
        assert n == 0

    async def test_count_messages_by_date_no_db_returns_zero(self):
        c = _bare_cache()
        n = await c.count_messages_by_date(123, "2025-01-01", "2025-01-02")
        assert n == 0

    async def test_count_messages_by_date_disabled_returns_zero(self):
        c = _bare_cache()
        c.enabled = False
        n = await c.count_messages_by_date(123, "2025-01-01", "2025-01-02")
        assert n == 0

    async def test_get_cached_date_ranges_no_db_returns_empty(self):
        c = _bare_cache()
        n = await c.get_cached_date_ranges(123)
        assert n == []

    async def test_get_cached_date_ranges_disabled_returns_empty(self):
        c = _bare_cache()
        c.enabled = False
        assert await c.get_cached_date_ranges(123) == []

    async def test_mark_date_range_checked_no_db_returns(self):
        c = _bare_cache()
        await c.mark_date_range_checked(123, "2025-01-01", "2025-01-02")

    async def test_mark_date_range_checked_disabled_returns(self):
        c = _bare_cache()
        c.enabled = False
        await c.mark_date_range_checked(123, "2025-01-01", "2025-01-02")

    async def test_get_missing_date_ranges_disabled_returns_full(self):
        c = _bare_cache()
        c.enabled = False
        n = await c.get_missing_date_ranges(123, "2025-01-01", "2025-01-31")
        assert n == [("2025-01-01", "2025-01-31")]

    async def test_get_missing_date_ranges_no_cached_returns_full(self):
        c = _bare_cache()
        # enabled, но cached пустой
        async def fake_get_cached_date_ranges(*a, **kw):
            return []
        c.get_cached_date_ranges = fake_get_cached_date_ranges
        n = await c.get_missing_date_ranges(123, "2025-01-01", "2025-01-31")
        assert n == [("2025-01-01", "2025-01-31")]

    async def test_get_missing_ranges_disabled_returns_full(self):
        c = _bare_cache()
        c.enabled = False
        n = await c.get_missing_ranges(123, 1, 100)
        assert n == [(1, 100)]

    async def test_get_cached_ranges_no_db_returns_empty(self):
        c = _bare_cache()
        assert await c.get_cached_ranges(123) == []

    async def test_get_cached_ranges_disabled_returns_empty(self):
        c = _bare_cache()
        c.enabled = False
        assert await c.get_cached_ranges(123) == []

    async def test_iter_messages_disabled_yields_nothing(self):
        c = _bare_cache()
        c.enabled = False
        items = [m async for m in c.iter_messages(123, 0, 100)]
        assert items == []

    async def test_iter_messages_no_db_yields_nothing(self):
        c = _bare_cache()
        items = [m async for m in c.iter_messages(123, 0, 100)]
        assert items == []

    async def test_iter_messages_by_date_disabled_yields_nothing(self):
        c = _bare_cache()
        c.enabled = False
        items = [m async for m in c.iter_messages_by_date(123, "2025-01-01", "2025-01-02")]
        assert items == []

    async def test_iter_messages_by_date_no_db_yields_nothing(self):
        c = _bare_cache()
        items = [m async for m in c.iter_messages_by_date(123, "2025-01-01", "2025-01-02")]
        assert items == []

    async def test_store_messages_disabled_returns_zero(self):
        c = _bare_cache()
        c.enabled = False
        msg = ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")
        n = await c.store_messages(123, [msg])
        assert n == 0

    async def test_store_messages_empty_list_returns_zero(self):
        c = _bare_cache()
        n = await c.store_messages(123, [])
        assert n == 0

    async def test_store_messages_no_db_returns_zero(self):
        c = _bare_cache()
        c._db = None
        msg = ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")
        n = await c.store_messages(123, [msg])
        assert n == 0

    async def test_initialize_disabled_returns(self):
        c = _bare_cache()
        c.enabled = False
        await c.initialize()  # ранний return при enabled=False


class TestMessageCachePureHelpers:
    """Чистые статические helper-методы."""

    def test_merge_intervals_empty_returns_empty(self):
        assert MessageCache._merge_intervals([]) == []

    def test_merge_intervals_overlapping_merged(self):
        result = MessageCache._merge_intervals([[1, 5], [3, 8], [10, 12]])
        assert result == [[1, 8], [10, 12]]

    def test_merge_intervals_adjacent_merged(self):
        # [1,5] + [6,8] → [1,8] (adjacent через +1)
        result = MessageCache._merge_intervals([[1, 5], [6, 8]])
        assert result == [[1, 8]]

    def test_merge_intervals_disjoint(self):
        result = MessageCache._merge_intervals([[1, 5], [10, 15]])
        assert result == [[1, 5], [10, 15]]

    def test_merge_date_intervals_empty_returns_empty(self):
        assert MessageCache._merge_date_intervals([]) == []

    def test_merge_date_intervals_overlapping(self):
        result = MessageCache._merge_date_intervals(
            [["2025-01-01", "2025-01-10"], ["2025-01-05", "2025-01-15"]]
        )
        assert result == [["2025-01-01", "2025-01-15"]]

    def test_merge_date_intervals_disjoint(self):
        result = MessageCache._merge_date_intervals(
            [["2025-01-01", "2025-01-05"], ["2025-02-01", "2025-02-10"]]
        )
        assert len(result) == 2

    def test_parse_date_to_timestamp_invalid(self):
        # exception path (lines 812-814)
        result = MessageCache._parse_date_to_timestamp("not-a-date")
        assert result is None

    def test_parse_date_to_timestamp_none(self):
        # TypeError path
        result = MessageCache._parse_date_to_timestamp(None)
        assert result is None

    def test_parse_date_to_timestamp_valid(self):
        result = MessageCache._parse_date_to_timestamp("2025-01-01T00:00:00")
        assert result is not None
        assert result > 0

    def test_extract_date_str_truncates(self):
        assert MessageCache._extract_date_str("2025-01-01T15:30:45") == "2025-01-01"

    def test_compute_missing_date_ranges_no_gaps(self):
        # cached=[full range] → missing=[]
        cached = [["2025-01-01", "2025-01-31"]]
        result = MessageCache._compute_missing_date_ranges(cached, "2025-01-05", "2025-01-20")
        assert result == []

    def test_compute_missing_date_ranges_with_gap(self):
        # cached=[Jan 1-5], request Jan 1-10 → gap Jan 6-10
        cached = [["2025-01-01", "2025-01-05"]]
        result = MessageCache._compute_missing_date_ranges(cached, "2025-01-01", "2025-01-10")
        assert result == [("2025-01-06", "2025-01-10")]

    def test_compute_missing_date_ranges_gap_before(self):
        # cached=[Jan 5-10], request Jan 1-10 → gap Jan 1-4
        cached = [["2025-01-05", "2025-01-10"]]
        result = MessageCache._compute_missing_date_ranges(cached, "2025-01-01", "2025-01-10")
        assert ("2025-01-01", "2025-01-04") in result


class TestMessageCacheExceptionPaths:
    """Exception-swallow ветки в read/write путях."""

    @pytest.fixture
    async def real_cache(self, tmp_path):
        c = MessageCache(
            db_path=str(tmp_path / "test_r5.db"),
            max_disk_bytes=10 * 1024 * 1024,
            max_messages_per_chat=1000,
            ttl_seconds=3600,
        )
        await c.initialize()
        yield c
        await c.close()

    async def test_close_swallows_read_conn_failure(self, real_cache):
        # close() цикл await rc.close() с except (228-229)
        bad_conn = AsyncMock()
        bad_conn.close = AsyncMock(side_effect=RuntimeError("disk gone"))
        real_cache._read_conns.append(bad_conn)
        await real_cache.close()
        # second close to avoid fixture re-close

    async def test_publish_cache_ranges_no_redis_returns(self, real_cache):
        # redis_client=None → ранний return
        real_cache.redis_client = None
        await real_cache._publish_cache_ranges_to_redis(123)

    async def test_publish_cache_ranges_exception_swallowed(self, real_cache):
        real_cache.redis_client = AsyncMock()
        real_cache.redis_client.set = AsyncMock(side_effect=RuntimeError("redis"))
        # Должно молча проглотить (line ~424-428)
        await real_cache._publish_cache_ranges_to_redis(123)

    async def test_publish_cache_ranges_username_exception_swallowed(self, real_cache):
        # Store something чтобы get_cached_ranges вернул не-пустой результат
        msg = ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")
        await real_cache.store_messages(123, [msg])

        real_cache.redis_client = AsyncMock()
        real_cache.redis_client.set = AsyncMock()
        real_cache.redis_client.get = AsyncMock(side_effect=RuntimeError("redis-get"))
        # Должно проглотить (lines 419-423)
        await real_cache._publish_cache_ranges_to_redis(123)

    async def test_publish_cache_ranges_with_username_bytes(self, real_cache):
        msg = ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")
        await real_cache.store_messages(123, [msg])

        real_cache.redis_client = AsyncMock()
        real_cache.redis_client.set = AsyncMock()
        real_cache.redis_client.get = AsyncMock(return_value=b"my_chat")
        # bytes → decode (line 413)
        await real_cache._publish_cache_ranges_to_redis(123)
        # Двойной set — numeric + username
        assert real_cache.redis_client.set.await_count >= 2

    async def test_iter_messages_deserialize_error_logged(self, real_cache):
        # Вставляем bad blob напрямую → deserialize raise → warning (458-459)
        await real_cache._db.execute(
            "INSERT INTO messages(chat_id, topic_id, msg_id, msg_ts, data) "
            "VALUES (?,?,?,?,?)",
            (123, 0, 1, 0, b"corrupt-bytes"),
        )
        await real_cache._db.commit()
        # iter_messages: deserialize должен бросить и попасть в except (458-459)
        items = [m async for m in real_cache.iter_messages(123, 0, 100)]
        assert items == []  # broken row пропущен

    async def test_iter_messages_by_date_deserialize_error_logged(self, real_cache):
        await real_cache._db.execute(
            "INSERT INTO messages(chat_id, topic_id, msg_id, msg_ts, data) "
            "VALUES (?,?,?,?,?)",
            (123, 0, 1, int(datetime(2025, 1, 5, tzinfo=timezone.utc).timestamp()),
             b"corrupt-bytes"),
        )
        await real_cache._db.commit()
        items = [m async for m in real_cache.iter_messages_by_date(
            123, "2025-01-01", "2025-01-10")]
        assert items == []  # broken row пропущен (lines 563-564)

    async def test_store_messages_rollback_failure_logged(self, real_cache):
        """executemany бросает → rollback вызывается → rollback тоже падает (361-364)."""
        msg = ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")
        with patch.object(real_cache._db, "executemany",
                          AsyncMock(side_effect=RuntimeError("boom"))):
            with patch.object(real_cache._db, "rollback",
                              AsyncMock(side_effect=RuntimeError("rb-fail"))):
                with pytest.raises(RuntimeError, match="boom"):
                    await real_cache.store_messages(123, [msg])

    async def test_store_messages_success_via_real_path(self, real_cache):
        # Двойной store того же чата — проверяет _add_range merge path
        msgs1 = [ExportedMessage(id=i, date="2025-01-01T00:00:00", text=f"m{i}")
                 for i in [1, 2, 3]]
        msgs2 = [ExportedMessage(id=i, date="2025-01-02T00:00:00", text=f"m{i}")
                 for i in [5, 6]]
        await real_cache.store_messages(123, msgs1)
        await real_cache.store_messages(123, msgs2)
        ranges = await real_cache.get_cached_ranges(123)
        assert len(ranges) == 2  # disjoint [1,3] и [5,6]

    async def test_evict_busy_retry_exhaust_returns_zero(self, real_cache):
        """OperationalError "database is locked" max_retries раз → return 0 (line 768)."""
        async def fake_evict_impl():
            raise aiosqlite.OperationalError("database is locked")
        real_cache._evict_impl = fake_evict_impl
        with patch("asyncio.sleep", AsyncMock()):
            result = await real_cache.evict_if_needed(max_retries=2)
        assert result == 0

    async def test_evict_non_busy_error_reraises(self, real_cache):
        """OperationalError без "locked"/"busy" → re-raise."""
        async def fake_evict_impl():
            raise aiosqlite.OperationalError("disk full")
        real_cache._evict_impl = fake_evict_impl
        with pytest.raises(aiosqlite.OperationalError):
            await real_cache.evict_if_needed(max_retries=1)

    async def test_evict_busy_succeeds_after_retry(self, real_cache):
        """First busy → retry → success returns evicted=0 (cache not full)."""
        calls = [0]
        async def fake_evict_impl():
            calls[0] += 1
            if calls[0] == 1:
                raise aiosqlite.OperationalError("database is busy")
            return 0
        real_cache._evict_impl = fake_evict_impl
        with patch("asyncio.sleep", AsyncMock()):
            result = await real_cache.evict_if_needed(max_retries=3)
        assert result == 0
        assert calls[0] == 2

    async def test_evict_impl_rollback_failure_swallowed(self, real_cache):
        """evict внутри for-loop падает → rollback падает → warning (793-798).

        Используем реальный путь — вставляем данные, ставим max_disk_bytes=1,
        затем заменяем execute через monkeypatch для DELETE.
        """
        msg = ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")
        await real_cache.store_messages(123, [msg])
        real_cache.max_disk_bytes = 1  # принудительный evict

        # _db.rollback падает → попадаем в rb_exc warning (794-797)
        async def bad_rollback():
            raise RuntimeError("rb-fail")
        # _db.commit падает → попадаем в except Exception (794-798)
        async def bad_commit():
            raise RuntimeError("commit-fail")

        real_cache._db.rollback = bad_rollback
        real_cache._db.commit = bad_commit
        with pytest.raises(RuntimeError, match="commit-fail"):
            await real_cache._evict_impl()


# ─────────────────────────────────────────────────────────────────────────────
# pyrogram_client — try_reconnect, topic edges, count fallbacks
# ─────────────────────────────────────────────────────────────────────────────

def _bare_tc() -> TelegramClient:
    from collections import OrderedDict
    tc = TelegramClient.__new__(TelegramClient)
    tc.client = AsyncMock()
    tc.is_connected = False
    tc.redis_client = None
    tc._topic_name_cache = OrderedDict()
    tc._TOPIC_NAME_CACHE_MAX = 500
    return tc


class TestTelegramClientReconnect:
    """try_reconnect: success + exception path (188-201)."""

    async def test_try_reconnect_success(self):
        tc = _bare_tc()
        tc.disconnect = AsyncMock()
        tc.connect = AsyncMock(return_value=True)
        with patch("pyrogram_client.Client") as MockClient, \
             patch("pyrogram_client.settings") as s:
            s.TELEGRAM_API_ID = 1
            s.TELEGRAM_API_HASH = "h"
            s.MAX_WORKERS = 4
            MockClient.return_value = MagicMock()
            ok = await tc.try_reconnect("new_session_string")
        assert ok is True
        tc.connect.assert_awaited_once()

    async def test_try_reconnect_client_construction_raises(self):
        tc = _bare_tc()
        tc.disconnect = AsyncMock()
        with patch("pyrogram_client.Client", side_effect=RuntimeError("bad session")), \
             patch("pyrogram_client.settings") as s:
            s.TELEGRAM_API_ID = 1
            s.TELEGRAM_API_HASH = "h"
            s.MAX_WORKERS = 4
            ok = await tc.try_reconnect("bad")
        assert ok is False

    async def test_try_reconnect_connect_returns_false(self):
        tc = _bare_tc()
        tc.disconnect = AsyncMock()
        tc.connect = AsyncMock(return_value=False)
        with patch("pyrogram_client.Client") as MockClient, \
             patch("pyrogram_client.settings") as s:
            s.TELEGRAM_API_ID = 1
            s.TELEGRAM_API_HASH = "h"
            s.MAX_WORKERS = 4
            MockClient.return_value = MagicMock()
            ok = await tc.try_reconnect("session")
        assert ok is False


class TestEnsureUtc:
    def test_ensure_utc_none(self):
        assert ensure_utc(None) is None

    def test_ensure_utc_naive(self):
        dt = datetime(2025, 1, 1, 10, 0, 0)
        result = ensure_utc(dt)
        assert result.tzinfo == timezone.utc

    def test_ensure_utc_aware(self):
        dt = datetime(2025, 1, 1, 10, 0, 0, tzinfo=timezone.utc)
        result = ensure_utc(dt)
        assert result is dt or result.tzinfo == timezone.utc


class TestCancellableFloodWaitSleep:
    async def test_cancel_during_sleep(self):
        cancelled = [False, True]
        idx = [0]

        async def is_cancelled():
            i = idx[0]
            idx[0] += 1
            return cancelled[min(i, len(cancelled) - 1)]

        with patch("asyncio.sleep", AsyncMock()):
            with pytest.raises(ExportCancelled):
                await cancellable_floodwait_sleep(
                    2.0, is_cancelled_fn=is_cancelled, tick_seconds=1.0
                )

    async def test_on_floodwait_initial_exception_swallowed(self):
        async def floodwait_cb(_remaining):
            raise RuntimeError("cb")

        with patch("asyncio.sleep", AsyncMock()):
            await cancellable_floodwait_sleep(
                0.5, on_floodwait=floodwait_cb, tick_seconds=1.0
            )

    async def test_is_cancelled_exception_swallowed(self):
        async def is_cancelled():
            raise RuntimeError("redis")

        with patch("asyncio.sleep", AsyncMock()):
            await cancellable_floodwait_sleep(
                0.5, is_cancelled_fn=is_cancelled, tick_seconds=1.0
            )

    async def test_progress_tick_callback_fired(self):
        ticks = []

        async def cb(remaining):
            ticks.append(remaining)

        with patch("asyncio.sleep", AsyncMock()):
            await cancellable_floodwait_sleep(
                10.0, on_floodwait=cb, tick_seconds=1.0, progress_interval=2.0
            )
        # initial + at least one tick during long wait
        assert len(ticks) >= 2

    async def test_progress_tick_exception_swallowed(self):
        calls = [0]

        async def cb(remaining):
            calls[0] += 1
            if calls[0] > 1:
                raise RuntimeError("cb-tick")

        with patch("asyncio.sleep", AsyncMock()):
            await cancellable_floodwait_sleep(
                10.0, on_floodwait=cb, tick_seconds=1.0, progress_interval=2.0
            )


class TestTelegramClientCounts:
    """get_chat_messages_count, get_date_range_count, get_topic_messages_count."""

    async def test_get_chat_messages_count_zero_returns_none(self):
        tc = _bare_tc()
        tc.client.get_chat_history_count = AsyncMock(return_value=0)
        result = await tc.get_chat_messages_count(123)
        assert result is None

    async def test_get_chat_messages_count_positive(self):
        tc = _bare_tc()
        tc.client.get_chat_history_count = AsyncMock(return_value=42)
        result = await tc.get_chat_messages_count(123)
        assert result == 42

    async def test_get_chat_messages_count_exception_returns_none(self):
        tc = _bare_tc()
        tc.client.get_chat_history_count = AsyncMock(side_effect=RuntimeError("api"))
        result = await tc.get_chat_messages_count(123)
        assert result is None

    async def test_get_date_range_count_to_none_returns_none(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        result_to = MagicMock()
        result_to.count = None
        tc.client.invoke = AsyncMock(return_value=result_to)
        from_date = datetime(2025, 1, 1, tzinfo=timezone.utc)
        to_date = datetime(2025, 1, 31, tzinfo=timezone.utc)
        result = await tc.get_date_range_count(123, from_date, to_date)
        assert result is None

    async def test_get_date_range_count_from_none_returns_none(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        result_to = MagicMock(); result_to.count = 100
        result_from = MagicMock(); result_from.count = None
        tc.client.invoke = AsyncMock(side_effect=[result_to, result_from])
        result = await tc.get_date_range_count(
            123, datetime(2025, 1, 1, tzinfo=timezone.utc),
            datetime(2025, 1, 31, tzinfo=timezone.utc),
        )
        assert result is None

    async def test_get_date_range_count_happy(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        result_to = MagicMock(); result_to.count = 100
        result_from = MagicMock(); result_from.count = 30
        tc.client.invoke = AsyncMock(side_effect=[result_to, result_from])
        result = await tc.get_date_range_count(
            123, datetime(2025, 1, 1, tzinfo=timezone.utc),
            datetime(2025, 1, 31, tzinfo=timezone.utc),
        )
        assert result == 70

    async def test_get_date_range_count_negative_zeroed(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        result_to = MagicMock(); result_to.count = 10
        result_from = MagicMock(); result_from.count = 100
        tc.client.invoke = AsyncMock(side_effect=[result_to, result_from])
        result = await tc.get_date_range_count(
            123, datetime(2025, 1, 1, tzinfo=timezone.utc),
            datetime(2025, 1, 31, tzinfo=timezone.utc),
        )
        assert result == 0

    async def test_get_date_range_count_exception_returns_none(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(side_effect=RuntimeError("api"))
        result = await tc.get_date_range_count(
            123, datetime(2025, 1, 1, tzinfo=timezone.utc),
            datetime(2025, 1, 31, tzinfo=timezone.utc),
        )
        assert result is None

    async def test_get_topic_messages_count_with_count(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        result = MagicMock(); result.count = 50; result.messages = []
        tc.client.invoke = AsyncMock(return_value=result)
        n = await tc.get_topic_messages_count(123, 42)
        assert n == 50

    async def test_get_topic_messages_count_no_count_uses_messages(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        result = MagicMock(); result.count = None
        result.messages = [1, 2, 3]
        tc.client.invoke = AsyncMock(return_value=result)
        n = await tc.get_topic_messages_count(123, 42)
        assert n == 3

    async def test_get_topic_messages_count_empty_messages(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        result = MagicMock(); result.count = None; result.messages = []
        tc.client.invoke = AsyncMock(return_value=result)
        n = await tc.get_topic_messages_count(123, 42)
        assert n == 0

    async def test_get_topic_messages_count_exception_returns_none(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(side_effect=RuntimeError("api"))
        n = await tc.get_topic_messages_count(123, 42)
        assert n is None


class TestTelegramClientTopicName:
    async def test_get_topic_name_cache_hit(self):
        tc = _bare_tc()
        tc._topic_name_cache[(123, 42)] = "Cached Topic"
        name = await tc.get_topic_name(123, 42)
        assert name == "Cached Topic"

    async def test_get_topic_name_resolve(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        topic = MagicMock(); topic.title = "MyTopic"
        result = MagicMock(); result.topics = [topic]
        tc.client.invoke = AsyncMock(return_value=result)
        name = await tc.get_topic_name(123, 42)
        assert name == "MyTopic"

    async def test_get_topic_name_no_topics(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        result = MagicMock(); result.topics = []
        tc.client.invoke = AsyncMock(return_value=result)
        name = await tc.get_topic_name(123, 42)
        assert name is None

    async def test_get_topic_name_exception_returns_none(self):
        tc = _bare_tc()
        tc.client.resolve_peer = AsyncMock(side_effect=RuntimeError("api"))
        name = await tc.get_topic_name(123, 42)
        assert name is None

    async def test_get_topic_name_cache_eviction(self):
        tc = _bare_tc()
        tc._TOPIC_NAME_CACHE_MAX = 2
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())

        def make_result(title):
            topic = MagicMock(); topic.title = title
            r = MagicMock(); r.topics = [topic]
            return r

        tc.client.invoke = AsyncMock(side_effect=[
            make_result("T1"), make_result("T2"), make_result("T3"),
        ])
        await tc.get_topic_name(1, 1)
        await tc.get_topic_name(1, 2)
        await tc.get_topic_name(1, 3)
        # First evicted
        assert (1, 1) not in tc._topic_name_cache


class TestTelegramClientGetMessagesCount:
    async def test_dispatches_to_topic(self):
        tc = _bare_tc()
        tc.get_topic_messages_count = AsyncMock(return_value=10)
        n = await tc.get_messages_count(123, topic_id=42)
        assert n == 10

    async def test_dispatches_to_date_range(self):
        tc = _bare_tc()
        tc.get_date_range_count = AsyncMock(return_value=50)
        n = await tc.get_messages_count(
            123, from_date=datetime(2025, 1, 1, tzinfo=timezone.utc)
        )
        assert n == 50

    async def test_dispatches_to_chat_count(self):
        tc = _bare_tc()
        tc.get_chat_messages_count = AsyncMock(return_value=100)
        n = await tc.get_messages_count(123)
        assert n == 100


class TestTelegramClientResolveCanonical:
    """_resolve_via_canonical_mapping."""

    async def test_no_redis_returns_none(self):
        tc = _bare_tc()
        tc.redis_client = None
        result = await tc._resolve_via_canonical_mapping(-100123)
        assert result is None

    async def test_no_canonical_mapping_returns_none(self):
        tc = _bare_tc()
        tc.redis_client = AsyncMock()
        tc.redis_client.get = AsyncMock(return_value=None)
        result = await tc._resolve_via_canonical_mapping(-100123)
        assert result is None

    async def test_canonical_resolve_happy(self):
        tc = _bare_tc()
        tc.redis_client = AsyncMock()
        tc.redis_client.get = AsyncMock(return_value="my_chat")
        chat = MagicMock()
        chat.id = -100123; chat.title = "T"; chat.username = "my_chat"
        chat.type = MagicMock(); chat.type.__str__ = lambda s: "ChatType.SUPERGROUP"
        tc.client.get_chat = AsyncMock(return_value=chat)
        result = await tc._resolve_via_canonical_mapping(-100123)
        assert result is not None
        assert result[0] is True

    async def test_canonical_bytes_decoded(self):
        tc = _bare_tc()
        tc.redis_client = AsyncMock()
        tc.redis_client.get = AsyncMock(return_value=b"my_chat")
        chat = MagicMock()
        chat.id = -100123; chat.title = "T"; chat.username = "my_chat"
        chat.type = MagicMock(); chat.type.__str__ = lambda s: "ChatType.SUPERGROUP"
        tc.client.get_chat = AsyncMock(return_value=chat)
        result = await tc._resolve_via_canonical_mapping(-100123)
        assert result is not None

    async def test_canonical_private_blocked(self):
        tc = _bare_tc()
        tc.redis_client = AsyncMock()
        tc.redis_client.get = AsyncMock(return_value="someone")
        chat = MagicMock()
        chat.id = 123; chat.title = "T"; chat.username = "someone"
        chat.type = MagicMock(); chat.type.__str__ = lambda s: "ChatType.PRIVATE"
        tc.client.get_chat = AsyncMock(return_value=chat)
        result = await tc._resolve_via_canonical_mapping(-100123)
        assert result == (False, None, "PRIVATE_CHAT_FORBIDDEN")

    async def test_canonical_exception_returns_none(self):
        tc = _bare_tc()
        tc.redis_client = AsyncMock()
        tc.redis_client.get = AsyncMock(side_effect=RuntimeError("redis"))
        result = await tc._resolve_via_canonical_mapping(-100123)
        assert result is None


class TestBuildChatInfo:
    def test_build_chat_info_empty_fields(self):
        chat = MagicMock(spec=[])
        chat.id = 123
        info = TelegramClient._build_chat_info(chat)
        assert info["id"] == 123
        assert info["title"] == ""

    def test_build_chat_info_with_type(self):
        chat = MagicMock(spec=[])
        chat.id = 123
        chat.title = "Chan"
        chat.username = "chan"
        chat.type = "ChatType.SUPERGROUP"
        info = TelegramClient._build_chat_info(chat)
        assert info["type"] == "supergroup"


# ─────────────────────────────────────────────────────────────────────────────
# main.py — оставшиеся edge cases
# ─────────────────────────────────────────────────────────────────────────────

class TestMainBatchLoopExceptionFallback:
    """_run_batch_loop except Exception path (line 137)."""

    async def test_pyrogram_raises_partial_batch_stored(self, tmp_path):
        w = _bare_worker()
        w.message_cache = MagicMock()
        w.message_cache.enabled = True
        w.message_cache.store_messages = AsyncMock()
        w._CACHE_BATCH_SIZE = 1000

        async def faulty_iter():
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")
            yield ExportedMessage(id=2, date="2025-01-01T00:00:00", text="y")
            raise RuntimeError("pyrogram crashed")

        job = make_job()
        with pytest.raises(RuntimeError, match="pyrogram crashed"):
            await w._run_batch_loop(job, faulty_iter(), tracker=None, initial_count=0)
        # Partial batch (2 msgs) был сохранён (line 137)
        w.message_cache.store_messages.assert_awaited_once()

    async def test_pyrogram_raises_no_cache_no_store(self):
        w = _bare_worker()
        w.message_cache = None  # cache disabled
        w._CACHE_BATCH_SIZE = 1000

        async def faulty_iter():
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")
            raise RuntimeError("crash")

        job = make_job()
        with pytest.raises(RuntimeError):
            await w._run_batch_loop(job, faulty_iter(), tracker=None, initial_count=0)


class TestMainCleanupEdges:
    """cleanup error swallowing (lines 1317-1325)."""

    async def test_cleanup_telegram_disconnect_fail_swallowed(self):
        w = _bare_worker()
        w.telegram_client = AsyncMock()
        w.telegram_client.disconnect = AsyncMock(side_effect=RuntimeError("tg"))
        w.queue_consumer = AsyncMock()
        w.queue_consumer.disconnect = AsyncMock()
        w.java_client = AsyncMock()
        w.java_client.close = AsyncMock()
        w.message_cache = AsyncMock()
        w.message_cache.close = AsyncMock()
        w.control_redis = AsyncMock()
        w.control_redis.aclose = AsyncMock()
        await w.cleanup()

    async def test_cleanup_cache_stats_task_cancelled(self):
        w = _bare_worker()
        task = asyncio.create_task(asyncio.sleep(100))
        w._cache_stats_task = task
        await w.cleanup()
        assert task.cancelled() or task.done()

    async def test_cleanup_cache_stats_task_await_raises(self):
        w = _bare_worker()
        async def boom():
            raise RuntimeError("task-err")
        task = asyncio.create_task(boom())
        await asyncio.sleep(0.01)
        w._cache_stats_task = task
        await w.cleanup()  # должно проглотить


class TestMainAlertAdminSessionInvalid:
    """_alert_admin_session_invalid (300-313)."""

    async def test_no_bot_token_logs_warning(self):
        w = _bare_worker()
        with patch("main.settings") as s:
            s.TELEGRAM_BOT_TOKEN = ""
            s.ADMIN_TG_ID = 0
            await w._alert_admin_session_invalid()

    async def test_no_admin_id_logs_warning(self):
        w = _bare_worker()
        with patch("main.settings") as s:
            s.TELEGRAM_BOT_TOKEN = "TOK"
            s.ADMIN_TG_ID = 0
            await w._alert_admin_session_invalid()

    async def test_with_token_sends_via_httpx(self):
        w = _bare_worker()
        with patch("main.settings") as s:
            s.TELEGRAM_BOT_TOKEN = "TOK"
            s.ADMIN_TG_ID = 999
            s.WORKER_NAME = "wkr"
            mock_client = AsyncMock()
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=None)
            mock_client.post = AsyncMock()
            with patch("httpx.AsyncClient", return_value=mock_client):
                await w._alert_admin_session_invalid()
            mock_client.post.assert_awaited_once()

    async def test_httpx_exception_swallowed(self):
        w = _bare_worker()
        with patch("main.settings") as s:
            s.TELEGRAM_BOT_TOKEN = "TOK"
            s.ADMIN_TG_ID = 999
            s.WORKER_NAME = "wkr"
            with patch("httpx.AsyncClient", side_effect=RuntimeError("net")):
                await w._alert_admin_session_invalid()


class TestMainSessionRecovery:
    """_try_session_recovery (279-294)."""

    async def test_no_vault_key_returns_false(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=None)
        with patch("main.settings") as s:
            s.REDIS_SESSION_VAULT_KEY = "k"
            ok = await w._try_session_recovery()
        assert ok is False

    async def test_vault_bytes_decoded_reconnect_success(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=b"new_session")
        w.control_redis.delete = AsyncMock()
        w.telegram_client = AsyncMock()
        w.telegram_client.try_reconnect = AsyncMock(return_value=True)
        with patch("main.settings") as s:
            s.REDIS_SESSION_VAULT_KEY = "k"
            ok = await w._try_session_recovery()
        assert ok is True
        w.control_redis.delete.assert_awaited_once()

    async def test_vault_reconnect_fails_returns_false(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value="session")
        w.telegram_client = AsyncMock()
        w.telegram_client.try_reconnect = AsyncMock(return_value=False)
        with patch("main.settings") as s:
            s.REDIS_SESSION_VAULT_KEY = "k"
            ok = await w._try_session_recovery()
        assert ok is False

    async def test_vault_exception_returns_false(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(side_effect=RuntimeError("redis"))
        with patch("main.settings") as s:
            s.REDIS_SESSION_VAULT_KEY = "k"
            ok = await w._try_session_recovery()
        assert ok is False


class TestMainNotifyQueuePosition:
    async def test_no_redis_returns(self):
        w = _bare_worker()
        await w._notify_queue_position("t1", 1, 5)

    async def test_no_value_returns(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=None)
        w.java_client = AsyncMock()
        await w._notify_queue_position("t1", 1, 5)
        w.java_client.update_queue_position.assert_not_called()

    async def test_malformed_value_skips(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value="not-a-pair")
        w.java_client = AsyncMock()
        await w._notify_queue_position("t1", 1, 5)
        w.java_client.update_queue_position.assert_not_called()

    async def test_happy_path(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value="42:777")
        w.java_client = AsyncMock()
        await w._notify_queue_position("t1", 1, 5)
        w.java_client.update_queue_position.assert_awaited_once_with(42, 777, 1, 5)


class TestMainUpdateAllQueuePositions:
    async def test_no_redis_returns(self):
        w = _bare_worker()
        await w._update_all_queue_positions("t1")

    async def test_exception_swallowed(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.java_client = AsyncMock()
        w.queue_consumer = AsyncMock()
        w.queue_consumer.get_pending_jobs = AsyncMock(side_effect=RuntimeError("r"))
        await w._update_all_queue_positions("t1")

    async def test_happy_path_sends_to_all(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.java_client = AsyncMock()
        w.queue_consumer = AsyncMock()
        job1 = make_job(task_id="t2")
        w.queue_consumer.get_pending_jobs = AsyncMock(
            return_value={"jobs": [job1], "total_count": 2}
        )
        w._notify_queue_position = AsyncMock()
        await w._update_all_queue_positions("t1")
        # current + 1 pending = 2 calls
        assert w._notify_queue_position.await_count == 2

    async def test_partial_failure_logs_warning(self):
        w = _bare_worker()
        w.control_redis = AsyncMock()
        w.java_client = AsyncMock()
        w.queue_consumer = AsyncMock()
        job1 = make_job(task_id="t2")
        w.queue_consumer.get_pending_jobs = AsyncMock(
            return_value={"jobs": [job1], "total_count": 2}
        )
        calls = [0]

        async def flaky(*a, **kw):
            calls[0] += 1
            if calls[0] == 1:
                return None
            raise RuntimeError("send-fail")

        w._notify_queue_position = flaky
        await w._update_all_queue_positions("t1")


class TestComputeCachedRanges:
    def test_no_missing_returns_full(self):
        result = ExportWorker._compute_cached_ranges("2025-01-01", "2025-01-10", [])
        assert result == [("2025-01-01", "2025-01-10")]

    def test_one_gap_in_middle(self):
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-10", [("2025-01-05", "2025-01-07")]
        )
        assert ("2025-01-01", "2025-01-04") in result
        assert ("2025-01-08", "2025-01-10") in result

    def test_gap_at_start(self):
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-10", [("2025-01-01", "2025-01-03")]
        )
        assert result == [("2025-01-04", "2025-01-10")]

    def test_gap_at_end(self):
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-10", [("2025-01-08", "2025-01-10")]
        )
        assert result == [("2025-01-01", "2025-01-07")]


# ─────────────────────────────────────────────────────────────────────────────
# pyrogram_client.connect — exception logging path
# ─────────────────────────────────────────────────────────────────────────────

class TestTelegramClientConnectAndDisconnect:
    async def test_connect_already_connected_returns_true(self):
        tc = _bare_tc()
        tc.is_connected = True
        ok = await tc.connect()
        assert ok is True

    async def test_connect_happy_path(self):
        tc = _bare_tc()
        tc.client.start = AsyncMock()
        me = MagicMock(); me.first_name = "Me"; me.username = "u"
        tc.client.get_me = AsyncMock(return_value=me)
        ok = await tc.connect()
        assert ok is True
        assert tc.is_connected is True

    async def test_connect_generic_exception_logs(self):
        tc = _bare_tc()
        tc.client.start = AsyncMock(side_effect=RuntimeError("network"))
        ok = await tc.connect()
        assert ok is False

    async def test_disconnect_not_connected_skips(self):
        tc = _bare_tc()
        tc.is_connected = False
        await tc.disconnect()

    async def test_disconnect_exception_swallowed(self):
        tc = _bare_tc()
        tc.is_connected = True
        tc.client.is_connected = True
        tc.client.stop = AsyncMock(side_effect=RuntimeError("stop-err"))
        await tc.disconnect()
