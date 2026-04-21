
"""Тесты снапшота статистики кэша для админ-дашборда."""

import json
import time
import pytest
from unittest.mock import AsyncMock

from message_cache import MessageCache
from cache_stats import (
    CacheStatsSnapshot,
    ChatStatEntry,
    REDIS_SNAPSHOT_KEY,
    REDIS_SNAPSHOT_TTL,
    build_snapshot,
    publish_snapshot,
)


@pytest.fixture
async def cache(tmp_path):
    c = MessageCache(
        db_path=str(tmp_path / "stats_cache.db"),
        max_disk_bytes=10 * 1024 * 1024,  # 10 MB для предсказуемого pct
        max_messages_per_chat=1000,
        ttl_seconds=3600,
    )
    await c.initialize()
    yield c
    await c.close()


async def _insert_meta(cache, chat_id, topic_id, msg_count, size_bytes, last_accessed):
    await cache._db.execute(
        "INSERT INTO chat_meta(chat_id, topic_id, last_accessed, msg_count, size_bytes) "
        "VALUES (?, ?, ?, ?, ?)",
        (chat_id, topic_id, last_accessed, msg_count, size_bytes),
    )
    await cache._db.commit()


class TestBuildSnapshotTotals:

    @pytest.mark.asyncio
    async def test_empty_cache_returns_zero_totals(self, cache):
        snap = await build_snapshot(cache)
        assert snap.used_bytes == 0
        assert snap.total_chats == 0
        assert snap.total_messages == 0
        assert snap.pct == 0.0
        assert snap.limit_bytes == 10 * 1024 * 1024
        assert snap.top_chats == []

    @pytest.mark.asyncio
    async def test_totals_sum_from_chat_meta(self, cache):
        now = time.time()
        await _insert_meta(cache, 100, 0, 50, 1_000_000, now)
        await _insert_meta(cache, 200, 0, 30, 500_000, now)
        await _insert_meta(cache, 200, 42, 10, 100_000, now)  # topic

        snap = await build_snapshot(cache)
        assert snap.used_bytes == 1_600_000
        assert snap.total_messages == 90
        assert snap.total_chats == 2  # DISTINCT chat_id

    @pytest.mark.asyncio
    async def test_pct_computed_from_limit(self, cache):
        now = time.time()
        # 2 MB из 10 MB = 20%
        await _insert_meta(cache, 1, 0, 1, 2 * 1024 * 1024, now)
        snap = await build_snapshot(cache)
        assert snap.pct == pytest.approx(20.0, abs=0.01)

    @pytest.mark.asyncio
    async def test_pct_zero_when_limit_zero(self, tmp_path):
        c = MessageCache(
            db_path=str(tmp_path / "z.db"),
            max_disk_bytes=0,
        )
        await c.initialize()
        try:
            await _insert_meta(c, 1, 0, 1, 1000, time.time())
            snap = await build_snapshot(c)
            assert snap.pct == 0.0
            assert snap.limit_bytes == 0
        finally:
            await c.close()


class TestTopChats:

    @pytest.mark.asyncio
    async def test_top_chats_sorted_desc_by_size(self, cache):
        now = time.time()
        await _insert_meta(cache, 1, 0, 10, 100, now)
        await _insert_meta(cache, 2, 0, 10, 500, now)
        await _insert_meta(cache, 3, 0, 10, 300, now)

        snap = await build_snapshot(cache)
        assert [e.chat_id for e in snap.top_chats] == [2, 3, 1]

    @pytest.mark.asyncio
    async def test_top_chats_limited_to_top_n(self, cache):
        now = time.time()
        for i in range(60):
            await _insert_meta(cache, i + 1, 0, 1, i + 1, now)

        snap = await build_snapshot(cache, top_n=50)
        assert len(snap.top_chats) == 50
        # Самый большой size_bytes первым
        assert snap.top_chats[0].size_bytes == 60
        assert snap.top_chats[-1].size_bytes == 11

    @pytest.mark.asyncio
    async def test_top_chat_pct_relative_to_used(self, cache):
        now = time.time()
        await _insert_meta(cache, 1, 0, 5, 750, now)
        await _insert_meta(cache, 2, 0, 5, 250, now)

        snap = await build_snapshot(cache)
        # used=1000; chat 1 = 75%, chat 2 = 25%
        pct_by_id = {e.chat_id: e.pct for e in snap.top_chats}
        assert pct_by_id[1] == pytest.approx(75.0, abs=0.01)
        assert pct_by_id[2] == pytest.approx(25.0, abs=0.01)

    @pytest.mark.asyncio
    async def test_top_chat_includes_topic_id(self, cache):
        now = time.time()
        await _insert_meta(cache, 100, 42, 5, 500, now)

        snap = await build_snapshot(cache)
        assert snap.top_chats[0].chat_id == 100
        assert snap.top_chats[0].topic_id == 42
        assert snap.top_chats[0].msg_count == 5


