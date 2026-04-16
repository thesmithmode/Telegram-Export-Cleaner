"""
Tests for MessageCache — SQLite-backed message caching on disk.

All tests use a temporary SQLite database (tmp_path fixture) instead of Redis.
"""

import time
import pytest
import asyncio

from models import ExportedMessage
from message_cache import MessageCache


def _make_msg(msg_id: int, text: str = "", date: str = "2025-01-01T00:00:00") -> ExportedMessage:
    """Helper: create ExportedMessage with given id."""
    return ExportedMessage(id=msg_id, type="message", date=date, text=text or f"msg_{msg_id}")


def _make_messages(ids) -> list[ExportedMessage]:
    """Helper: create list of ExportedMessage with given ids."""
    return [_make_msg(i) for i in ids]


@pytest.fixture
async def cache(tmp_path):
    """MessageCache backed by a temp SQLite DB."""
    c = MessageCache(
        db_path=str(tmp_path / "test_cache.db"),
        max_disk_bytes=10 * 1024 * 1024,  # 10 MB — won't evict during normal tests
        max_messages_per_chat=1000,
        ttl_seconds=3600,
    )
    await c.initialize()
    yield c
    await c.close()


class TestStoreAndRetrieve:
    """Basic round-trip: store messages, retrieve them."""

    @pytest.mark.asyncio
    async def test_store_and_retrieve_messages(self, cache):
        """Store messages, then retrieve by range — should get same data back."""
        messages = _make_messages([1, 2, 3, 4, 5])
        stored = await cache.store_messages(123, messages)
        assert stored == 5

        retrieved = await cache.get_messages(123, 1, 5)
        assert len(retrieved) == 5
        assert [m.id for m in retrieved] == [1, 2, 3, 4, 5]
        assert retrieved[0].text == "msg_1"

    @pytest.mark.asyncio
    async def test_retrieve_partial_range(self, cache):
        """Retrieve a subset of cached messages."""
        await cache.store_messages(123, _make_messages([1, 2, 3, 4, 5]))
        retrieved = await cache.get_messages(123, 2, 4)
        assert [m.id for m in retrieved] == [2, 3, 4]

    @pytest.mark.asyncio
    async def test_retrieve_empty_chat(self, cache):
        """Retrieve from non-existent chat returns empty list."""
        retrieved = await cache.get_messages(999, 1, 100)
        assert retrieved == []

    @pytest.mark.asyncio
    async def test_store_overwrites_duplicates(self, cache):
        """Storing same msg_id twice overwrites (INSERT OR REPLACE)."""
        msg_v1 = _make_msg(1, text="version 1")
        msg_v2 = _make_msg(1, text="version 2")
        await cache.store_messages(123, [msg_v1])
        await cache.store_messages(123, [msg_v2])

        retrieved = await cache.get_messages(123, 1, 1)
        assert len(retrieved) == 1
        assert retrieved[0].text == "version 2"


class TestCachedRanges:
    """Range tracking metadata."""

    @pytest.mark.asyncio
    async def test_get_cached_ranges_empty(self, cache):
        """No cache → empty ranges."""
        ranges = await cache.get_cached_ranges(999)
        assert ranges == []

    @pytest.mark.asyncio
    async def test_get_cached_ranges_after_store(self, cache):
        """After storing [1-5], ranges should be [[1,5]]."""
        await cache.store_messages(123, _make_messages([1, 2, 3, 4, 5]))
        ranges = await cache.get_cached_ranges(123)
        assert ranges == [[1, 5]]

    @pytest.mark.asyncio
    async def test_get_cached_ranges_disjoint(self, cache):
        """Store two disjoint batches → two separate ranges."""
        await cache.store_messages(123, _make_messages([1, 2, 3]))
        await cache.store_messages(123, _make_messages([10, 11, 12]))
        ranges = await cache.get_cached_ranges(123)
        assert ranges == [[1, 3], [10, 12]]

    @pytest.mark.asyncio
    async def test_ranges_merge_adjacent(self, cache):
        """Store [1-3] then [4-6] → should merge to [[1,6]]."""
        await cache.store_messages(123, _make_messages([1, 2, 3]))
        await cache.store_messages(123, _make_messages([4, 5, 6]))
        ranges = await cache.get_cached_ranges(123)
        assert ranges == [[1, 6]]

    @pytest.mark.asyncio
    async def test_ranges_merge_overlapping(self, cache):
        """Store [1-5] then [3-8] → should merge to [[1,8]]."""
        await cache.store_messages(123, _make_messages([1, 2, 3, 4, 5]))
        await cache.store_messages(123, _make_messages([3, 4, 5, 6, 7, 8]))
        ranges = await cache.get_cached_ranges(123)
        assert ranges == [[1, 8]]


