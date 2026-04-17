
import time
import pytest
import asyncio
from unittest.mock import patch

import aiosqlite

from models import ExportedMessage
from message_cache import MessageCache

def _make_msg(msg_id: int, text: str = "", date: str = "2025-01-01T00:00:00") -> ExportedMessage:
    return ExportedMessage(id=msg_id, type="message", date=date, text=text or f"msg_{msg_id}")

def _make_messages(ids) -> list[ExportedMessage]:
    return [_make_msg(i) for i in ids]

@pytest.fixture
async def cache(tmp_path):
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

    @pytest.mark.asyncio
    async def test_store_and_retrieve_messages(self, cache):
        messages = _make_messages([1, 2, 3, 4, 5])
        stored = await cache.store_messages(123, messages)
        assert stored == 5

        retrieved = await cache.get_messages(123, 1, 5)
        assert len(retrieved) == 5
        assert [m.id for m in retrieved] == [1, 2, 3, 4, 5]
        assert retrieved[0].text == "msg_1"

    @pytest.mark.asyncio
    async def test_retrieve_partial_range(self, cache):
        await cache.store_messages(123, _make_messages([1, 2, 3, 4, 5]))
        retrieved = await cache.get_messages(123, 2, 4)
        assert [m.id for m in retrieved] == [2, 3, 4]

    @pytest.mark.asyncio
    async def test_retrieve_empty_chat(self, cache):
        retrieved = await cache.get_messages(999, 1, 100)
        assert retrieved == []

    @pytest.mark.asyncio
    async def test_store_overwrites_duplicates(self, cache):
        msg_v1 = _make_msg(1, text="version 1")
        msg_v2 = _make_msg(1, text="version 2")
        await cache.store_messages(123, [msg_v1])
        await cache.store_messages(123, [msg_v2])

        retrieved = await cache.get_messages(123, 1, 1)
        assert len(retrieved) == 1
        assert retrieved[0].text == "version 2"

class TestCachedRanges:

    @pytest.mark.asyncio
    async def test_get_cached_ranges_empty(self, cache):
        ranges = await cache.get_cached_ranges(999)
        assert ranges == []

    @pytest.mark.asyncio
    async def test_get_cached_ranges_after_store(self, cache):
        await cache.store_messages(123, _make_messages([1, 2, 3, 4, 5]))
        ranges = await cache.get_cached_ranges(123)
        assert ranges == [[1, 5]]

    @pytest.mark.asyncio
    async def test_get_cached_ranges_disjoint(self, cache):
        await cache.store_messages(123, _make_messages([1, 2, 3]))
        await cache.store_messages(123, _make_messages([10, 11, 12]))
        ranges = await cache.get_cached_ranges(123)
        assert ranges == [[1, 3], [10, 12]]

    @pytest.mark.asyncio
    async def test_ranges_merge_adjacent(self, cache):
        await cache.store_messages(123, _make_messages([1, 2, 3]))
        await cache.store_messages(123, _make_messages([4, 5, 6]))
        ranges = await cache.get_cached_ranges(123)
        assert ranges == [[1, 6]]

    @pytest.mark.asyncio
    async def test_ranges_merge_overlapping(self, cache):
        await cache.store_messages(123, _make_messages([1, 2, 3, 4, 5]))
        await cache.store_messages(123, _make_messages([3, 4, 5, 6, 7, 8]))
        ranges = await cache.get_cached_ranges(123)
        assert ranges == [[1, 8]]

class TestMissingRanges:

    @pytest.mark.asyncio
    async def test_get_missing_ranges_no_cache(self, cache):
        missing = await cache.get_missing_ranges(999, 1, 1000)
        assert missing == [(1, 1000)]

    @pytest.mark.asyncio
    async def test_get_missing_ranges_partial(self, cache):
        await cache.store_messages(123, _make_messages(range(300, 501)))
        missing = await cache.get_missing_ranges(123, 1, 1000)
        assert missing == [(1, 299), (501, 1000)]

    @pytest.mark.asyncio
    async def test_get_missing_ranges_full_cache(self, cache):
        await cache.store_messages(123, _make_messages(range(1, 101)))
        missing = await cache.get_missing_ranges(123, 1, 100)
        assert missing == []

    @pytest.mark.asyncio
    async def test_get_missing_ranges_multiple_gaps(self, cache):
        await cache.store_messages(123, _make_messages(range(10, 21)))
        await cache.store_messages(123, _make_messages(range(50, 61)))
        missing = await cache.get_missing_ranges(123, 1, 100)
        assert missing == [(1, 9), (21, 49), (61, 100)]

