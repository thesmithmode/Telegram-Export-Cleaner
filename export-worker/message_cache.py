
import asyncio
import logging
import os
import shutil
import time
from contextlib import asynccontextmanager
from datetime import date, datetime, timedelta, timezone
from typing import Any, AsyncGenerator, List, Optional, Tuple, Union

import aiosqlite
import json as _stdjson
import msgpack

from models import ExportedMessage
from pyrogram_client import ensure_utc

logger = logging.getLogger(__name__)

from config import settings

# TTL для Redis cache:ranges:{chat_id} — публикуется из Python после store_messages,
# Java читает в isLikelyCached() и решает Express queue vs main. Значение совпадает
# с TTL канонических маппингов (30 дней), чтобы expire ранжей не оставлял zombie
# в Java логике. Не привязано к SQLite TTL (там диапазоны хранятся вечно до evict).
_CACHE_RANGES_REDIS_TTL_SECONDS = 30 * 86400


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
        # Read pool: отдельные read-only conn разрывают bottleneck aiosqlite single-thread
        # executor при concurrent reads. WAL уже разрешает writers + readers parallel,
        # но один conn сериализует всё через свой executor thread.
        self._read_pool: Optional[asyncio.Queue] = None
        self._read_pool_size = settings.CACHE_READ_POOL_SIZE
        self._read_conns: list = []
        # Per-chat asyncio.Lock: разделяет write contention между чатами. На N=4
        # concurrent jobs +29% throughput; при N=1 overhead ~ноль (один Lock на чат).
        self._chat_locks: dict = {}
        # Optional Redis-клиент для публикации cache:ranges:{chat_id}. Java
        # ExportJobProducer.isLikelyCached читает этот ключ и направляет
        # повторные экспорты в Express queue. Без публикации Express ветка
        # никогда не активируется (false → main queue всегда).
        self.redis_client: Optional[Any] = None

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

        # Миграция page_size: SQLite требует VACUUM для смены page_size на existing БД.
        # Делается один раз; при повторных стартах current_page_size == target → skip.
        await self._migrate_page_size_if_needed()

        await self._db.executescript("""
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=NORMAL;
            PRAGMA cache_size=-8192;
            PRAGMA temp_store=MEMORY;
            PRAGMA mmap_size=268435456;

            CREATE TABLE IF NOT EXISTS messages (
                chat_id   INTEGER NOT NULL,
                topic_id  INTEGER NOT NULL DEFAULT 0,
                msg_id    INTEGER NOT NULL,
                msg_ts    INTEGER NOT NULL,
                data      BLOB    NOT NULL,
                PRIMARY KEY (chat_id, topic_id, msg_id)
            );
            CREATE INDEX IF NOT EXISTS idx_msg_ts
                ON messages(chat_id, topic_id, msg_ts, msg_id);

            CREATE TABLE IF NOT EXISTS chat_id_ranges (
                chat_id   INTEGER NOT NULL,
                topic_id  INTEGER NOT NULL DEFAULT 0,
                min_id    INTEGER NOT NULL,
                max_id    INTEGER NOT NULL,
                PRIMARY KEY (chat_id, topic_id, min_id)
            );

            CREATE TABLE IF NOT EXISTS chat_date_ranges (
                chat_id    INTEGER NOT NULL,
                topic_id   INTEGER NOT NULL DEFAULT 0,
                from_date  TEXT    NOT NULL,
                to_date    TEXT    NOT NULL,
                PRIMARY KEY (chat_id, topic_id, from_date)
            );

            CREATE TABLE IF NOT EXISTS chat_meta (
                chat_id       INTEGER NOT NULL,
                topic_id      INTEGER NOT NULL DEFAULT 0,
                last_accessed REAL    NOT NULL DEFAULT 0,
                msg_count     INTEGER NOT NULL DEFAULT 0,
                size_bytes    INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (chat_id, topic_id)
            );
        """)
        await self._db.commit()

        # Read pool инициализируется ПОСЛЕ создания таблиц чтобы избежать
        # race на CREATE TABLE между read-only conn и main conn.
        await self._init_read_pool()

        logger.info("MessageCache initialized at %s", self.db_path)
        await self._log_startup_state()

    async def _migrate_page_size_if_needed(self) -> None:
        """Меняет page_size SQLite через VACUUM если current != target.

        Требует:
          - временно ~2x места на диске (VACUUM пересоздаёт файл)
          - exclusive access (одна aiosqlite conn — обеспечено initialize)

        После миграции page_size зафиксирован в файле; повторный запуск skip'ает.
        """
        if self._db is None:
            return
        if not settings.CACHE_VACUUM_PAGE_SIZE_ON_START:
            return
        target = settings.CACHE_TARGET_PAGE_SIZE

        async with self._db.execute("PRAGMA page_size") as cur:
            row = await cur.fetchone()
            current = row[0] if row else target
        if current == target:
            return

        # Disk space check: VACUUM требует free >= db_size (приблизительно).
        # Если свободно меньше — пропускаем чтобы не упасть на ENOSPC.
        try:
            db_size = os.path.getsize(self.db_path) if os.path.exists(self.db_path) else 0
            free = shutil.disk_usage(os.path.dirname(self.db_path) or ".").free
            if db_size > 0 and free < db_size + (100 * 1024 * 1024):  # +100MB headroom
                logger.warning(
                    "MessageCache: page_size migration skipped — "
                    "free=%d MB, db_size=%d MB (need 2x + 100MB)",
                    free // (1024 * 1024), db_size // (1024 * 1024),
                )
                return
        except OSError as exc:
            logger.warning("MessageCache: disk_usage check failed: %s — proceeding", exc)

        # VACUUM нельзя в WAL mode + внутри активной транзакции.
        # Switch journal_mode=DELETE на время + явный commit() перед VACUUM
        # чтобы не было pending tx (aiosqlite isolation_level="" auto-begins).
        # isolation_level toggle через aiosqlite не работает: raw sqlite3.Connection
        # живёт в executor thread, прямой setattr из main thread = ProgrammingError
        # "SQLite objects created in a thread can only be used in that same thread".
        try:
            await self._db.commit()
            await self._db.execute("PRAGMA journal_mode=DELETE")
            await self._db.execute(f"PRAGMA page_size={target}")
            await self._db.commit()
            t0 = time.time()
            await self._db.execute("VACUUM")
            elapsed = time.time() - t0
            logger.info(
                "MessageCache: migrated page_size %d → %d via VACUUM (%.1fs)",
                current, target, elapsed,
            )
        except Exception as exc:
            logger.error(
                "MessageCache: page_size migration failed: %s — продолжаем со старым size",
                exc, exc_info=True,
            )

    async def _init_read_pool(self) -> None:
        """4 отдельных read-only conn в asyncio.Queue для concurrent reads."""
        if self._read_pool_size <= 0:
            return
        self._read_pool = asyncio.Queue(maxsize=self._read_pool_size)
        for _ in range(self._read_pool_size):
            rc = await aiosqlite.connect(self.db_path)
            await rc.execute("PRAGMA query_only=1")
            await rc.execute("PRAGMA mmap_size=67108864")
            await rc.execute("PRAGMA cache_size=-8000")
            self._read_conns.append(rc)
            self._read_pool.put_nowait(rc)

    async def _log_startup_state(self) -> None:
        # Стартовый self-report: если после деплоя bind mount потерялся,
        # в логах сразу видно chats=0 messages=0 — а не через жалобу юзера.
        if self._db is None:
            return
        async with self._db.execute(
            "SELECT COUNT(DISTINCT chat_id), COUNT(*), "
            "COALESCE(SUM(msg_count), 0), COALESCE(SUM(size_bytes), 0), "
            "COALESCE(MAX(last_accessed), 0) FROM chat_meta"
        ) as cur:
            row = await cur.fetchone()
        chats, topics, messages, size_bytes, last_accessed = row or (0, 0, 0, 0, 0)
        size_mb = size_bytes / (1024 * 1024)
        last_write = (
            datetime.fromtimestamp(last_accessed, tz=timezone.utc).isoformat()
            if last_accessed else "never"
        )
        logger.info(
            "Cache state at startup: chats=%d topics=%d messages=%d "
            "size=%.2f MB last_write=%s",
            chats, topics, messages, size_mb, last_write,
        )

    async def close(self):
        for rc in self._read_conns:
            try:
                await rc.close()
            except Exception as exc:
                logger.warning(f"MessageCache: read conn close failed: {exc}")
        self._read_conns.clear()
        self._read_pool = None
        # Locks привязаны к event loop → cleanup при close обязателен,
        # иначе reuse instance в новом loop = "Lock attached to different loop"
        self._chat_locks.clear()
        if self._db:
            await self._db.close()
            self._db = None

    @asynccontextmanager
    async def _acquire_read(self):
        """Acquire read conn из пула; fallback на main conn если пул не готов."""
        if self._read_pool is None:
            yield self._db
            return
        rc = await self._read_pool.get()
        try:
            yield rc
        finally:
            self._read_pool.put_nowait(rc)

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
        self, chat_id: Union[int, str], messages: List[ExportedMessage],
        topic_id: int = 0,
    ) -> int:
        if not self.enabled or not messages or self._db is None:
            return 0

        chat_id_int = int(chat_id)
        now = time.time()

        # Per-chat lock: при concurrent jobs два разных чата не блокируют друг друга,
        # один и тот же чат сериализуется (порядок msg_id + ranges merge).
        lock = self._chat_locks.get(chat_id_int)
        if lock is None:
            lock = asyncio.Lock()
            self._chat_locks[chat_id_int] = lock

        async with lock:
            return await self._store_messages_locked(
                chat_id_int, topic_id, messages, now,
            )

    async def _store_messages_locked(
        self, chat_id_int: int, topic_id: int,
        messages: List[ExportedMessage], now: float,
    ) -> int:
        # Prepare rows and track approximate size
        rows: list = []
        total_bytes = 0
        for msg in messages:
            data = self._serialize(msg)
            ts = int(self._parse_date_to_timestamp(msg.date) or 0)
            rows.append((chat_id_int, topic_id, msg.id, ts, data))
            total_bytes += len(data)

        try:
            # INSERT OR REPLACE: newest import always wins
            store_batch = settings.CACHE_STORE_BATCH_SIZE
            for i in range(0, len(rows), store_batch):
                await self._db.executemany(
                    "INSERT OR REPLACE INTO messages(chat_id, topic_id, msg_id, msg_ts, data)"
                    " VALUES (?,?,?,?,?)",
                    rows[i : i + store_batch],
                )

            # Merge ID ranges (без commit внутри — см. докстринг helper'ов)
            msg_ids = sorted(m.id for m in messages)
            await self._add_range(chat_id_int, topic_id, msg_ids[0], msg_ids[-1])

            # Merge date ranges
            dates = sorted({self._extract_date_str(m.date) for m in messages if m.date})
            if dates:
                await self._add_date_range(chat_id_int, topic_id, dates[0], dates[-1])

            # Upsert metadata — size_bytes пересчитывается из реальных данных,
            # а не аккумулируется (иначе INSERT OR REPLACE раздувает счётчик)
            async with self._db.execute(
                "SELECT COUNT(*), COALESCE(SUM(LENGTH(data)), 0)"
                " FROM messages WHERE chat_id=? AND topic_id=?",
                (chat_id_int, topic_id),
            ) as cur:
                row = await cur.fetchone()
                actual_count = row[0] if row else len(messages)
                actual_bytes = row[1] if row else total_bytes

            await self._db.execute(
                """
                INSERT INTO chat_meta(chat_id, topic_id, last_accessed, msg_count, size_bytes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(chat_id, topic_id) DO UPDATE SET
                    last_accessed = excluded.last_accessed,
                    msg_count     = excluded.msg_count,
                    size_bytes    = excluded.size_bytes
                """,
                (chat_id_int, topic_id, now, actual_count, actual_bytes),
            )

            # Единственный commit — атомарно фиксирует ВСЁ или НИЧЕГО
            await self._db.commit()
        except Exception:
            # При любой ошибке откатываем всё, чтобы не оставлять messages
            # без ranges/meta (это ломало бы 3-path кэш при следующем запросе).
            try:
                await self._db.rollback()
            except Exception as rb_exc:
                # rollback fail = SQLite в неопределённом состоянии; warning
                # маскирует проблему, поэтому логируем как error со stacktrace.
                logger.error(
                    "rollback failed during store_messages — cache may be inconsistent: %s",
                    rb_exc,
                    exc_info=True,
                )
            raise

        logger.debug(
            f"Cached {len(messages)} msgs for chat {chat_id_int} (total: {actual_count})"
        )
        # Публикация в Redis cache:ranges:{chat_id} — best-effort. Java
        # isLikelyCached проверяет этот ключ для решения Express queue.
        # Только для topic_id=0: Java логика без topic-awareness, для топиков
        # Express ветка нерелевантна.
        if topic_id == 0:
            await self._publish_cache_ranges_to_redis(chat_id_int)
        # evict_if_needed — отдельная операция, её фейл не должен трогать транзакцию store
        await self.evict_if_needed()
        return len(messages)

    async def _publish_cache_ranges_to_redis(self, chat_id_int: int) -> None:
        """Best-effort publish текущих id-ranges в Redis для Java Express queue.

        Без этого ключа Java ExportJobProducer.isLikelyCached() всегда возвращает
        false и Express ветка мертва. Падение Redis не должно ломать ingestion —
        SQLite cache уже зафиксирован.

        Java резолвит lookup как:
            canonical = GET canonical:{input}; if null → canonical = input
            ranges    = GET cache:ranges:{canonical}

        Если юзер ввёл numeric id (например -100123), а в Redis уже есть
        обратный маппинг `canonical:-100123` → "mychat", Java получит "mychat"
        и будет искать `cache:ranges:mychat` (а не numeric). Поэтому
        дублируем publish: numeric (обычный случай — username input) +
        username (если найден в canonical reverse map).
        """
        if self.redis_client is None:
            return
        try:
            ranges = await self.get_cached_ranges(chat_id_int, topic_id=0)
            payload = _stdjson.dumps(ranges, separators=(",", ":"))
            await self.redis_client.set(
                f"cache:ranges:{chat_id_int}",
                payload,
                ex=_CACHE_RANGES_REDIS_TTL_SECONDS,
            )
            # Дублируем под username (если известен) — иначе numeric input
            # юзера попадёт на обратный canonical и Java не найдёт ranges.
            try:
                username = await self.redis_client.get(f"canonical:{chat_id_int}")
                if username:
                    if isinstance(username, bytes):
                        username = username.decode("utf-8", "ignore")
                    await self.redis_client.set(
                        f"cache:ranges:{username}",
                        payload,
                        ex=_CACHE_RANGES_REDIS_TTL_SECONDS,
                    )
            except Exception as username_exc:
                logger.debug(
                    "publish cache:ranges username-dup skipped chat=%s: %s",
                    chat_id_int, username_exc,
                )
        except Exception as exc:
            logger.debug(
                "publish cache:ranges:%s skipped: %s",
                chat_id_int, exc,
            )

    # ------------------------------------------------------------------ #
    # Core read API
    # ------------------------------------------------------------------ #

    async def iter_messages(
        self, chat_id: Union[int, str], low_id: int, high_id: int,
        topic_id: int = 0,
    ) -> AsyncGenerator[ExportedMessage, None]:
        if not self.enabled or self._db is None:
            return

        chat_id_int = int(chat_id)
        await self._touch(chat_id_int, topic_id)

        async with self._acquire_read() as rc:
            async with rc.execute(
                "SELECT data FROM messages"
                " WHERE chat_id=? AND topic_id=? AND msg_id BETWEEN ? AND ?"
                " ORDER BY msg_id",
                (chat_id_int, topic_id, low_id, high_id),
            ) as cursor:
                while True:
                    rows = await cursor.fetchmany(settings.CACHE_FETCH_CHUNK_SIZE)
                    if not rows:
                        break
                    for row in rows:
                        try:
                            yield self._deserialize(row[0])
                        except Exception as exc:
                            logger.warning(f"Deserialize error (chat {chat_id_int}): {exc}")

    async def get_messages(
        self, chat_id: Union[int, str], low_id: int, high_id: int,
        topic_id: int = 0,
    ) -> List[ExportedMessage]:
        result = []
        async for msg in self.iter_messages(chat_id, low_id, high_id, topic_id=topic_id):
            result.append(msg)
        return result

    async def count_messages(
        self, chat_id: Union[int, str], low_id: int, high_id: int,
        topic_id: int = 0,
    ) -> int:
        if not self.enabled or self._db is None:
            return 0
        async with self._db.execute(
            "SELECT COUNT(*) FROM messages"
            " WHERE chat_id=? AND topic_id=? AND msg_id BETWEEN ? AND ?",
            (int(chat_id), topic_id, low_id, high_id),
        ) as cur:
            row = await cur.fetchone()
            return row[0] if row else 0

    # ------------------------------------------------------------------ #
    # ID-range management
    # ------------------------------------------------------------------ #

    async def get_cached_ranges(self, chat_id: Union[int, str],
                               topic_id: int = 0) -> List[List[int]]:
        if not self.enabled or self._db is None:
            return []
        async with self._db.execute(
            "SELECT min_id, max_id FROM chat_id_ranges"
            " WHERE chat_id=? AND topic_id=? ORDER BY min_id",
            (int(chat_id), topic_id),
        ) as cur:
            rows = await cur.fetchall()
        return [[r[0], r[1]] for r in rows]

    async def get_missing_ranges(
        self, chat_id: Union[int, str], requested_low: int, requested_high: int,
        topic_id: int = 0,
    ) -> List[Tuple[int, int]]:
        if not self.enabled:
            return [(requested_low, requested_high)]

        cached = await self.get_cached_ranges(chat_id, topic_id=topic_id)
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
        self, chat_id: Union[int, str], from_date: str, to_date: str,
        topic_id: int = 0,
    ) -> AsyncGenerator[ExportedMessage, None]:
        if not self.enabled or self._db is None:
            return

        chat_id_int = int(chat_id)
        ts_from = int(self._date_str_to_timestamp(from_date))
        ts_to   = int(self._date_str_to_timestamp(to_date)) + 86400 - 1
        await self._touch(chat_id_int, topic_id)

        async with self._acquire_read() as rc:
            async with rc.execute(
                "SELECT data FROM messages"
                " WHERE chat_id=? AND topic_id=? AND msg_ts BETWEEN ? AND ?"
                " ORDER BY msg_id",
                (chat_id_int, topic_id, ts_from, ts_to),
            ) as cursor:
                while True:
                    rows = await cursor.fetchmany(settings.CACHE_FETCH_CHUNK_SIZE)
                    if not rows:
                        break
                    for row in rows:
                        try:
                            yield self._deserialize(row[0])
                        except Exception as exc:
                            logger.warning(f"Deserialize error (chat {chat_id_int}): {exc}")

    async def get_messages_by_date(
        self, chat_id: Union[int, str], from_date: str, to_date: str,
        topic_id: int = 0,
    ) -> List[ExportedMessage]:
        result = []
        async for msg in self.iter_messages_by_date(chat_id, from_date, to_date, topic_id=topic_id):
            result.append(msg)
        return result

    async def count_messages_by_date(
        self, chat_id: Union[int, str], from_date: str, to_date: str,
        topic_id: int = 0,
    ) -> int:
        if not self.enabled or self._db is None:
            return 0
        ts_from = int(self._date_str_to_timestamp(from_date))
        ts_to   = int(self._date_str_to_timestamp(to_date)) + 86400 - 1
        async with self._db.execute(
            "SELECT COUNT(*) FROM messages"
            " WHERE chat_id=? AND topic_id=? AND msg_ts BETWEEN ? AND ?",
            (int(chat_id), topic_id, ts_from, ts_to),
        ) as cur:
            row = await cur.fetchone()
            return row[0] if row else 0

    async def get_cached_date_ranges(self, chat_id: Union[int, str],
                                    topic_id: int = 0) -> List[List[str]]:
        if not self.enabled or self._db is None:
            return []
        async with self._db.execute(
            "SELECT from_date, to_date FROM chat_date_ranges"
            " WHERE chat_id=? AND topic_id=? ORDER BY from_date",
            (int(chat_id), topic_id),
        ) as cur:
            rows = await cur.fetchall()
        return [[r[0], r[1]] for r in rows]

    async def get_missing_date_ranges(
        self, chat_id: Union[int, str], from_date: str, to_date: str,
        topic_id: int = 0,
    ) -> List[Tuple[str, str]]:
        if not self.enabled:
            return [(from_date, to_date)]
        cached = await self.get_cached_date_ranges(chat_id, topic_id=topic_id)
        if not cached:
            return [(from_date, to_date)]
        return self._compute_missing_date_ranges(cached, from_date, to_date)

    async def mark_date_range_checked(
        self, chat_id: Union[int, str], from_date: str, to_date: str,
        topic_id: int = 0,
    ) -> None:
        if not self.enabled or self._db is None:
            return
        chat_id_int = int(chat_id)
        await self._add_date_range(chat_id_int, topic_id, from_date, to_date)
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

    async def _add_range(self, chat_id: int, topic_id: int, new_min: int, new_max: int):
        # Единственный caller — store_messages (владеет outer-транзакцией, commit
        # на верхнем уровне). aiosqlite isolation_level="" открывает deferred tx на
        # первом DML — manual BEGIN IMMEDIATE здесь = nested transaction error.
        # Single-writer гарантирует, что reader не увидит окно "no ranges":
        # execute() сериализованы в одном connection.
        cached = await self.get_cached_ranges(chat_id, topic_id=topic_id)
        merged = self._merge_intervals(cached + [[new_min, new_max]])
        await self._db.execute(
            "DELETE FROM chat_id_ranges WHERE chat_id=? AND topic_id=?", (chat_id, topic_id)
        )
        await self._db.executemany(
            "INSERT INTO chat_id_ranges(chat_id, topic_id, min_id, max_id) VALUES (?,?,?,?)",
            [(chat_id, topic_id, r[0], r[1]) for r in merged],
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

    async def _add_date_range(self, chat_id: int, topic_id: int, new_from: str, new_to: str):
        # Два caller-path:
        #   (a) store_messages — владеет outer-транзакцией, commit на верхнем уровне
        #   (b) mark_date_range_checked — standalone, явный self._db.commit() сразу после
        # В обоих случаях aiosqlite (isolation_level="") сам открывает deferred tx на
        # первом DML — manual BEGIN IMMEDIATE = nested error. SIGKILL между DELETE
        # и commit() в path (b) → date-range запись пропадёт, повторный fetch
        # из Telegram (degraded, не corrupted).
        cached = await self.get_cached_date_ranges(chat_id, topic_id=topic_id)
        merged = self._merge_date_intervals(cached + [[new_from, new_to]])
        await self._db.execute(
            "DELETE FROM chat_date_ranges WHERE chat_id=? AND topic_id=?", (chat_id, topic_id)
        )
        await self._db.executemany(
            "INSERT INTO chat_date_ranges(chat_id, topic_id, from_date, to_date) VALUES (?,?,?,?)",
            [(chat_id, topic_id, r[0], r[1]) for r in merged],
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

    async def _touch(self, chat_id: int, topic_id: int = 0):
        """Обновляет last_accessed и сразу фиксирует изменение в БД."""
        if self._db is None:
            return
        await self._db.execute(
            "UPDATE chat_meta SET last_accessed=? WHERE chat_id=? AND topic_id=?",
            (time.time(), chat_id, topic_id),
        )

    async def evict_if_needed(self, max_retries: int = 3) -> int:
        for attempt in range(max_retries):
            try:
                return await self._evict_impl()
            except aiosqlite.OperationalError as exc:
                msg = str(exc).lower()
                if "locked" not in msg and "busy" not in msg:
                    raise
                if attempt == max_retries - 1:
                    logger.warning(
                        "evict_if_needed: database busy after %d retries, skipping cycle",
                        max_retries,
                    )
                    return 0
                await asyncio.sleep(0.1 * (2 ** attempt))

    async def _evict_impl(self) -> int:
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
            "SELECT chat_id, topic_id, size_bytes FROM chat_meta ORDER BY last_accessed ASC"
        ) as cur:
            candidates = await cur.fetchall()

        evicted = 0
        try:
            for chat_id, topic_id, size in candidates:
                if total_bytes <= target:
                    break
                await self._db.execute(
                    "DELETE FROM messages WHERE chat_id=? AND topic_id=?", (chat_id, topic_id)
                )
                await self._db.execute(
                    "DELETE FROM chat_id_ranges WHERE chat_id=? AND topic_id=?", (chat_id, topic_id)
                )
                await self._db.execute(
                    "DELETE FROM chat_date_ranges WHERE chat_id=? AND topic_id=?", (chat_id, topic_id)
                )
                await self._db.execute(
                    "DELETE FROM chat_meta WHERE chat_id=? AND topic_id=?", (chat_id, topic_id)
                )
                # Также убираем lock — иначе dict растёт unbounded при долгом uptime
                self._chat_locks.pop(chat_id, None)
                total_bytes -= size
                evicted += 1
                topic_info = f" topic={topic_id}" if topic_id else ""
                logger.info(
                    f"Evicted LRU cache for chat {chat_id}{topic_info} "
                    f"({size // 1024} KB freed, remaining ~{total_bytes // 1024 // 1024} MB)"
                )
            # Один атомарный commit — либо все evict'ы, либо ни одного.
            # Не оставляет "phantom ranges" без messages при прерывании.
            await self._db.commit()
        except Exception:
            try:
                await self._db.rollback()
            except Exception as rb_exc:
                logger.warning(f"rollback failed during evict: {rb_exc}")
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