class TestMissingRanges:
    """Gap detection: what ranges are NOT in cache."""

    @pytest.mark.asyncio
    async def test_get_missing_ranges_no_cache(self, cache):
        """No cache → entire requested range is missing."""
        missing = await cache.get_missing_ranges(999, 1, 1000)
        assert missing == [(1, 1000)]

    @pytest.mark.asyncio
    async def test_get_missing_ranges_partial(self, cache):
        """[1-1000] requested, [300-500] cached → gaps at [1-299] and [501-1000]."""
        await cache.store_messages(123, _make_messages(range(300, 501)))
        missing = await cache.get_missing_ranges(123, 1, 1000)
        assert missing == [(1, 299), (501, 1000)]

    @pytest.mark.asyncio
    async def test_get_missing_ranges_full_cache(self, cache):
        """Everything cached → no missing ranges."""
        await cache.store_messages(123, _make_messages(range(1, 101)))
        missing = await cache.get_missing_ranges(123, 1, 100)
        assert missing == []

    @pytest.mark.asyncio
    async def test_get_missing_ranges_multiple_gaps(self, cache):
        """Cached [10-20] and [50-60] → gaps at start, middle, end."""
        await cache.store_messages(123, _make_messages(range(10, 21)))
        await cache.store_messages(123, _make_messages(range(50, 61)))
        missing = await cache.get_missing_ranges(123, 1, 100)
        assert missing == [(1, 9), (21, 49), (61, 100)]


class TestMergeAndSort:
    """Merging cached and fresh messages."""

    @pytest.mark.asyncio
    async def test_merge_and_sort_dedup(self, cache):
        """Merge two lists with overlapping IDs — dedup by id, sort ascending."""
        cached = _make_messages([1, 3, 5])
        fresh = _make_messages([2, 3, 4])  # id=3 is duplicate
        merged = cache.merge_and_sort(cached, fresh)
        assert [m.id for m in merged] == [1, 2, 3, 4, 5]

    @pytest.mark.asyncio
    async def test_merge_empty_cached(self, cache):
        """Merge with empty cache — just sort fresh."""
        merged = cache.merge_and_sort([], _make_messages([5, 3, 1]))
        assert [m.id for m in merged] == [1, 3, 5]

    @pytest.mark.asyncio
    async def test_merge_empty_fresh(self, cache):
        """Merge with no fresh messages — return cached sorted."""
        merged = cache.merge_and_sort(_make_messages([3, 1, 2]), [])
        assert [m.id for m in merged] == [1, 2, 3]


class TestMsgpackSerialization:
    """msgpack round-trip for ExportedMessage."""

    @pytest.mark.asyncio
    async def test_msgpack_roundtrip(self, cache):
        """ExportedMessage survives msgpack serialize/deserialize via store+retrieve."""
        msg = ExportedMessage(
            id=42,
            type="message",
            date="2025-06-24T15:29:46",
            text="Hello with entities",
            from_user="John Doe",
            from_id={"peer_type": "user", "peer_id": 456},
        )
        await cache.store_messages(123, [msg])
        retrieved = await cache.get_messages(123, 42, 42)

        assert len(retrieved) == 1
        r = retrieved[0]
        assert r.id == 42
        assert r.text == "Hello with entities"
        assert r.from_user == "John Doe"
        assert r.from_id == {"peer_type": "user", "peer_id": 456}
        assert r.date == "2025-06-24T15:29:46"

    @pytest.mark.asyncio
    async def test_msgpack_roundtrip_unicode(self, cache):
        """Unicode and emoji survive msgpack round-trip."""
        msg = _make_msg(1, text="Привет мир 🎉🔥")
        await cache.store_messages(123, [msg])
        retrieved = await cache.get_messages(123, 1, 1)
        assert retrieved[0].text == "Привет мир 🎉🔥"