class TestMergeAndSort:

    @pytest.mark.asyncio
    async def test_merge_and_sort_dedup(self, cache):
        cached = _make_messages([1, 3, 5])
        fresh = _make_messages([2, 3, 4])  # id=3 is duplicate
        merged = cache.merge_and_sort(cached, fresh)
        assert [m.id for m in merged] == [1, 2, 3, 4, 5]

    @pytest.mark.asyncio
    async def test_merge_empty_cached(self, cache):
        merged = cache.merge_and_sort([], _make_messages([5, 3, 1]))
        assert [m.id for m in merged] == [1, 3, 5]

    @pytest.mark.asyncio
    async def test_merge_empty_fresh(self, cache):
        merged = cache.merge_and_sort(_make_messages([3, 1, 2]), [])
        assert [m.id for m in merged] == [1, 2, 3]

class TestMsgpackSerialization:

    @pytest.mark.asyncio
    async def test_msgpack_roundtrip(self, cache):
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
        msg = _make_msg(1, text="Привет мир 🎉🔥")
        await cache.store_messages(123, [msg])
        retrieved = await cache.get_messages(123, 1, 1)
        assert retrieved[0].text == "Привет мир 🎉🔥"

class TestLRUTracking:

    @pytest.mark.asyncio
    async def test_last_accessed_set_on_store(self, cache):
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

    @pytest.mark.asyncio
    async def test_eviction_oldest_chat(self, tmp_path):
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
        await cache.store_messages(123, _make_messages(range(1, 16)))
        assert await cache.count_messages(123, 1, 15) == 15
        assert await cache.count_messages(123, 5, 10) == 6

    @pytest.mark.asyncio
    async def test_evict_retries_on_busy(self, tmp_path):
        cache = MessageCache(db_path=str(tmp_path / "retry.db"), max_disk_bytes=0)
        await cache.initialize()
        try:
            call_count = 0
            raised_errors: list[aiosqlite.OperationalError] = []
            original = cache._evict_impl

            async def flaky_evict():
                nonlocal call_count
                call_count += 1
                if call_count < 3:
                    err = aiosqlite.OperationalError("database is locked")
                    raised_errors.append(err)
                    raise err
                return await original()

            with patch.object(cache, "_evict_impl", side_effect=flaky_evict):
                result = await cache.evict_if_needed(max_retries=3)

            assert call_count == 3
            assert len(raised_errors) == 2  # первые 2 попытки упали с busy
            assert all("locked" in str(e) for e in raised_errors)
            assert result == 0  # бюджет 0 → таргет 0 → nothing to evict
        finally:
            await cache.close()

    @pytest.mark.asyncio
    async def test_evict_gives_up_after_max_retries(self, tmp_path, caplog):
        cache = MessageCache(db_path=str(tmp_path / "busy.db"), max_disk_bytes=0)
        await cache.initialize()
        try:
            async def always_busy():
                raise aiosqlite.OperationalError("database is locked")

            with patch.object(cache, "_evict_impl", side_effect=always_busy):
                with caplog.at_level("WARNING"):
                    result = await cache.evict_if_needed(max_retries=3)

            assert result == 0
            assert any("busy" in r.message.lower() for r in caplog.records)
        finally:
            await cache.close()

    @pytest.mark.asyncio
    async def test_evict_reraises_other_errors(self, tmp_path):
        cache = MessageCache(db_path=str(tmp_path / "err.db"), max_disk_bytes=0)
        await cache.initialize()
        try:
            async def bad_error():
                raise aiosqlite.OperationalError("no such table: chat_meta")

            with patch.object(cache, "_evict_impl", side_effect=bad_error):
                with pytest.raises(aiosqlite.OperationalError, match="no such table"):
                    await cache.evict_if_needed(max_retries=3)
        finally:
            await cache.close()

class TestCacheDisabled:

    @pytest.mark.asyncio
    async def test_disabled_cache_returns_empty(self):
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

    @pytest.mark.asyncio
    async def test_store_populates_date_index(self, cache):
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
        msgs = [_make_msg(1, date="2025-01-01T10:00:00")]
        await cache.store_messages(123, msgs)

        retrieved = await cache.get_messages_by_date(123, "2025-02-01", "2025-02-28")
        assert retrieved == []

    @pytest.mark.asyncio
    async def test_count_messages_by_date(self, cache):
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
        msgs = [_make_msg(1, date="2025-01-01T10:00:00")]
        await cache.store_messages(123, msgs)

        count = await cache.count_messages_by_date(123, "2025-02-01", "2025-02-28")
        assert count == 0

    @pytest.mark.asyncio
    async def test_iter_messages_by_date(self, cache):
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
        msgs = [_make_msg(1, date="2025-01-01T10:00:00")]
        await cache.store_messages(123, msgs)

        result = []
        async for msg in cache.iter_messages_by_date(123, "2025-02-01", "2025-02-28"):
            result.append(msg)

        assert result == []

