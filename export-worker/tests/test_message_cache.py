"""
Tests for MessageCache — Redis-backed message caching with sorted sets.

TDD: these tests are written BEFORE the implementation.
All tests use fakeredis for async Redis mock.
"""

import json
import time
import pytest
import msgpack
from unittest.mock import AsyncMock, patch

from models import ExportedMessage
from message_cache import MessageCache


def _make_msg(msg_id: int, text: str = "", date: str = "2025-01-01T00:00:00") -> ExportedMessage:
    """Helper: create ExportedMessage with given id."""
    return ExportedMessage(id=msg_id, type="message", date=date, text=text or f"msg_{msg_id}")


def _make_messages(ids: list[int]) -> list[ExportedMessage]:
    """Helper: create list of ExportedMessage with given ids."""
    return [_make_msg(i) for i in ids]


@pytest.fixture
async def redis_client():
    """Async fakeredis client."""
    import fakeredis.aioredis
    client = fakeredis.aioredis.FakeRedis(decode_responses=False)
    yield client
    await client.aclose()


@pytest.fixture
async def cache(redis_client):
    """MessageCache with fakeredis and short TTL for testing."""
    return MessageCache(
        redis_client=redis_client,
        ttl_seconds=3600,
        max_memory_mb=120,
        max_messages_per_chat=1000,
    )


class TestStoreAndRetrieve:
    """Basic round-trip: store messages, retrieve them."""

    @pytest.mark.asyncio
    async def test_store_and_retrieve_messages(self, cache):
        """Store messages, then retrieve by range — should get same data back."""
        messages = _make_messages([1, 2, 3, 4, 5])
        stored = await cache.store_messages("chat_123", messages)
        assert stored == 5

        retrieved = await cache.get_messages("chat_123", 1, 5)
        assert len(retrieved) == 5
        assert [m.id for m in retrieved] == [1, 2, 3, 4, 5]
        assert retrieved[0].text == "msg_1"

    @pytest.mark.asyncio
    async def test_retrieve_partial_range(self, cache):
        """Retrieve a subset of cached messages."""
        await cache.store_messages("chat_123", _make_messages([1, 2, 3, 4, 5]))
        retrieved = await cache.get_messages("chat_123", 2, 4)
        assert [m.id for m in retrieved] == [2, 3, 4]

    @pytest.mark.asyncio
    async def test_retrieve_empty_chat(self, cache):
        """Retrieve from non-existent chat returns empty list."""
        retrieved = await cache.get_messages("chat_999", 1, 100)
        assert retrieved == []

    @pytest.mark.asyncio
    async def test_store_overwrites_duplicates(self, cache):
        """Storing same msg_id twice overwrites (sorted set behavior)."""
        msg_v1 = _make_msg(1, text="version 1")
        msg_v2 = _make_msg(1, text="version 2")
        await cache.store_messages("chat_123", [msg_v1])
        await cache.store_messages("chat_123", [msg_v2])

        retrieved = await cache.get_messages("chat_123", 1, 1)
        assert len(retrieved) == 1
        assert retrieved[0].text == "version 2"


class TestCachedRanges:
    """Range tracking metadata."""

    @pytest.mark.asyncio
    async def test_get_cached_ranges_empty(self, cache):
        """No cache → empty ranges."""
        ranges = await cache.get_cached_ranges("chat_999")
        assert ranges == []

    @pytest.mark.asyncio
    async def test_get_cached_ranges_after_store(self, cache):
        """After storing [1-5], ranges should be [[1,5]]."""
        await cache.store_messages("chat_123", _make_messages([1, 2, 3, 4, 5]))
        ranges = await cache.get_cached_ranges("chat_123")
        assert ranges == [[1, 5]]

    @pytest.mark.asyncio
    async def test_get_cached_ranges_disjoint(self, cache):
        """Store two disjoint batches → two separate ranges."""
        await cache.store_messages("chat_123", _make_messages([1, 2, 3]))
        await cache.store_messages("chat_123", _make_messages([10, 11, 12]))
        ranges = await cache.get_cached_ranges("chat_123")
        assert ranges == [[1, 3], [10, 12]]

    @pytest.mark.asyncio
    async def test_ranges_merge_adjacent(self, cache):
        """Store [1-3] then [4-6] → should merge to [[1,6]]."""
        await cache.store_messages("chat_123", _make_messages([1, 2, 3]))
        await cache.store_messages("chat_123", _make_messages([4, 5, 6]))
        ranges = await cache.get_cached_ranges("chat_123")
        assert ranges == [[1, 6]]

    @pytest.mark.asyncio
    async def test_ranges_merge_overlapping(self, cache):
        """Store [1-5] then [3-8] → should merge to [[1,8]]."""
        await cache.store_messages("chat_123", _make_messages([1, 2, 3, 4, 5]))
        await cache.store_messages("chat_123", _make_messages([3, 4, 5, 6, 7, 8]))
        ranges = await cache.get_cached_ranges("chat_123")
        assert ranges == [[1, 8]]