class TestLRUTracking:
    """last_accessed updated on store and read — basis for LRU eviction."""

    @pytest.mark.asyncio
    async def test_last_accessed_set_on_store(self, cache):
        """Storing messages sets last_accessed in chat_meta."""
        before = time.time()
        await cache.store_messages(123, _make_messages([1, 2, 3]))

        async with cache._db.execute(
            "SELECT last_accessed FROM chat_meta WHERE chat_id=?", (123,)
        ) as cur:
            row = await cur.fetchone()
        assert row is not None
        assert row[0] >= before

    @pytest.mark.asyncio
    async def test_last_accessed_refresh_on_read(self, cache):
        """Reading messages updates last_accessed (LRU touch)."""
        await cache.store_messages(123, _make_messages([1, 2, 3]))

        async with cache._db.execute(
            "SELECT last_accessed FROM chat_meta WHERE chat_id=?", (123,)
        ) as cur:
            row = await cur.fetchone()
        initial_ts = row[0]

        # Small delay so the new timestamp is strictly greater
        await asyncio.sleep(0.02)

        await cache.get_messages(123, 1, 3)

        async with cache._db.execute(
            "SELECT last_accessed FROM chat_meta WHERE chat_id=?", (123,)
        ) as cur:
            row = await cur.fetchone()
        assert row[0] > initial_ts


class TestEviction:
    """LRU eviction removes oldest chats when over disk budget."""

    @pytest.mark.asyncio
    async def test_eviction_oldest_chat(self, tmp_path):
        """Oldest-accessed chat evicted first when over budget."""
        cache = MessageCache(
            db_path=str(tmp_path / "evict.db"),
            max_disk_bytes=10 * 1024 * 1024,  # large enough to store both
        )
        await cache.initialize()
        try:
            await cache.store_messages(1001, _make_messages([1, 2, 3]))
            await cache.store_messages(1002, _make_messages([1, 2, 3]))

            # Touch chat 1002 to make it newer; 1001 becomes LRU
            await cache.get_messages(1002, 1, 3)

            # Force eviction by setting budget to 0
            cache.max_disk_bytes = 0
            evicted = await cache.evict_if_needed()
            assert evicted >= 1

            # chat 1001 (LRU) should be evicted
            ranges_old = await cache.get_cached_ranges(1001)
            assert ranges_old == []
        finally:
            await cache.close()

    @pytest.mark.asyncio
    async def test_count_messages(self, cache):
        """count_messages returns correct count for ID range."""
        await cache.store_messages(123, _make_messages(range(1, 16)))
        assert await cache.count_messages(123, 1, 15) == 15
        assert await cache.count_messages(123, 5, 10) == 6


class TestCacheDisabled:
    """Cache with enabled=False should be a no-op pass-through."""

    @pytest.mark.asyncio
    async def test_disabled_cache_returns_empty(self):
        """Disabled cache returns empty/default for all queries."""
        cache = MessageCache(enabled=False)
        # No initialize() needed — disabled cache never opens SQLite

        stored = await cache.store_messages(123, _make_messages([1, 2, 3]))
        assert stored == 0
        assert await cache.get_cached_ranges(123) == []
        assert await cache.get_messages(123, 1, 3) == []
        assert await cache.get_missing_ranges(123, 1, 100) == [(1, 100)]
        assert await cache.get_cached_date_ranges(123) == []
        assert await cache.get_messages_by_date(123, "2025-01-01", "2025-01-31") == []