class TestDateRanges:

    @pytest.mark.asyncio
    async def test_get_cached_date_ranges_empty(self, cache):
        ranges = await cache.get_cached_date_ranges(999)
        assert ranges == []

    @pytest.mark.asyncio
    async def test_get_cached_date_ranges_after_store(self, cache):
        msgs = [
            _make_msg(1, date="2025-01-05T10:00:00"),
            _make_msg(2, date="2025-01-06T15:00:00"),
        ]
        await cache.store_messages(123, msgs)
        ranges = await cache.get_cached_date_ranges(123)
        assert ranges == [["2025-01-05", "2025-01-06"]]

    @pytest.mark.asyncio
    async def test_date_ranges_merge_adjacent(self, cache):
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

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_no_cache(self, cache):
        missing = await cache.get_missing_date_ranges(999, "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-01", "2025-01-15")]

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_partial(self, cache):
        await cache.store_messages(123, [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-08T10:00:00"),
        ])
        missing = await cache.get_missing_date_ranges(123, "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-09", "2025-01-15")]

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_full_cache(self, cache):
        await cache.store_messages(123, [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-15T10:00:00"),
        ])
        missing = await cache.get_missing_date_ranges(123, "2025-01-01", "2025-01-15")
        assert missing == []

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_multiple_gaps(self, cache):
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

    @pytest.mark.asyncio
    async def test_failure_after_insert_rolls_back_messages(
        self, cache, monkeypatch
    ):
        CHAT = 777
        messages = _make_messages([1, 2, 3])

        original_add_range = cache._add_range

        async def failing_add_range(chat_id, topic_id, new_min, new_max):
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
        CHAT = 888
        messages = _make_messages([10, 11, 12])

        async def failing_add_date_range(chat_id, topic_id, new_from, new_to):
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
        CHAT = 1111
        # Первый успешный батч
        first = _make_messages([1, 2, 3])
        await cache.store_messages(CHAT, first)

        # Второй батч — падает в _add_range
        second = _make_messages([100, 101, 102])

        async def failing_add_range(chat_id, topic_id, new_min, new_max):
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

    @pytest.mark.asyncio
    async def test_marks_empty_range_as_covered(self, cache):
        await cache.mark_date_range_checked(123, "2025-04-05", "2025-04-08")
        missing = await cache.get_missing_date_ranges(123, "2025-04-05", "2025-04-08")
        assert missing == []

    @pytest.mark.asyncio
    async def test_merges_with_existing_date_ranges(self, cache):
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
        cache = MessageCache(enabled=False)
        await cache.mark_date_range_checked(123, "2025-01-01", "2025-01-07")
        # Нет исключения — тест пройден