class TestMissingRanges:
    """Gap detection: what ranges are NOT in cache."""

    @pytest.mark.asyncio
    async def test_get_missing_ranges_no_cache(self, cache):
        """No cache → entire requested range is missing."""
        missing = await cache.get_missing_ranges("chat_999", 1, 1000)
        assert missing == [(1, 1000)]

    @pytest.mark.asyncio
    async def test_get_missing_ranges_partial(self, cache):
        """[1-1000] requested, [300-500] cached → gaps at [1-299] and [501-1000]."""
        await cache.store_messages("chat_123", _make_messages(range(300, 501)))
        missing = await cache.get_missing_ranges("chat_123", 1, 1000)
        assert missing == [(1, 299), (501, 1000)]

    @pytest.mark.asyncio
    async def test_get_missing_ranges_full_cache(self, cache):
        """Everything cached → no missing ranges."""
        await cache.store_messages("chat_123", _make_messages(range(1, 101)))
        missing = await cache.get_missing_ranges("chat_123", 1, 100)
        assert missing == []

    @pytest.mark.asyncio
    async def test_get_missing_ranges_multiple_gaps(self, cache):
        """Cached [10-20] and [50-60] → gaps at start, middle, end."""
        await cache.store_messages("chat_123", _make_messages(range(10, 21)))
        await cache.store_messages("chat_123", _make_messages(range(50, 61)))
        missing = await cache.get_missing_ranges("chat_123", 1, 100)
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
        await cache.store_messages("chat_123", [msg])
        retrieved = await cache.get_messages("chat_123", 42, 42)

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
        await cache.store_messages("chat_123", [msg])
        retrieved = await cache.get_messages("chat_123", 1, 1)
        assert retrieved[0].text == "Привет мир 🎉🔥"


class TestTTL:
    """TTL behavior: refresh on access."""

    @pytest.mark.asyncio
    async def test_ttl_set_on_store(self, cache, redis_client):
        """Storing messages sets TTL on all keys."""
        await cache.store_messages("chat_123", _make_messages([1, 2, 3]))

        ttl_msgs = await redis_client.ttl("cache:msgs:chat_123")
        ttl_ranges = await redis_client.ttl("cache:ranges:chat_123")
        ttl_meta = await redis_client.ttl("cache:meta:chat_123")

        assert ttl_msgs > 0
        assert ttl_ranges > 0
        assert ttl_meta > 0

    @pytest.mark.asyncio
    async def test_ttl_refresh_on_read(self, cache, redis_client):
        """Reading messages refreshes TTL."""
        await cache.store_messages("chat_123", _make_messages([1, 2, 3]))

        # Artificially lower TTL
        await redis_client.expire("cache:msgs:chat_123", 60)
        assert await redis_client.ttl("cache:msgs:chat_123") <= 60

        # Read should refresh
        await cache.get_messages("chat_123", 1, 3)
        ttl_after = await redis_client.ttl("cache:msgs:chat_123")
        assert ttl_after > 60


class TestEviction:
    """Memory management: evict oldest chats."""

    @pytest.mark.asyncio
    async def test_eviction_oldest_chat(self, redis_client):
        """Oldest-accessed chat evicted first when over memory budget."""
        cache = MessageCache(
            redis_client=redis_client,
            ttl_seconds=3600,
            max_memory_mb=0,  # Force eviction immediately
            max_messages_per_chat=1000,
        )

        # Store in two chats
        await cache.store_messages("chat_old", _make_messages([1, 2, 3]))
        await cache.store_messages("chat_new", _make_messages([1, 2, 3]))

        # Touch chat_new to make it newer
        await cache.get_messages("chat_new", 1, 3)

        evicted = await cache.evict_if_needed()
        assert evicted >= 1

        # chat_old should be evicted first
        ranges_old = await cache.get_cached_ranges("chat_old")
        assert ranges_old == []

    @pytest.mark.asyncio
    async def test_per_chat_cap(self, redis_client):
        """Messages beyond max_messages_per_chat are trimmed (oldest removed)."""
        cache = MessageCache(
            redis_client=redis_client,
            ttl_seconds=3600,
            max_memory_mb=120,
            max_messages_per_chat=10,
        )

        # Store 15 messages (cap is 10)
        await cache.store_messages("chat_123", _make_messages(range(1, 16)))

        # Should keep only newest 10 (ids 6-15)
        retrieved = await cache.get_messages("chat_123", 1, 15)
        assert len(retrieved) == 10
        assert retrieved[0].id == 6
        assert retrieved[-1].id == 15