class TestDateIndex:
    """Date-based indexing and retrieval."""

    @pytest.mark.asyncio
    async def test_store_populates_date_index(self, cache):
        """Storing messages creates date range metadata."""
        msgs = [
            _make_msg(1, date="2025-01-05T10:00:00"),
            _make_msg(2, date="2025-01-05T12:00:00"),
            _make_msg(3, date="2025-01-06T09:00:00"),
        ]
        await cache.store_messages(123, msgs)

        # Verify via count_messages_by_date (covers idx_msg_ts)
        count = await cache.count_messages_by_date(123, "2025-01-05", "2025-01-06")
        assert count == 3

    @pytest.mark.asyncio
    async def test_get_messages_by_date_range(self, cache):
        """Retrieve messages by date range."""
        msgs = [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-03T10:00:00"),
            _make_msg(3, date="2025-01-05T10:00:00"),
            _make_msg(4, date="2025-01-07T10:00:00"),
            _make_msg(5, date="2025-01-09T10:00:00"),
        ]
        await cache.store_messages(123, msgs)

        # Get Jan 3 - Jan 7
        retrieved = await cache.get_messages_by_date(123, "2025-01-03", "2025-01-07")
        assert [m.id for m in retrieved] == [2, 3, 4]

    @pytest.mark.asyncio
    async def test_get_messages_by_date_empty(self, cache):
        """Date range with no cached messages returns empty."""
        msgs = [_make_msg(1, date="2025-01-01T10:00:00")]
        await cache.store_messages(123, msgs)

        retrieved = await cache.get_messages_by_date(123, "2025-02-01", "2025-02-28")
        assert retrieved == []

    @pytest.mark.asyncio
    async def test_count_messages_by_date(self, cache):
        """count_messages_by_date returns correct count without loading messages."""
        msgs = [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-03T10:00:00"),
            _make_msg(3, date="2025-01-05T10:00:00"),
            _make_msg(4, date="2025-01-07T10:00:00"),
        ]
        await cache.store_messages(123, msgs)

        count = await cache.count_messages_by_date(123, "2025-01-03", "2025-01-07")
        assert count == 3

    @pytest.mark.asyncio
    async def test_count_messages_by_date_empty(self, cache):
        """count_messages_by_date returns 0 for empty range."""
        msgs = [_make_msg(1, date="2025-01-01T10:00:00")]
        await cache.store_messages(123, msgs)

        count = await cache.count_messages_by_date(123, "2025-02-01", "2025-02-28")
        assert count == 0

    @pytest.mark.asyncio
    async def test_iter_messages_by_date(self, cache):
        """iter_messages_by_date streams only messages in the date range."""
        msgs = [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-03T10:00:00"),
            _make_msg(3, date="2025-01-05T10:00:00"),
            _make_msg(4, date="2025-01-07T10:00:00"),
            _make_msg(5, date="2025-01-09T10:00:00"),
        ]
        await cache.store_messages(123, msgs)

        result = []
        async for msg in cache.iter_messages_by_date(123, "2025-01-03", "2025-01-07"):
            result.append(msg.id)

        assert result == [2, 3, 4]

    @pytest.mark.asyncio
    async def test_iter_messages_by_date_empty(self, cache):
        """iter_messages_by_date yields nothing for out-of-range dates."""
        msgs = [_make_msg(1, date="2025-01-01T10:00:00")]
        await cache.store_messages(123, msgs)

        result = []
        async for msg in cache.iter_messages_by_date(123, "2025-02-01", "2025-02-28"):
            result.append(msg)

        assert result == []


class TestDateRanges:
    """Date range tracking for gap detection."""

    @pytest.mark.asyncio
    async def test_get_cached_date_ranges_empty(self, cache):
        """No cache → empty date ranges."""
        ranges = await cache.get_cached_date_ranges(999)
        assert ranges == []

    @pytest.mark.asyncio
    async def test_get_cached_date_ranges_after_store(self, cache):
        """After storing messages spanning Jan 5-6, date ranges reflect that."""
        msgs = [
            _make_msg(1, date="2025-01-05T10:00:00"),
            _make_msg(2, date="2025-01-06T15:00:00"),
        ]
        await cache.store_messages(123, msgs)
        ranges = await cache.get_cached_date_ranges(123)
        assert ranges == [["2025-01-05", "2025-01-06"]]

    @pytest.mark.asyncio
    async def test_date_ranges_merge_adjacent(self, cache):
        """Store Jan 5-6 then Jan 7-8 → merge to [Jan 5, Jan 8]."""
        await cache.store_messages(123, [
            _make_msg(1, date="2025-01-05T10:00:00"),
            _make_msg(2, date="2025-01-06T10:00:00"),
        ])
        await cache.store_messages(123, [
            _make_msg(3, date="2025-01-07T10:00:00"),
            _make_msg(4, date="2025-01-08T10:00:00"),
        ])
        ranges = await cache.get_cached_date_ranges(123)
        assert ranges == [["2025-01-05", "2025-01-08"]]

    @pytest.mark.asyncio
    async def test_date_ranges_disjoint(self, cache):
        """Store Jan 1-3 and Jan 10-12 → two separate date ranges."""
        await cache.store_messages(123, [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-03T10:00:00"),
        ])
        await cache.store_messages(123, [
            _make_msg(3, date="2025-01-10T10:00:00"),
            _make_msg(4, date="2025-01-12T10:00:00"),
        ])
        ranges = await cache.get_cached_date_ranges(123)
        assert ranges == [["2025-01-01", "2025-01-03"], ["2025-01-10", "2025-01-12"]]

    @pytest.mark.asyncio
    async def test_date_ranges_overlapping(self, cache):
        """Store Jan 1-8 then Jan 5-12 → merge to [Jan 1, Jan 12]."""
        await cache.store_messages(123, [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-08T10:00:00"),
        ])
        await cache.store_messages(123, [
            _make_msg(3, date="2025-01-05T10:00:00"),
            _make_msg(4, date="2025-01-12T10:00:00"),
        ])
        ranges = await cache.get_cached_date_ranges(123)
        assert ranges == [["2025-01-01", "2025-01-12"]]


