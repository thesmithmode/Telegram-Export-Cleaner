"""
MessageCache: Redis-backed message cache using sorted sets.

Stores exported messages per chat in Redis sorted sets (score=msg_id).
Tracks cached ranges (by ID and by date) to enable gap detection.

Redis keys:
  cache:msgs:{chat_id}         — sorted set (score=msg_id, value=msgpack'd message)
  cache:dates:{chat_id}        — sorted set (score=unix_ts, value=msg_id as bytes)
  cache:ranges:{chat_id}       — string: JSON array [[low_id, high_id], ...]
  cache:date_ranges:{chat_id}  — string: JSON array [["2025-01-01","2025-01-08"], ...]
  cache:meta:{chat_id}         — hash: {last_access: unix_ts, msg_count: int}
"""

import json
import logging
import time
from datetime import datetime, date, timedelta
from typing import AsyncGenerator, List, Tuple, Optional, Union

import msgpack

from models import ExportedMessage

try:
    import redis.asyncio as aioredis
except ImportError:
    aioredis = None  # type: ignore

logger = logging.getLogger(__name__)


class MessageCache:
    """Per-chat message cache backed by Redis sorted sets."""

    def __init__(
        self,
        redis_client,
        ttl_seconds: int = 7 * 86400,
        max_memory_mb: int = 120,
        max_messages_per_chat: int = 100_000,
        enabled: bool = True,
    ):
        self.redis = redis_client
        self.ttl = ttl_seconds
        self.max_memory_mb = max_memory_mb
        self.max_messages_per_chat = max_messages_per_chat
        self.enabled = enabled

    # --- Key helpers ---

    @staticmethod
    def _msgs_key(chat_id: Union[int, str]) -> str:
        return f"cache:msgs:{chat_id}"

    @staticmethod
    def _ranges_key(chat_id: Union[int, str]) -> str:
        return f"cache:ranges:{chat_id}"

    @staticmethod
    def _dates_key(chat_id: Union[int, str]) -> str:
        return f"cache:dates:{chat_id}"

    @staticmethod
    def _date_ranges_key(chat_id: Union[int, str]) -> str:
        return f"cache:date_ranges:{chat_id}"

    @staticmethod
    def _meta_key(chat_id: Union[int, str]) -> str:
        return f"cache:meta:{chat_id}"

    # --- Serialization ---

    @staticmethod
    def _serialize(msg: ExportedMessage) -> bytes:
        """Serialize ExportedMessage to msgpack bytes."""
        return msgpack.packb(msg.model_dump(exclude_none=True), use_bin_type=True)

    @staticmethod
    def _deserialize(data: bytes) -> ExportedMessage:
        """Deserialize msgpack bytes to ExportedMessage."""
        obj = msgpack.unpackb(data, raw=False)
        return ExportedMessage(**obj)

    # --- Core API ---

    STORE_BATCH_SIZE = 5000
    GET_CHUNK_SIZE = 10_000  # Messages per Redis read — limits peak raw-bytes buffer

    async def store_messages(
        self, chat_id: Union[int, str], messages: List[ExportedMessage]
    ) -> int:
        """Store messages in cache, update ranges. Returns count stored."""
        if not self.enabled or not messages:
            return 0

        msgs_key = self._msgs_key(chat_id)
        dates_key = self._dates_key(chat_id)
        meta_key = self._meta_key(chat_id)

        # Батчинг: разбиваем на чанки чтобы не убить Redis одним пайплайном
        for i in range(0, len(messages), self.STORE_BATCH_SIZE):
            batch = messages[i:i + self.STORE_BATCH_SIZE]

            # Telegram message IDs are unique within a chat — no dedup check needed.
            # Single pipeline: add new entries via ZADD.
            pipe = self.redis.pipeline()
            for msg in batch:
                pipe.zadd(msgs_key, {self._serialize(msg): msg.id})
                ts = self._parse_date_to_timestamp(msg.date)
                if ts is not None:
                    pipe.zadd(dates_key, {str(msg.id).encode(): ts})
            await pipe.execute()

        # Update ID ranges
        msg_ids = sorted(m.id for m in messages)
        new_range = [msg_ids[0], msg_ids[-1]]
        await self._add_range(chat_id, new_range)

        # Update date ranges
        dates = sorted(set(self._extract_date_str(m.date) for m in messages if m.date))
        if dates:
            await self._add_date_range(chat_id, [dates[0], dates[-1]])

        # Update metadata
        final_count = await self.redis.zcard(msgs_key)
        await self.redis.hset(meta_key, mapping={
            "last_access": str(int(time.time())),
            "msg_count": str(final_count),
        })

        # Set/refresh TTL on all keys
        await self._refresh_ttl(chat_id)

        logger.debug(f"Cached {len(messages)} messages for chat {chat_id} (total: {final_count})")
        return len(messages)

    async def get_messages(
        self, chat_id: Union[int, str], low_id: int, high_id: int
    ) -> List[ExportedMessage]:
        """Retrieve cached messages in [low_id, high_id] range, sorted by ID ascending.

        Reads from Redis in chunks of GET_CHUNK_SIZE to avoid loading a large raw-bytes
        buffer into memory all at once (prevents OOM for chats with 100k+ cached messages).
        """
        if not self.enabled:
            return []

        msgs_key = self._msgs_key(chat_id)
        messages = []
        offset = 0

        while True:
            raw_items = await self.redis.zrangebyscore(
                msgs_key, low_id, high_id,
                start=offset, num=self.GET_CHUNK_SIZE,
            )
            if not raw_items:
                break

            for data in raw_items:
                try:
                    messages.append(self._deserialize(data))
                except Exception as e:
                    logger.warning(f"Failed to deserialize cached message: {e}")

            offset += len(raw_items)
            if len(raw_items) < self.GET_CHUNK_SIZE:
                break

        if not messages:
            return []

        # Touch last_access
        await self._touch(chat_id)
        await self._refresh_ttl(chat_id)

        return messages

    async def count_messages(
        self, chat_id: Union[int, str], low_id: int, high_id: int
    ) -> int:
        """Count cached messages in [low_id, high_id] without loading them. O(1) via ZCOUNT."""
        if not self.enabled:
            return 0
        return await self.redis.zcount(self._msgs_key(chat_id), low_id, high_id)

    async def iter_messages(
        self, chat_id: Union[int, str], low_id: int, high_id: int
    ) -> AsyncGenerator[ExportedMessage, None]:
        """Async generator: yield messages one at a time from cache.

        Memory: O(GET_CHUNK_SIZE) — reads Redis in chunks, never holds full list.
        Use instead of get_messages() for large chats to avoid OOM.
        """
        if not self.enabled:
            return
        msgs_key = self._msgs_key(chat_id)
        offset = 0
        while True:
            raw_items = await self.redis.zrangebyscore(
                msgs_key, low_id, high_id,
                start=offset, num=self.GET_CHUNK_SIZE,
            )
            if not raw_items:
                break
            for data in raw_items:
                try:
                    yield self._deserialize(data)
                except Exception as e:
                    logger.warning(f"Failed to deserialize cached message: {e}")
            offset += len(raw_items)
            if len(raw_items) < self.GET_CHUNK_SIZE:
                break
        await self._touch(chat_id)
        await self._refresh_ttl(chat_id)

    async def get_cached_ranges(self, chat_id: Union[int, str]) -> List[List[int]]:
        """Return list of [low, high] ranges cached for this chat."""
        if not self.enabled:
            return []

        ranges_key = self._ranges_key(chat_id)
        raw = await self.redis.get(ranges_key)
        if not raw:
            return []

        try:
            return json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            return []

    async def get_missing_ranges(
        self, chat_id: Union[int, str], requested_low: int, requested_high: int
    ) -> List[Tuple[int, int]]:
        """Given requested [low, high], return sub-ranges NOT in cache."""
        if not self.enabled:
            return [(requested_low, requested_high)]

        cached = await self.get_cached_ranges(chat_id)
        if not cached:
            return [(requested_low, requested_high)]

        # Subtract cached intervals from [requested_low, requested_high]
        missing = []
        current = requested_low

        for low, high in cached:
            if low > current:
                # Gap before this cached range
                gap_end = min(low - 1, requested_high)
                if gap_end >= current:
                    missing.append((current, gap_end))
            current = max(current, high + 1)
            if current > requested_high:
                break

        # Gap after last cached range
        if current <= requested_high:
            missing.append((current, requested_high))

        return missing

    @staticmethod
    def merge_and_sort(
        cached: List[ExportedMessage], fresh: List[ExportedMessage]
    ) -> List[ExportedMessage]:
        """Merge two lists, deduplicate by ID, sort ascending by ID."""
        by_id = {}
        for msg in cached:
            by_id[msg.id] = msg
        for msg in fresh:
            by_id[msg.id] = msg  # fresh overwrites cached (newer data)
        return sorted(by_id.values(), key=lambda m: m.id)

    # --- Date-aware API ---

    async def get_messages_by_date(
        self, chat_id: Union[int, str], from_date: str, to_date: str
    ) -> List[ExportedMessage]:
        """Retrieve cached messages within [from_date, to_date] (inclusive, YYYY-MM-DD)."""
        if not self.enabled:
            return []

        dates_key = self._dates_key(chat_id)
        ts_from = self._date_str_to_timestamp(from_date)
        # to_date is inclusive: end of day
        ts_to = self._date_str_to_timestamp(to_date) + 86400 - 1

        # Get msg_ids from date index
        raw_ids = await self.redis.zrangebyscore(dates_key, ts_from, ts_to)
        if not raw_ids:
            return []

        msg_ids = [int(mid) for mid in raw_ids]
        if not msg_ids:
            return []

        # Fetch actual messages by ID range
        min_id = min(msg_ids)
        max_id = max(msg_ids)
        all_msgs = await self.get_messages(chat_id, min_id, max_id)

        # Filter to only the msg_ids from the date index (exact match)
        id_set = set(msg_ids)
        return [m for m in all_msgs if m.id in id_set]

    async def count_messages_by_date(
        self, chat_id: Union[int, str], from_date: str, to_date: str
    ) -> int:
        """Return count of cached messages in [from_date, to_date] — O(1), no data loaded."""
        if not self.enabled:
            return 0
        dates_key = self._dates_key(chat_id)
        ts_from = self._date_str_to_timestamp(from_date)
        ts_to = self._date_str_to_timestamp(to_date) + 86400 - 1
        return await self.redis.zcount(dates_key, ts_from, ts_to)

    async def iter_messages_by_date(
        self, chat_id: Union[int, str], from_date: str, to_date: str
    ) -> AsyncGenerator:
        """Stream cached messages in [from_date, to_date], sorted by msg_id, O(chunk) memory.

        Loads msg_id list from the date index (integers only, lightweight),
        then streams full message objects via iter_messages filtered to that id set.
        """
        if not self.enabled:
            return

        dates_key = self._dates_key(chat_id)
        ts_from = self._date_str_to_timestamp(from_date)
        ts_to = self._date_str_to_timestamp(to_date) + 86400 - 1

        raw_ids = await self.redis.zrangebyscore(dates_key, ts_from, ts_to)
        if not raw_ids:
            return

        msg_ids = [int(mid) for mid in raw_ids]
        if not msg_ids:
            return

        id_set = set(msg_ids)
        min_id = min(msg_ids)
        max_id = max(msg_ids)

        async for msg in self.iter_messages(chat_id, min_id, max_id):
            if msg.id in id_set:
                yield msg

    async def get_cached_date_ranges(self, chat_id: Union[int, str]) -> List[List[str]]:
        """Return list of [from_date, to_date] date ranges cached for this chat."""
        if not self.enabled:
            return []

        raw = await self.redis.get(self._date_ranges_key(chat_id))
        if not raw:
            return []

        try:
            return json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            return []

    async def get_missing_date_ranges(
        self, chat_id: Union[int, str], from_date: str, to_date: str
    ) -> List[Tuple[str, str]]:
        """Given requested [from_date, to_date], return sub-ranges NOT in cache."""
        if not self.enabled:
            return [(from_date, to_date)]

        cached = await self.get_cached_date_ranges(chat_id)
        if not cached:
            return [(from_date, to_date)]

        return self._compute_missing_date_ranges(cached, from_date, to_date)

    @staticmethod
    def _compute_missing_date_ranges(
        cached: List[List[str]], from_date: str, to_date: str
    ) -> List[Tuple[str, str]]:
        """Subtract cached date intervals from [from_date, to_date]."""
        req_start = date.fromisoformat(from_date)
        req_end = date.fromisoformat(to_date)

        missing = []
        current = req_start

        for low_s, high_s in cached:
            low = date.fromisoformat(low_s)
            high = date.fromisoformat(high_s)

            if low > current:
                gap_end = min(low - timedelta(days=1), req_end)
                if gap_end >= current:
                    missing.append((current.isoformat(), gap_end.isoformat()))
            current = max(current, high + timedelta(days=1))
            if current > req_end:
                break

        if current <= req_end:
            missing.append((current.isoformat(), req_end.isoformat()))

        return missing

    # --- Date helpers ---

    @staticmethod
    def _parse_date_to_timestamp(date_str: str) -> Optional[float]:
        """Parse ISO datetime string to unix timestamp (UTC)."""
        try:
            from datetime import timezone
            dt = datetime.fromisoformat(date_str)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.timestamp()
        except (ValueError, TypeError):
            return None

    @staticmethod
    def _date_str_to_timestamp(date_str: str) -> float:
        """Parse YYYY-MM-DD to unix timestamp (start of day, UTC)."""
        from datetime import timezone
        dt = datetime.fromisoformat(date_str + "T00:00:00").replace(tzinfo=timezone.utc)
        return dt.timestamp()

    @staticmethod
    def _extract_date_str(date_iso: str) -> str:
        """Extract YYYY-MM-DD from ISO datetime string."""
        return date_iso[:10]

    # --- Range management ---

    async def _add_range(self, chat_id: Union[int, str], new_range: List[int]):
        """Add a new [low, high] interval and merge overlapping/adjacent."""
        ranges = await self.get_cached_ranges(chat_id)
        ranges.append(new_range)
        merged = self._merge_intervals(ranges)
        # If per-chat cap was enforced, adjust ranges to reflect actual data
        await self.redis.set(self._ranges_key(chat_id), json.dumps(merged))

    @staticmethod
    def _merge_intervals(intervals: List[List[int]]) -> List[List[int]]:
        """Merge overlapping and adjacent intervals. Classic interval merge."""
        if not intervals:
            return []

        sorted_intervals = sorted(intervals, key=lambda x: x[0])
        merged = [sorted_intervals[0][:]]  # copy first

        for low, high in sorted_intervals[1:]:
            last = merged[-1]
            if low <= last[1] + 1:  # overlapping or adjacent
                last[1] = max(last[1], high)
            else:
                merged.append([low, high])

        return merged

    async def _add_date_range(self, chat_id: Union[int, str], new_range: List[str]):
        """Add a new [from_date, to_date] interval and merge overlapping/adjacent."""
        ranges = await self.get_cached_date_ranges(chat_id)
        ranges.append(new_range)
        merged = self._merge_date_intervals(ranges)
        await self.redis.set(self._date_ranges_key(chat_id), json.dumps(merged))

    @staticmethod
    def _merge_date_intervals(intervals: List[List[str]]) -> List[List[str]]:
        """Merge overlapping and adjacent date intervals (YYYY-MM-DD strings)."""
        if not intervals:
            return []

        sorted_intervals = sorted(intervals, key=lambda x: x[0])
        merged = [sorted_intervals[0][:]]

        for low_s, high_s in sorted_intervals[1:]:
            last = merged[-1]
            last_high = date.fromisoformat(last[1])
            curr_low = date.fromisoformat(low_s)

            # Adjacent (next day) or overlapping
            if curr_low <= last_high + timedelta(days=1):
                if high_s > last[1]:
                    last[1] = high_s
            else:
                merged.append([low_s, high_s])

        return merged

    # --- TTL & metadata ---

    async def _refresh_ttl(self, chat_id: Union[int, str]):
        """Set/refresh TTL on all cache keys for chat."""
        pipe = self.redis.pipeline()
        pipe.expire(self._msgs_key(chat_id), self.ttl)
        pipe.expire(self._dates_key(chat_id), self.ttl)
        pipe.expire(self._ranges_key(chat_id), self.ttl)
        pipe.expire(self._date_ranges_key(chat_id), self.ttl)
        pipe.expire(self._meta_key(chat_id), self.ttl)
        await pipe.execute()

    async def _touch(self, chat_id: Union[int, str]):
        """Update last_access timestamp."""
        await self.redis.hset(
            self._meta_key(chat_id),
            "last_access",
            str(int(time.time())),
        )

    # --- Eviction ---

    async def evict_if_needed(self) -> int:
        """Evict oldest-accessed chats until under memory budget. Returns count evicted."""
        evicted = 0

        # Scan for all cache:meta:* keys
        meta_keys = []
        async for key in self.redis.scan_iter(match="cache:meta:*"):
            if isinstance(key, bytes):
                key = key.decode()
            meta_keys.append(key)

        if not meta_keys:
            return 0

        # Get last_access for each, sort oldest first
        chat_access = []
        for mk in meta_keys:
            chat_id = mk.replace("cache:meta:", "")
            last_access = await self.redis.hget(mk, "last_access")
            ts = int(last_access) if last_access else 0
            chat_access.append((ts, chat_id))

        chat_access.sort()  # oldest first

        # Check memory (use Redis INFO if available, else estimate)
        for _, chat_id in chat_access:
            try:
                info = await self.redis.info("memory")
                used_mb = info.get("used_memory", 0) / (1024 * 1024)
            except Exception:
                # Fallback: always evict at least one if max_memory_mb=0
                used_mb = self.max_memory_mb + 1

            if used_mb <= self.max_memory_mb:
                break

            # Evict this chat
            pipe = self.redis.pipeline()
            pipe.delete(self._msgs_key(chat_id))
            pipe.delete(self._dates_key(chat_id))
            pipe.delete(self._ranges_key(chat_id))
            pipe.delete(self._date_ranges_key(chat_id))
            pipe.delete(self._meta_key(chat_id))
            await pipe.execute()
            evicted += 1
            logger.info(f"Evicted cache for chat {chat_id} (last access: {_})")

        return evicted