class TestTopicIsolation:
    """Тесты изоляции данных между topic_id=0 (весь чат) и конкретными топиками."""

    @pytest.mark.asyncio
    async def test_messages_isolated_by_topic_id(self, cache):
        msg_chat = _make_msg(1, text="whole chat")
        msg_topic = _make_msg(1, text="topic msg")

        await cache.store_messages(100, [msg_chat], topic_id=0)
        await cache.store_messages(100, [msg_topic], topic_id=42)

        chat_msgs = await cache.get_messages(100, 0, 10, topic_id=0)
        topic_msgs = await cache.get_messages(100, 0, 10, topic_id=42)

        assert len(chat_msgs) == 1
        assert chat_msgs[0].text == "whole chat"
        assert len(topic_msgs) == 1
        assert topic_msgs[0].text == "topic msg"

    @pytest.mark.asyncio
    async def test_count_messages_isolated_by_topic(self, cache):
        msgs_chat = [_make_msg(i, text=f"c{i}") for i in range(1, 6)]
        msgs_topic = [_make_msg(i, text=f"t{i}") for i in range(1, 4)]

        await cache.store_messages(100, msgs_chat, topic_id=0)
        await cache.store_messages(100, msgs_topic, topic_id=42)

        assert await cache.count_messages(100, 0, 100, topic_id=0) == 5
        assert await cache.count_messages(100, 0, 100, topic_id=42) == 3

    @pytest.mark.asyncio
    async def test_id_ranges_isolated_by_topic(self, cache):
        await cache.store_messages(100, _make_messages([1, 2, 3]), topic_id=0)
        await cache.store_messages(100, _make_messages([10, 11, 12]), topic_id=42)

        assert await cache.get_cached_ranges(100, topic_id=0) == [[1, 3]]
        assert await cache.get_cached_ranges(100, topic_id=42) == [[10, 12]]

    @pytest.mark.asyncio
    async def test_date_ranges_isolated_by_topic(self, cache):
        msg = _make_msg(1, date="2025-01-15T12:00:00")
        await cache.store_messages(100, [msg], topic_id=0)
        await cache.mark_date_range_checked(100, "2025-01-01", "2025-01-31", topic_id=0)

        # topic_id=0 — диапазон покрыт
        missing_chat = await cache.get_missing_date_ranges(
            100, "2025-01-01", "2025-01-31", topic_id=0
        )
        # topic_id=42 — ничего нет
        missing_topic = await cache.get_missing_date_ranges(
            100, "2025-01-01", "2025-01-31", topic_id=42
        )

        assert missing_chat == []
        assert missing_topic == [("2025-01-01", "2025-01-31")]

    @pytest.mark.asyncio
    async def test_iter_messages_by_date_isolated(self, cache):
        msg_chat = _make_msg(1, date="2025-01-15T12:00:00", text="chat")
        msg_topic = _make_msg(2, date="2025-01-15T12:00:00", text="topic")

        await cache.store_messages(100, [msg_chat], topic_id=0)
        await cache.store_messages(100, [msg_topic], topic_id=99)

        chat_result = await cache.get_messages_by_date(100, "2025-01-15", "2025-01-15", topic_id=0)
        topic_result = await cache.get_messages_by_date(100, "2025-01-15", "2025-01-15", topic_id=99)

        assert len(chat_result) == 1 and chat_result[0].text == "chat"
        assert len(topic_result) == 1 and topic_result[0].text == "topic"

    @pytest.mark.asyncio
    async def test_default_topic_id_zero(self, cache):
        """Без указания topic_id используется 0 (обратная совместимость)."""
        await cache.store_messages(100, _make_messages([1, 2, 3]))
        msgs = await cache.get_messages(100, 0, 10)
        assert len(msgs) == 3

        # Явно запросить topic_id=0 — тот же результат
        msgs_explicit = await cache.get_messages(100, 0, 10, topic_id=0)
        assert len(msgs_explicit) == 3


class TestReopenPersistence:
    # Кэш лежит на хосте (bind mount). После рестарта/редеплоя контейнера
    # SQLite-файл тот же — данные и id-ranges должны сохраниться,
    # повторный initialize() на тот же путь не сбрасывает схему.

    def _make_cache(self, db_path: str) -> MessageCache:
        return MessageCache(db_path=db_path, max_disk_bytes=10 * 1024 * 1024,
                            max_messages_per_chat=1000, ttl_seconds=3600)

    @pytest.mark.asyncio
    async def test_reopen_existing_db_preserves_data(self, tmp_path):
        db_path = str(tmp_path / "reopen.db")

        first = self._make_cache(db_path)
        await first.initialize()
        await first.store_messages(555, _make_messages([1, 2, 3]))
        await first.close()

        second = self._make_cache(db_path)
        await second.initialize()
        try:
            msgs = await second.get_messages(555, 1, 3)
            assert [m.id for m in msgs] == [1, 2, 3]
            assert msgs[0].text == "msg_1"

            ranges = await second.get_cached_ranges(555)
            assert ranges == [[1, 3]]
        finally:
            await second.close()

    @pytest.mark.asyncio
    async def test_reopen_after_full_export_workflow(self, tmp_path):
        # Эмуляция цикла: воркер поднялся, закэшировал чат, упал/был рестартнут,
        # воркер поднялся снова — повторный экспорт того же диапазона должен
        # быть cache HIT (get_missing_ranges → []), никаких запросов к Telegram.
        db_path = str(tmp_path / "workflow.db")

        first = self._make_cache(db_path)
        await first.initialize()
        await first.store_messages(777, _make_messages(list(range(100, 201))))
        await first.close()

        second = self._make_cache(db_path)
        await second.initialize()
        try:
            missing = await second.get_missing_ranges(777, 100, 200)
            assert missing == []

            count = await second.count_messages(777, 100, 200)
            assert count == 101
        finally:
            await second.close()