class TestMissingDateRanges:
    """Gap detection by dates."""

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_no_cache(self, cache):
        """No cache → entire date range missing."""
        missing = await cache.get_missing_date_ranges(999, "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-01", "2025-01-15")]

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_partial(self, cache):
        """Cached Jan 1-8, request Jan 1-15 → missing Jan 9-15."""
        await cache.store_messages(123, [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-08T10:00:00"),
        ])
        missing = await cache.get_missing_date_ranges(123, "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-09", "2025-01-15")]

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_full_cache(self, cache):
        """Cached Jan 1-15, request Jan 1-15 → nothing missing."""
        await cache.store_messages(123, [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-15T10:00:00"),
        ])
        missing = await cache.get_missing_date_ranges(123, "2025-01-01", "2025-01-15")
        assert missing == []

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_multiple_gaps(self, cache):
        """Cached Jan 1-8 and Jan 11-13 → gaps at Jan 9-10 and Jan 14-15."""
        await cache.store_messages(123, [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-08T10:00:00"),
        ])
        await cache.store_messages(123, [
            _make_msg(3, date="2025-01-11T10:00:00"),
            _make_msg(4, date="2025-01-13T10:00:00"),
        ])
        missing = await cache.get_missing_date_ranges(123, "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-09", "2025-01-10"), ("2025-01-14", "2025-01-15")]


class TestVasyaPetyaKolyaScenario:
    """Full E2E scenario.

    1. Vasya exports chat A for Jan 11-13 → cached
    2. Petya exports chat A for Jan 1-8 → cached
    3. Kolya exports chat A for Jan 1-15 → uses cache for 1-8 and 11-13,
       fetches only 9-10 and 14-15, returns complete file
    """

    @pytest.mark.asyncio
    async def test_kolya_gets_full_range_from_partial_caches(self, cache):
        CHAT_A = 100500

        # Vasya: Jan 11-13
        vasya_msgs = [
            _make_msg(11, date="2025-01-11T10:00:00"),
            _make_msg(12, date="2025-01-12T10:00:00"),
            _make_msg(13, date="2025-01-13T10:00:00"),
        ]
        await cache.store_messages(CHAT_A, vasya_msgs)

        # Petya: Jan 1-8
        petya_msgs = [
            _make_msg(i, date=f"2025-01-{i:02d}T10:00:00")
            for i in range(1, 9)
        ]
        await cache.store_messages(CHAT_A, petya_msgs)

        # Verify cached date ranges: [Jan 1-8] and [Jan 11-13]
        date_ranges = await cache.get_cached_date_ranges(CHAT_A)
        assert date_ranges == [["2025-01-01", "2025-01-08"], ["2025-01-11", "2025-01-13"]]

        # Kolya requests Jan 1-15 — find missing date ranges
        missing = await cache.get_missing_date_ranges(CHAT_A, "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-09", "2025-01-10"), ("2025-01-14", "2025-01-15")]

        # Simulate fetching missing ranges from Telegram
        kolya_fresh = [
            _make_msg(9, date="2025-01-09T10:00:00"),
            _make_msg(10, date="2025-01-10T10:00:00"),
            _make_msg(14, date="2025-01-14T10:00:00"),
            _make_msg(15, date="2025-01-15T10:00:00"),
        ]
        await cache.store_messages(CHAT_A, kolya_fresh)

        # Get cached messages for full range
        cached = await cache.get_messages_by_date(CHAT_A, "2025-01-01", "2025-01-15")
        all_msgs = cache.merge_and_sort(cached, [])

        # Kolya gets all 15 messages as one monolithic result
        assert [m.id for m in all_msgs] == list(range(1, 16))

        # Cache now has complete Jan 1-15
        date_ranges = await cache.get_cached_date_ranges(CHAT_A)
        assert date_ranges == [["2025-01-01", "2025-01-15"]]

        # Future user requesting Jan 1-15 → nothing missing
        missing = await cache.get_missing_date_ranges(CHAT_A, "2025-01-01", "2025-01-15")
        assert missing == []

        # Future user requesting Jan 1-20 → only Jan 16-20 missing
        missing = await cache.get_missing_date_ranges(CHAT_A, "2025-01-01", "2025-01-20")
        assert missing == [("2025-01-16", "2025-01-20")]


