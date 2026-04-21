
"""Снапшот статистики кэша для админ-дашборда.

Агрегат из chat_meta: общие итоги, top-N чатов по размеру, heatmap
по last_accessed (hot/warm/cold LRU-бакеты).

Публикуется в Redis периодически (fresh-ness до 1 мин), Java-дашборд
читает на запрос — real-time HTTP между контейнерами не нужен.
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import asdict, dataclass, field
from typing import List, Optional

logger = logging.getLogger(__name__)

REDIS_SNAPSHOT_KEY = "cache:stats:snapshot"
REDIS_SNAPSHOT_TTL = 300  # 5 мин — чуть больше периода публикации (60с)

_HOT_DAYS = 7
_WARM_DAYS = 30
_BUCKET_ORDER = ("hot", "warm", "cold")


@dataclass
class ChatStatEntry:
    chat_id: int
    topic_id: int
    msg_count: int
    size_bytes: int
    last_accessed: float
    pct: float


@dataclass
class CacheStatsSnapshot:
    used_bytes: int
    limit_bytes: int
    pct: float
    total_chats: int
    total_messages: int
    top_chats: List[ChatStatEntry] = field(default_factory=list)
    heatmap: dict = field(default_factory=dict)  # {bucket_name: {chat_count, size_bytes}}
    generated_at: float = 0.0

    def to_json(self) -> str:
        return json.dumps(asdict(self))


async def build_snapshot(
    cache,
    top_n: int = 50,
    now: Optional[float] = None,
) -> CacheStatsSnapshot:
    """Собрать агрегат chat_meta → CacheStatsSnapshot."""
    ts = time.time() if now is None else now
    db = cache._db

    async with db.execute(
        "SELECT COUNT(DISTINCT chat_id), "
        "COALESCE(SUM(msg_count), 0), "
        "COALESCE(SUM(size_bytes), 0) FROM chat_meta"
    ) as cur:
        row = await cur.fetchone()
    total_chats, total_messages, used_bytes = row or (0, 0, 0)

    limit_bytes = int(getattr(cache, "max_disk_bytes", 0) or 0)
    pct = (used_bytes / limit_bytes * 100.0) if limit_bytes > 0 else 0.0

    async with db.execute(
        "SELECT chat_id, topic_id, msg_count, size_bytes, last_accessed "
        "FROM chat_meta ORDER BY size_bytes DESC, chat_id ASC LIMIT ?",
        (top_n,),
    ) as cur:
        rows = await cur.fetchall()
    top_chats = [
        ChatStatEntry(
            chat_id=r[0],
            topic_id=r[1],
            msg_count=r[2],
            size_bytes=r[3],
            last_accessed=r[4],
            pct=(r[3] / used_bytes * 100.0) if used_bytes > 0 else 0.0,
        )
        for r in rows
    ]

    hot_cutoff = ts - _HOT_DAYS * 86400
    warm_cutoff = ts - _WARM_DAYS * 86400
    async with db.execute(
        """SELECT
                CASE
                    WHEN last_accessed >= ? THEN 'hot'
                    WHEN last_accessed >= ? THEN 'warm'
                    ELSE 'cold'
                END AS bucket,
                COUNT(*) AS chat_count,
                COALESCE(SUM(size_bytes), 0) AS size_bytes
            FROM chat_meta
            GROUP BY bucket""",
        (hot_cutoff, warm_cutoff),
    ) as cur:
        rows = await cur.fetchall()
    heatmap = {r[0]: {"chat_count": r[1], "size_bytes": r[2]} for r in rows}
    for name in _BUCKET_ORDER:
        if name not in heatmap:
            heatmap[name] = {"chat_count": 0, "size_bytes": 0}

    return CacheStatsSnapshot(
        used_bytes=used_bytes,
        limit_bytes=limit_bytes,
        pct=pct,
        total_chats=total_chats,
        total_messages=total_messages,
        top_chats=top_chats,
        heatmap=heatmap,
        generated_at=ts,
    )


async def publish_snapshot(redis_client, snapshot: CacheStatsSnapshot) -> None:
    """Записать snapshot в Redis с TTL."""
    try:
        await redis_client.set(
            REDIS_SNAPSHOT_KEY,
            snapshot.to_json(),
            ex=REDIS_SNAPSHOT_TTL,
        )
    except Exception as e:
        logger.warning("Failed to publish cache stats snapshot: %s", e)