class TestCacheDisabled:
    """Cache with enabled=False should be a no-op pass-through."""

    @pytest.mark.asyncio
    async def test_disabled_cache_returns_empty(self, redis_client):
        """Disabled cache returns empty for all queries."""
        cache = MessageCache(
            redis_client=redis_client,
            ttl_seconds=3600,
            max_memory_mb=120,
            max_messages_per_chat=1000,
            enabled=False,
        )

        await cache.store_messages("chat_123", _make_messages([1, 2, 3]))
        assert await cache.get_cached_ranges("chat_123") == []
        assert await cache.get_messages("chat_123", 1, 3) == []
        assert await cache.get_missing_ranges("chat_123", 1, 100) == [(1, 100)]
        assert await cache.get_cached_date_ranges("chat_123") == []
        assert await cache.get_messages_by_date("chat_123", "2025-01-01", "2025-01-31") == []


class TestDateIndex:
    """Date-based indexing and retrieval."""

    @pytest.mark.asyncio
    async def test_store_populates_date_index(self, cache, redis_client):
        """Storing messages creates date index entries."""
        msgs = [
            _make_msg(1, date="2025-01-05T10:00:00"),
            _make_msg(2, date="2025-01-05T12:00:00"),
            _make_msg(3, date="2025-01-06T09:00:00"),
        ]
        await cache.store_messages("chat_123", msgs)

        # Date index sorted set should have 3 entries
        count = await redis_client.zcard("cache:dates:chat_123")
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
        await cache.store_messages("chat_123", msgs)

        # Get Jan 3 - Jan 7
        retrieved = await cache.get_messages_by_date("chat_123", "2025-01-03", "2025-01-07")
        assert [m.id for m in retrieved] == [2, 3, 4]

    @pytest.mark.asyncio
    async def test_get_messages_by_date_empty(self, cache):
        """Date range with no cached messages returns empty."""
        msgs = [_make_msg(1, date="2025-01-01T10:00:00")]
        await cache.store_messages("chat_123", msgs)

        retrieved = await cache.get_messages_by_date("chat_123", "2025-02-01", "2025-02-28")
        assert retrieved == []


class TestDateRanges:
    """Date range tracking for gap detection."""

    @pytest.mark.asyncio
    async def test_get_cached_date_ranges_empty(self, cache):
        """No cache → empty date ranges."""
        ranges = await cache.get_cached_date_ranges("chat_999")
        assert ranges == []

    @pytest.mark.asyncio
    async def test_get_cached_date_ranges_after_store(self, cache):
        """After storing messages spanning Jan 5-6, date ranges reflect that."""
        msgs = [
            _make_msg(1, date="2025-01-05T10:00:00"),
            _make_msg(2, date="2025-01-06T15:00:00"),
        ]
        await cache.store_messages("chat_123", msgs)
        ranges = await cache.get_cached_date_ranges("chat_123")
        assert ranges == [["2025-01-05", "2025-01-06"]]

    @pytest.mark.asyncio
    async def test_date_ranges_merge_adjacent(self, cache):
        """Store Jan 5-6 then Jan 7-8 → merge to [Jan 5, Jan 8]."""
        await cache.store_messages("chat_123", [
            _make_msg(1, date="2025-01-05T10:00:00"),
            _make_msg(2, date="2025-01-06T10:00:00"),
        ])
        await cache.store_messages("chat_123", [
            _make_msg(3, date="2025-01-07T10:00:00"),
            _make_msg(4, date="2025-01-08T10:00:00"),
        ])
        ranges = await cache.get_cached_date_ranges("chat_123")
        assert ranges == [["2025-01-05", "2025-01-08"]]

    @pytest.mark.asyncio
    async def test_date_ranges_disjoint(self, cache):
        """Store Jan 1-3 and Jan 10-12 → two separate date ranges."""
        await cache.store_messages("chat_123", [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-03T10:00:00"),
        ])
        await cache.store_messages("chat_123", [
            _make_msg(3, date="2025-01-10T10:00:00"),
            _make_msg(4, date="2025-01-12T10:00:00"),
        ])
        ranges = await cache.get_cached_date_ranges("chat_123")
        assert ranges == [["2025-01-01", "2025-01-03"], ["2025-01-10", "2025-01-12"]]

    @pytest.mark.asyncio
    async def test_date_ranges_overlapping(self, cache):
        """Store Jan 1-8 then Jan 5-12 → merge to [Jan 1, Jan 12]."""
        await cache.store_messages("chat_123", [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-08T10:00:00"),
        ])
        await cache.store_messages("chat_123", [
            _make_msg(3, date="2025-01-05T10:00:00"),
            _make_msg(4, date="2025-01-12T10:00:00"),
        ])
        ranges = await cache.get_cached_date_ranges("chat_123")
        assert ranges == [["2025-01-01", "2025-01-12"]]