class TestStoreMessagesAtomicity:
    """REGRESSION S3 — store_messages должен быть атомарным.

    До фикса store_messages делал 3+ независимых commit'а: messages, id_ranges,
    date_ranges, chat_meta. Crash между ними оставлял БД в несогласованном
    состоянии: сообщения есть, но ranges/meta нет. Следующий запрос шёл в
    fallback-miss, перезаписывая те же сообщения, а LRU-эвиктор ошибался в
    размере чата. Теперь — ОДИН commit в конце, rollback при любой ошибке.

    Эти тесты обязаны падать, если кто-то откатит фикс и вернёт промежуточные
    commit'ы в store_messages / _add_range / _add_date_range.
    """

    @pytest.mark.asyncio
    async def test_failure_after_insert_rolls_back_messages(
        self, cache, monkeypatch
    ):
        """Если _add_range падает после INSERT сообщений — ВСЁ откатывается.

        Сценарий: executemany INSERT messages выполнен, но _add_range бросает
        ошибку. В старом коде commit() был ДО _add_range → сообщения уже в БД.
        В новом коде commit() в самом конце → rollback откатывает сообщения.
        """
        CHAT = 777
        messages = _make_messages([1, 2, 3])

        original_add_range = cache._add_range

        async def failing_add_range(chat_id, new_min, new_max):
            # Простая эмуляция сбоя ПОСЛЕ того, как INSERT messages уже выполнен
            # в рамках ещё НЕ закоммиченной транзакции.
            raise RuntimeError("simulated crash during _add_range")

        monkeypatch.setattr(cache, "_add_range", failing_add_range)

        with pytest.raises(RuntimeError, match="simulated crash"):
            await cache.store_messages(CHAT, messages)

        # Восстанавливаем метод, чтобы дальше можно было читать
        monkeypatch.setattr(cache, "_add_range", original_add_range)

        # КЛЮЧЕВОЕ: сообщения НЕ должны остаться в БД — транзакция откатилась.
        retrieved = await cache.get_messages(CHAT, 0, 1_000_000)
        assert retrieved == [], (
            "Сообщения остались в БД после отката. Либо commit() вызван до "
            "_add_range (старый баг), либо rollback() не сработал."
        )

        # Ranges тоже не должны появиться
        id_ranges = await cache.get_cached_ranges(CHAT)
        assert id_ranges == []

        date_ranges = await cache.get_cached_date_ranges(CHAT)
        assert date_ranges == []

    @pytest.mark.asyncio
    async def test_failure_after_date_range_rolls_back_everything(
        self, cache, monkeypatch
    ):
        """Если _add_date_range падает — rollback откатывает messages И id_ranges."""
        CHAT = 888
        messages = _make_messages([10, 11, 12])

        async def failing_add_date_range(chat_id, new_from, new_to):
            raise RuntimeError("simulated crash during _add_date_range")

        monkeypatch.setattr(cache, "_add_date_range", failing_add_date_range)

        with pytest.raises(RuntimeError, match="simulated crash"):
            await cache.store_messages(CHAT, messages)

        # Ничего не должно остаться: ни сообщений, ни id_ranges, ни date_ranges
        assert await cache.get_messages(CHAT, 0, 1_000_000) == []
        assert await cache.get_cached_ranges(CHAT) == []
        assert await cache.get_cached_date_ranges(CHAT) == []

    @pytest.mark.asyncio
    async def test_successful_store_commits_everything_in_one_transaction(
        self, cache
    ):
        """Happy path: все 4 группы записей видны после успешного store_messages."""
        CHAT = 999
        messages = [
            _make_msg(1, date="2025-03-01T10:00:00"),
            _make_msg(2, date="2025-03-02T10:00:00"),
            _make_msg(3, date="2025-03-03T10:00:00"),
        ]
        count = await cache.store_messages(CHAT, messages)
        assert count == 3

        # Messages
        assert len(await cache.get_messages(CHAT, 1, 3)) == 3
        # ID ranges
        assert await cache.get_cached_ranges(CHAT) == [[1, 3]]
        # Date ranges
        assert await cache.get_cached_date_ranges(CHAT) == [["2025-03-01", "2025-03-03"]]
        # Meta populated (LRU tracking)
        async with cache._db.execute(
            "SELECT msg_count, size_bytes FROM chat_meta WHERE chat_id=?", (CHAT,)
        ) as cur:
            row = await cur.fetchone()
        assert row is not None
        assert row[0] == 3  # msg_count
        assert row[1] > 0   # size_bytes

    @pytest.mark.asyncio
    async def test_rollback_leaves_previously_stored_data_intact(
        self, cache, monkeypatch
    ):
        """Откат неудачного store_messages НЕ должен трогать уже сохранённые данные.

        Это критично: если первый батч прошёл, а второй упал — первый должен
        остаться нетронутым.
        """
        CHAT = 1111
        # Первый успешный батч
        first = _make_messages([1, 2, 3])
        await cache.store_messages(CHAT, first)

        # Второй батч — падает в _add_range
        second = _make_messages([100, 101, 102])

        async def failing_add_range(chat_id, new_min, new_max):
            raise RuntimeError("second batch must fail")

        monkeypatch.setattr(cache, "_add_range", failing_add_range)

        with pytest.raises(RuntimeError):
            await cache.store_messages(CHAT, second)

        monkeypatch.undo()

        # Первый батч остался
        retrieved = await cache.get_messages(CHAT, 1, 1_000_000)
        ids = sorted(m.id for m in retrieved)
        assert ids == [1, 2, 3], (
            f"Откат второго батча снёс первый. Получено: {ids}. "
            "Это значит rollback слишком широкий — либо первый батч тоже был "
            "в ещё-не-закоммиченной транзакции, что невозможно после фикса."
        )
        assert await cache.get_cached_ranges(CHAT) == [[1, 3]]


