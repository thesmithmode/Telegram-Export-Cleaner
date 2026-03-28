"""
MessageCache: Redis-backed message cache using sorted sets.

Stores exported messages per chat in Redis sorted sets (score=msg_id).
Tracks cached ranges to enable gap detection and partial fetching.

Redis keys:
  cache:msgs:{chat_id}    — sorted set (score=msg_id, value=msgpack'd message)
  cache:ranges:{chat_id}  — string: JSON array [[low, high], ...]
  cache:meta:{chat_id}    — hash: {last_access: unix_ts, msg_count: int}
"""

import json
import logging
import time
from typing import List, Tuple, Optional, Union

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

    async def store_messages(
        self, chat_id: Union[int, str], messages: List[ExportedMessage]
    ) -> int:
        """Store messages in cache, update ranges. Returns count stored."""
        if not self.enabled or not messages:
            return 0

        msgs_key = self._msgs_key(chat_id)
        ranges_key = self._ranges_key(chat_id)
        meta_key = self._meta_key(chat_id)

        # Add to sorted set: score=msg_id, value=msgpack bytes
        pipe = self.redis.pipeline()
        for msg in messages:
            pipe.zadd(msgs_key, {self._serialize(msg): msg.id})
        await pipe.execute()

        # Enforce per-chat cap: keep newest N messages
        count = await self.redis.zcard(msgs_key)
        if count > self.max_messages_per_chat:
            trim_count = count - self.max_messages_per_chat
            await self.redis.zremrangebyrank(msgs_key, 0, trim_count - 1)

        # Update ranges
        msg_ids = sorted(m.id for m in messages)
        new_range = [msg_ids[0], msg_ids[-1]]
        await self._add_range(chat_id, new_range)

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
        """Retrieve cached messages in [low_id, high_id] range, sorted by ID ascending."""
        if not self.enabled:
            return []

        msgs_key = self._msgs_key(chat_id)
        raw_items = await self.redis.zrangebyscore(msgs_key, low_id, high_id)

        if not raw_items:
            return []

        messages = []
        for data in raw_items:
            try:
                messages.append(self._deserialize(data))
            except Exception as e:
                logger.warning(f"Failed to deserialize cached message: {e}")
                continue

        # Touch last_access
        await self._touch(chat_id)
        await self._refresh_ttl(chat_id)

        return messages

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

    # --- TTL & metadata ---

    async def _refresh_ttl(self, chat_id: Union[int, str]):
        """Set/refresh TTL on all cache keys for chat."""
        pipe = self.redis.pipeline()
        pipe.expire(self._msgs_key(chat_id), self.ttl)
        pipe.expire(self._ranges_key(chat_id), self.ttl)
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
            pipe.delete(self._ranges_key(chat_id))
            pipe.delete(self._meta_key(chat_id))
            await pipe.execute()
            evicted += 1
            logger.info(f"Evicted cache for chat {chat_id} (last access: {_})")

        return evicted