class TestMissingDateRanges:
    """Gap detection by dates."""

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_no_cache(self, cache):
        """No cache → entire date range missing."""
        missing = await cache.get_missing_date_ranges("chat_999", "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-01", "2025-01-15")]

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_partial(self, cache):
        """Cached Jan 1-8, request Jan 1-15 → missing Jan 9-15."""
        await cache.store_messages("chat_123", [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-08T10:00:00"),
        ])
        missing = await cache.get_missing_date_ranges("chat_123", "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-09", "2025-01-15")]

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_full_cache(self, cache):
        """Cached Jan 1-15, request Jan 1-15 → nothing missing."""
        await cache.store_messages("chat_123", [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-15T10:00:00"),
        ])
        missing = await cache.get_missing_date_ranges("chat_123", "2025-01-01", "2025-01-15")
        assert missing == []

    @pytest.mark.asyncio
    async def test_get_missing_date_ranges_multiple_gaps(self, cache):
        """Cached Jan 1-8 and Jan 11-13 → gaps at Jan 9-10 and Jan 14-15."""
        await cache.store_messages("chat_123", [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-08T10:00:00"),
        ])
        await cache.store_messages("chat_123", [
            _make_msg(3, date="2025-01-11T10:00:00"),
            _make_msg(4, date="2025-01-13T10:00:00"),
        ])
        missing = await cache.get_missing_date_ranges("chat_123", "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-09", "2025-01-10"), ("2025-01-14", "2025-01-15")]


class TestVasyaPetyaKolyaScenario:
    """Full E2E scenario as described by user.

    1. Vasya exports chat A for Jan 11-13 → cached
    2. Petya exports chat A for Jan 1-8 → cached
    3. Kolya exports chat A for Jan 1-15 → uses cache for 1-8 and 11-13,
       fetches only 9-10 and 14-15, returns complete file
    """

    @pytest.mark.asyncio
    async def test_kolya_gets_full_range_from_partial_caches(self, cache):
        # Vasya: Jan 11-13
        vasya_msgs = [
            _make_msg(11, date="2025-01-11T10:00:00"),
            _make_msg(12, date="2025-01-12T10:00:00"),
            _make_msg(13, date="2025-01-13T10:00:00"),
        ]
        await cache.store_messages("chat_A", vasya_msgs)

        # Petya: Jan 1-8
        petya_msgs = [
            _make_msg(1, date="2025-01-01T10:00:00"),
            _make_msg(2, date="2025-01-02T10:00:00"),
            _make_msg(3, date="2025-01-03T10:00:00"),
            _make_msg(4, date="2025-01-04T10:00:00"),
            _make_msg(5, date="2025-01-05T10:00:00"),
            _make_msg(6, date="2025-01-06T10:00:00"),
            _make_msg(7, date="2025-01-07T10:00:00"),
            _make_msg(8, date="2025-01-08T10:00:00"),
        ]
        await cache.store_messages("chat_A", petya_msgs)

        # Verify cached date ranges: [Jan 1-8] and [Jan 11-13]
        date_ranges = await cache.get_cached_date_ranges("chat_A")
        assert date_ranges == [["2025-01-01", "2025-01-08"], ["2025-01-11", "2025-01-13"]]

        # Kolya requests Jan 1-15 — find missing date ranges
        missing = await cache.get_missing_date_ranges("chat_A", "2025-01-01", "2025-01-15")
        assert missing == [("2025-01-09", "2025-01-10"), ("2025-01-14", "2025-01-15")]

        # Simulate fetching missing ranges from Telegram
        kolya_fresh = [
            _make_msg(9, date="2025-01-09T10:00:00"),
            _make_msg(10, date="2025-01-10T10:00:00"),
            _make_msg(14, date="2025-01-14T10:00:00"),
            _make_msg(15, date="2025-01-15T10:00:00"),
        ]
        await cache.store_messages("chat_A", kolya_fresh)

        # Get cached messages for full range
        cached = await cache.get_messages_by_date("chat_A", "2025-01-01", "2025-01-15")
        all_msgs = cache.merge_and_sort(cached, [])

        # Kolya gets all 15 messages as one monolithic result
        assert [m.id for m in all_msgs] == list(range(1, 16))

        # Cache now has complete Jan 1-15
        date_ranges = await cache.get_cached_date_ranges("chat_A")
        assert date_ranges == [["2025-01-01", "2025-01-15"]]

        # Future user requesting Jan 1-15 → nothing missing
        missing = await cache.get_missing_date_ranges("chat_A", "2025-01-01", "2025-01-15")
        assert missing == []

        # Future user requesting Jan 1-20 → only Jan 16-20 missing
        missing = await cache.get_missing_date_ranges("chat_A", "2025-01-01", "2025-01-20")
        assert missing == [("2025-01-16", "2025-01-20")]