class TestHeatmap:

    @pytest.mark.asyncio
    async def test_heatmap_bucketing_by_last_accessed(self, cache):
        now = time.time()
        # hot: <7d
        await _insert_meta(cache, 1, 0, 10, 100, now - 3 * 86400)
        # warm: 7-30d
        await _insert_meta(cache, 2, 0, 10, 200, now - 15 * 86400)
        # cold: >30d
        await _insert_meta(cache, 3, 0, 10, 400, now - 60 * 86400)

        snap = await build_snapshot(cache, now=now)
        hm = snap.heatmap
        assert hm["hot"]["chat_count"] == 1
        assert hm["hot"]["size_bytes"] == 100
        assert hm["warm"]["chat_count"] == 1
        assert hm["warm"]["size_bytes"] == 200
        assert hm["cold"]["chat_count"] == 1
        assert hm["cold"]["size_bytes"] == 400

    @pytest.mark.asyncio
    async def test_heatmap_always_has_three_buckets(self, cache):
        # Пустой кэш — все три бакета с нулями (UI чарт не падает)
        snap = await build_snapshot(cache)
        assert set(snap.heatmap.keys()) == {"hot", "warm", "cold"}
        for data in snap.heatmap.values():
            assert data["chat_count"] == 0
            assert data["size_bytes"] == 0

    @pytest.mark.asyncio
    async def test_heatmap_boundary_is_hot(self, cache):
        now = time.time()
        # ровно 7 дней назад = hot (inclusive)
        await _insert_meta(cache, 1, 0, 1, 100, now - 7 * 86400)
        snap = await build_snapshot(cache, now=now)
        assert snap.heatmap["hot"]["chat_count"] == 1
        assert snap.heatmap["warm"]["chat_count"] == 0


class TestSerialization:

    @pytest.mark.asyncio
    async def test_snapshot_serializes_to_json(self, cache):
        now = time.time()
        await _insert_meta(cache, 1, 0, 5, 100, now)

        snap = await build_snapshot(cache, now=now)
        payload = snap.to_json()
        parsed = json.loads(payload)

        assert parsed["used_bytes"] == 100
        assert parsed["total_chats"] == 1
        assert parsed["top_chats"][0]["chat_id"] == 1
        assert len(parsed["heatmap"]) == 3
        assert "generated_at" in parsed

    @pytest.mark.asyncio
    async def test_publish_snapshot_writes_to_redis_with_ttl(self, cache):
        redis_mock = AsyncMock()
        snap = await build_snapshot(cache)

        await publish_snapshot(redis_mock, snap)

        redis_mock.set.assert_awaited_once()
        call_args = redis_mock.set.await_args
        assert call_args.args[0] == REDIS_SNAPSHOT_KEY
        assert call_args.kwargs.get("ex") == REDIS_SNAPSHOT_TTL
        # значение — валидный JSON
        json.loads(call_args.args[1])

    def test_redis_ttl_is_five_minutes(self):
        assert REDIS_SNAPSHOT_TTL == 300

    def test_redis_key_is_namespaced(self):
        assert REDIS_SNAPSHOT_KEY == "cache:stats:snapshot"