class TestMarkDateRangeChecked:
    """mark_date_range_checked — явная регистрация проверенного диапазона без сообщений."""

    @pytest.mark.asyncio
    async def test_marks_empty_range_as_covered(self, cache):
        """Диапазон без сообщений помечается покрытым — повторный get_missing_date_ranges его не возвращает."""
        await cache.mark_date_range_checked(123, "2025-04-05", "2025-04-08")
        missing = await cache.get_missing_date_ranges(123, "2025-04-05", "2025-04-08")
        assert missing == []

    @pytest.mark.asyncio
    async def test_merges_with_existing_date_ranges(self, cache):
        """mark_date_range_checked корректно мержит с уже существующими диапазонами."""
        msgs = [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-03T10:00:00"),
        ]
        await cache.store_messages(123, msgs)

        # mark смежный диапазон Jan 4-6
        await cache.mark_date_range_checked(123, "2025-01-04", "2025-01-06")

        # Они должны слиться в [Jan 1, Jan 6]
        ranges = await cache.get_cached_date_ranges(123)
        assert len(ranges) == 1
        assert ranges[0] == ["2025-01-01", "2025-01-06"]

    @pytest.mark.asyncio
    async def test_noop_when_disabled(self):
        """Отключённый кэш — mark_date_range_checked не падает."""
        cache = MessageCache(enabled=False)
        await cache.mark_date_range_checked(123, "2025-01-01", "2025-01-07")
        # Нет исключения — тест пройден
