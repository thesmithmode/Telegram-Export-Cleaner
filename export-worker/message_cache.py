
import logging
import os
import time
from datetime import date, datetime, timedelta, timezone
from typing import AsyncGenerator, List, Optional, Tuple, Union

import aiosqlite
import msgpack

from models import ExportedMessage
from pyrogram_client import ensure_utc

logger = logging.getLogger(__name__)

_FETCH_CHUNK = 1_000   # rows per fetchmany — O(1000) RAM peak
_STORE_BATCH = 1_000   # rows per executemany INSERT

class MessageCache:

    def __init__(
        self,
        db_path: str = "/data/cache/messages.db",
        max_disk_bytes: int = 25 * 1024 ** 3,
        max_messages_per_chat: int = 100_000,
        ttl_seconds: int = 30 * 86400,
        enabled: bool = True,
    ):
        self.db_path = db_path
        self.max_disk_bytes = max_disk_bytes
        self.max_messages_per_chat = max_messages_per_chat
        self.ttl = ttl_seconds
        self.enabled = enabled
        self._db: Optional[aiosqlite.Connection] = None

    # ------------------------------------------------------------------ #
    # Lifecycle
    # ------------------------------------------------------------------ #

    async def initialize(self):
        if not self.enabled:
            return
        db_dir = os.path.dirname(self.db_path)
        if db_dir:
            os.makedirs(db_dir, exist_ok=True)
        self._db = await aiosqlite.connect(self.db_path)
        await self._db.executescript("""
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=NORMAL;
            PRAGMA cache_size=-32000;
            PRAGMA temp_store=MEMORY;
            PRAGMA mmap_size=268435456;

            CREATE TABLE IF NOT EXISTS messages (
                chat_id  INTEGER NOT NULL,
                msg_id   INTEGER NOT NULL,
                msg_ts   INTEGER NOT NULL,
                data     BLOB    NOT NULL,
                PRIMARY KEY (chat_id, msg_id)
            );
            CREATE INDEX IF NOT EXISTS idx_msg_ts
                ON messages(chat_id, msg_ts, msg_id);

            CREATE TABLE IF NOT EXISTS chat_id_ranges (
                chat_id  INTEGER NOT NULL,
                min_id   INTEGER NOT NULL,
                max_id   INTEGER NOT NULL,
                PRIMARY KEY (chat_id, min_id)
            );

            CREATE TABLE IF NOT EXISTS chat_date_ranges (
                chat_id    INTEGER NOT NULL,
                from_date  TEXT    NOT NULL,
                to_date    TEXT    NOT NULL,
                PRIMARY KEY (chat_id, from_date)
            );

            CREATE TABLE IF NOT EXISTS chat_meta (
                chat_id       INTEGER PRIMARY KEY,
                last_accessed REAL    NOT NULL DEFAULT 0,
                msg_count     INTEGER NOT NULL DEFAULT 0,
                size_bytes    INTEGER NOT NULL DEFAULT 0
            );
        """)
        await self._db.commit()
        logger.info(f"MessageCache initialized at {self.db_path}")

    async def close(self):
        if self._db:
            await self._db.close()
            self._db = None

    # ------------------------------------------------------------------ #
    # Serialization
    # ------------------------------------------------------------------ #

    @staticmethod
    def _serialize(msg: ExportedMessage) -> bytes:
        return msgpack.packb(msg.model_dump(exclude_none=True), use_bin_type=True)

    @staticmethod
    def _deserialize(data: bytes) -> ExportedMessage:
        obj = msgpack.unpackb(data, raw=False)
        return ExportedMessage(**obj)

    # ------------------------------------------------------------------ #
    # Core write API
    # ------------------------------------------------------------------ #

    async def store_messages(
        self, chat_id: Union[int, str], messages: List[ExportedMessage]
    ) -> int:
        if not self.enabled or not messages or self._db is None:
            return 0

        chat_id_int = int(chat_id)
        now = time.time()

        # Prepare rows and track approximate size
        rows: list = []
        total_bytes = 0
        for msg in messages:
            data = self._serialize(msg)
            ts = int(self._parse_date_to_timestamp(msg.date) or 0)
            rows.append((chat_id_int, msg.id, ts, data))
            total_bytes += len(data)

        try:
            # INSERT OR REPLACE: newest import always wins
            for i in range(0, len(rows), _STORE_BATCH):
                await self._db.executemany(
                    "INSERT OR REPLACE INTO messages(chat_id, msg_id, msg_ts, data)"
                    " VALUES (?,?,?,?)",
                    rows[i : i + _STORE_BATCH],
                )

            # Merge ID ranges (без commit внутри — см. докстринг helper'ов)
            msg_ids = sorted(m.id for m in messages)
            await self._add_range(chat_id_int, msg_ids[0], msg_ids[-1])

            # Merge date ranges
            dates = sorted({self._extract_date_str(m.date) for m in messages if m.date})
            if dates:
                await self._add_date_range(chat_id_int, dates[0], dates[-1])

            # Upsert metadata — size_bytes пересчитывается из реальных данных,
            # а не аккумулируется (иначе INSERT OR REPLACE раздувает счётчик)
            async with self._db.execute(
                "SELECT COUNT(*), COALESCE(SUM(LENGTH(data)), 0)"
                " FROM messages WHERE chat_id=?",
                (chat_id_int,),
            ) as cur:
                row = await cur.fetchone()
                actual_count = row[0] if row else len(messages)
                actual_bytes = row[1] if row else total_bytes

            await self._db.execute(
                """
                INSERT INTO chat_meta(chat_id, last_accessed, msg_count, size_bytes)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(chat_id) DO UPDATE SET
                    last_accessed = excluded.last_accessed,
                    msg_count     = excluded.msg_count,
                    size_bytes    = excluded.size_bytes
                """,
                (chat_id_int, now, actual_count, actual_bytes),
            )

            # Единственный commit — атомарно фиксирует ВСЁ или НИЧЕГО
            await self._db.commit()
        except Exception:
            # При любой ошибке откатываем всё, чтобы не оставлять messages
            # без ranges/meta (это ломало бы 3-path кэш при следующем запросе).
            try:
                await self._db.rollback()
            except Exception:
                pass
            raise

        logger.debug(
            f"Cached {len(messages)} msgs for chat {chat_id_int} (total: {actual_count})"
        )
        # evict_if_needed — отдельная операция, её фейл не должен трогать транзакцию store
        await self.evict_if_needed()
        return len(messages)

    # ------------------------------------------------------------------ #
    # Core read API
    # ------------------------------------------------------------------ #

    async def iter_messages(
        self, chat_id: Union[int, str], low_id: int, high_id: int
    ) -> AsyncGenerator[ExportedMessage, None]:
        if not self.enabled or self._db is None:
            return

        chat_id_int = int(chat_id)
        await self._touch(chat_id_int)

        async with self._db.execute(
            "SELECT data FROM messages"
            " WHERE chat_id=? AND msg_id BETWEEN ? AND ?"
            " ORDER BY msg_id",
            (chat_id_int, low_id, high_id),
        ) as cursor:
            while True:
                rows = await cursor.fetchmany(_FETCH_CHUNK)
                if not rows:
                    break
                for row in rows:
                    try:
                        yield self._deserialize(row[0])
                    except Exception as exc:
                        logger.warning(f"Deserialize error (chat {chat_id_int}): {exc}")

    async def get_messages(
        self, chat_id: Union[int, str], low_id: int, high_id: int
    ) -> List[ExportedMessage]:
        result = []
        async for msg in self.iter_messages(chat_id, low_id, high_id):
            result.append(msg)
        return result

    async def count_messages(
        self, chat_id: Union[int, str], low_id: int, high_id: int
    ) -> int:
        if not self.enabled or self._db is None:
            return 0
        async with self._db.execute(
            "SELECT COUNT(*) FROM messages"
            " WHERE chat_id=? AND msg_id BETWEEN ? AND ?",
            (int(chat_id), low_id, high_id),
        ) as cur:
            row = await cur.fetchone()
            return row[0] if row else 0

    # ------------------------------------------------------------------ #
    # ID-range management
    # ------------------------------------------------------------------ #

    async def get_cached_ranges(self, chat_id: Union[int, str]) -> List[List[int]]:
        if not self.enabled or self._db is None:
            return []
        async with self._db.execute(
            "SELECT min_id, max_id FROM chat_id_ranges"
            " WHERE chat_id=? ORDER BY min_id",
            (int(chat_id),),
        ) as cur:
            rows = await cur.fetchall()
        return [[r[0], r[1]] for r in rows]

    async def get_missing_ranges(
        self, chat_id: Union[int, str], requested_low: int, requested_high: int
    ) -> List[Tuple[int, int]]:
        if not self.enabled:
            return [(requested_low, requested_high)]

        cached = await self.get_cached_ranges(chat_id)
        if not cached:
            return [(requested_low, requested_high)]

        missing: list = []
        current = requested_low
        for low, high in cached:
            if low > current:
                gap_end = min(low - 1, requested_high)
                if gap_end >= current:
                    missing.append((current, gap_end))
            current = max(current, high + 1)
            if current > requested_high:
                break
        if current <= requested_high:
            missing.append((current, requested_high))
        return missing

    @staticmethod
    def merge_and_sort(
        cached: List[ExportedMessage], fresh: List[ExportedMessage]
    ) -> List[ExportedMessage]:
        by_id = {msg.id: msg for msg in cached}
        by_id.update({msg.id: msg for msg in fresh})  # fresh wins
        return sorted(by_id.values(), key=lambda m: m.id)

    # ------------------------------------------------------------------ #
    # Date-range API
    # ------------------------------------------------------------------ #

    async def iter_messages_by_date(
        self, chat_id: Union[int, str], from_date: str, to_date: str
    ) -> AsyncGenerator[ExportedMessage, None]:
        if not self.enabled or self._db is None:
            return

        chat_id_int = int(chat_id)
        ts_from = int(self._date_str_to_timestamp(from_date))
        ts_to   = int(self._date_str_to_timestamp(to_date)) + 86400
        await self._touch(chat_id_int)

        async with self._db.execute(
            "SELECT data FROM messages"
            " WHERE chat_id=? AND msg_ts BETWEEN ? AND ?"
            " ORDER BY msg_id",
            (chat_id_int, ts_from, ts_to),
        ) as cursor:
            while True:
                rows = await cursor.fetchmany(_FETCH_CHUNK)
                if not rows:
                    break
                for row in rows:
                    try:
                        yield self._deserialize(row[0])
                    except Exception as exc:
                        logger.warning(f"Deserialize error (chat {chat_id_int}): {exc}")

    async def get_messages_by_date(
        self, chat_id: Union[int, str], from_date: str, to_date: str
    ) -> List[ExportedMessage]:
        result = []
        async for msg in self.iter_messages_by_date(chat_id, from_date, to_date):
            result.append(msg)
        return result

    async def count_messages_by_date(
        self, chat_id: Union[int, str], from_date: str, to_date: str
    ) -> int:
        if not self.enabled or self._db is None:
            return 0
        ts_from = int(self._date_str_to_timestamp(from_date))
        ts_to   = int(self._date_str_to_timestamp(to_date)) + 86400
        async with self._db.execute(
            "SELECT COUNT(*) FROM messages"
            " WHERE chat_id=? AND msg_ts BETWEEN ? AND ?",
            (int(chat_id), ts_from, ts_to),
        ) as cur:
            row = await cur.fetchone()
            return row[0] if row else 0

    async def get_cached_date_ranges(self, chat_id: Union[int, str]) -> List[List[str]]:
        if not self.enabled or self._db is None:
            return []
        async with self._db.execute(
            "SELECT from_date, to_date FROM chat_date_ranges"
            " WHERE chat_id=? ORDER BY from_date",
            (int(chat_id),),
        ) as cur:
            rows = await cur.fetchall()
        return [[r[0], r[1]] for r in rows]

    async def get_missing_date_ranges(
        self, chat_id: Union[int, str], from_date: str, to_date: str
    ) -> List[Tuple[str, str]]:
        if not self.enabled:
            return [(from_date, to_date)]
        cached = await self.get_cached_date_ranges(chat_id)
        if not cached:
            return [(from_date, to_date)]
        return self._compute_missing_date_ranges(cached, from_date, to_date)

    async def mark_date_range_checked(
        self, chat_id: Union[int, str], from_date: str, to_date: str
    ) -> None:
        if not self.enabled or self._db is None:
            return
        chat_id_int = int(chat_id)
        await self._add_date_range(chat_id_int, from_date, to_date)
        await self._db.commit()

    @staticmethod
    def _compute_missing_date_ranges(
        cached: List[List[str]], from_date: str, to_date: str
    ) -> List[Tuple[str, str]]:
        req_start = date.fromisoformat(from_date)
        req_end   = date.fromisoformat(to_date)
        missing: list = []
        current = req_start
        for low_s, high_s in cached:
            low  = date.fromisoformat(low_s)
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

    # ------------------------------------------------------------------ #
    # Internal range merge helpers
    # ------------------------------------------------------------------ #

    async def _add_range(self, chat_id: int, new_min: int, new_max: int):
        cached = await self.get_cached_ranges(chat_id)
        merged = self._merge_intervals(cached + [[new_min, new_max]])
        await self._db.execute(
            "DELETE FROM chat_id_ranges WHERE chat_id=?", (chat_id,)
        )
        await self._db.executemany(
            "INSERT INTO chat_id_ranges(chat_id, min_id, max_id) VALUES (?,?,?)",
            [(chat_id, r[0], r[1]) for r in merged],
        )

    @staticmethod
    def _merge_intervals(intervals: List[List[int]]) -> List[List[int]]:
        if not intervals:
            return []
        sorted_iv = sorted(intervals, key=lambda x: x[0])
        merged = [sorted_iv[0][:]]
        for low, high in sorted_iv[1:]:
            last = merged[-1]
            if low <= last[1] + 1:
                last[1] = max(last[1], high)
            else:
                merged.append([low, high])
        return merged

    async def _add_date_range(self, chat_id: int, new_from: str, new_to: str):
        cached = await self.get_cached_date_ranges(chat_id)
        merged = self._merge_date_intervals(cached + [[new_from, new_to]])
        await self._db.execute(
            "DELETE FROM chat_date_ranges WHERE chat_id=?", (chat_id,)
        )
        await self._db.executemany(
            "INSERT INTO chat_date_ranges(chat_id, from_date, to_date) VALUES (?,?,?)",
            [(chat_id, r[0], r[1]) for r in merged],
        )

    @staticmethod
    def _merge_date_intervals(intervals: List[List[str]]) -> List[List[str]]:
        if not intervals:
            return []
        # Parse strings to date objects for comparison, reuse int merge logic
        date_intervals = [[date.fromisoformat(d[0]), date.fromisoformat(d[1])] for d in intervals]
        sorted_iv = sorted(date_intervals, key=lambda x: x[0])
        merged = [sorted_iv[0][:]]
        for low, high in sorted_iv[1:]:
            last = merged[-1]
            if low <= last[1] + timedelta(days=1):
                last[1] = max(last[1], high)
            else:
                merged.append([low, high])
        # Convert back to ISO strings
        return [[d[0].isoformat(), d[1].isoformat()] for d in merged]

    # ------------------------------------------------------------------ #
    # LRU touch & eviction
    # ------------------------------------------------------------------ #

    async def _touch(self, chat_id: int):
        """Обновляет last_accessed без commit — вызывающий код сам решает когда коммитить."""
        if self._db is None:
            return
        await self._db.execute(
            "UPDATE chat_meta SET last_accessed=? WHERE chat_id=?",
            (time.time(), chat_id),
        )

    async def evict_if_needed(self) -> int:
        if self._db is None:
            return 0

        async with self._db.execute(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM chat_meta"
        ) as cur:
            row = await cur.fetchone()
            total_bytes: int = row[0] if row else 0

        target = int(self.max_disk_bytes * 0.9)
        if total_bytes <= target:
            return 0

        # Fetch candidates sorted oldest-first (LRU)
        async with self._db.execute(
            "SELECT chat_id, size_bytes FROM chat_meta ORDER BY last_accessed ASC"
        ) as cur:
            candidates = await cur.fetchall()

        evicted = 0
        try:
            for chat_id, size in candidates:
                if total_bytes <= target:
                    break
                await self._db.execute(
                    "DELETE FROM messages WHERE chat_id=?", (chat_id,)
                )
                await self._db.execute(
                    "DELETE FROM chat_id_ranges WHERE chat_id=?", (chat_id,)
                )
                await self._db.execute(
                    "DELETE FROM chat_date_ranges WHERE chat_id=?", (chat_id,)
                )
                await self._db.execute(
                    "DELETE FROM chat_meta WHERE chat_id=?", (chat_id,)
                )
                total_bytes -= size
                evicted += 1
                logger.info(
                    f"Evicted LRU cache for chat {chat_id} "
                    f"({size // 1024} KB freed, remaining ~{total_bytes // 1024 // 1024} MB)"
                )
            # Один атомарный commit — либо все evict'ы, либо ни одного.
            # Не оставляет "phantom ranges" без messages при прерывании.
            await self._db.commit()
        except Exception:
            try:
                await self._db.rollback()
            except Exception:
                pass
            raise

        return evicted

    # ------------------------------------------------------------------ #
    # Date/timestamp helpers
    # ------------------------------------------------------------------ #

    @staticmethod
    def _parse_date_to_timestamp(date_str: str) -> Optional[float]:
        try:
            dt = datetime.fromisoformat(date_str)
            dt = ensure_utc(dt)
            return dt.timestamp()
        except (ValueError, TypeError):
            logger.warning(f"Невалидная дата при кэшировании: {date_str!r}")
            return None

    @staticmethod
    def _date_str_to_timestamp(date_str: str) -> float:
        dt = datetime.fromisoformat(date_str + "T00:00:00").replace(tzinfo=timezone.utc)
        return dt.timestamp()

    @staticmethod
    def _extract_date_str(date_iso: str) -> str:
        return date_iso[:10]
