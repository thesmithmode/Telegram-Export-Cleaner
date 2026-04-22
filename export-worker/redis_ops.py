"""Generic safe-fail wrappers вокруг redis.asyncio.Redis + ключевые константы."""
import logging
from typing import Optional

import redis.asyncio as aioredis


class RedisKeys:
    """Префиксы и ключи Redis. Один источник правды для именования —
    избегаем drift между прод-кодом, тестами и админ-скриптами."""

    ACTIVE_EXPORT_PREFIX = "active_export:"
    ACTIVE_PROCESSING_JOB = "active_processing_job"
    HEARTBEAT_PREFIX = "worker:heartbeat:"
    CANONICAL_PREFIX = "canonical:"

    @staticmethod
    def active_export(user_id: int) -> str:
        return f"{RedisKeys.ACTIVE_EXPORT_PREFIX}{user_id}"

    @staticmethod
    def heartbeat(task_id: str) -> str:
        return f"{RedisKeys.HEARTBEAT_PREFIX}{task_id}"

    @staticmethod
    def canonical(input_id) -> str:
        return f"{RedisKeys.CANONICAL_PREFIX}{input_id}"

    @staticmethod
    def canonical_type(canonical_id) -> str:
        return f"{RedisKeys.CANONICAL_PREFIX}{canonical_id}:type"


class RedisOps:
    """Обёртка для best-effort Redis-операций.

    Если client отсутствует (None) — no-op. Все исключения подавляются и
    логируются на указанном уровне ('warning' или 'debug'). Domain-методы в
    ExportWorker используют RedisOps чтобы избавиться от boilerplate
    try/except + 'if self.redis:'.
    """

    def __init__(self, client: Optional[aioredis.Redis], logger: logging.Logger):
        self._client = client
        self._log = logger

    async def safe_set(
        self,
        key: str,
        value: str,
        ex: Optional[int] = None,
        on_error_level: str = "warning",
    ) -> None:
        """SET key value [EX seconds]. Ошибки → log на указанном уровне."""
        if self._client is None:
            return
        try:
            await self._client.set(key, value, ex=ex)
        except Exception as e:
            getattr(self._log, on_error_level)(f"redis set failed (key={key}): {e}")

    async def safe_delete(
        self,
        key: str,
        on_error_level: str = "warning",
    ) -> None:
        """DEL key. Ошибки → log на указанном уровне."""
        if self._client is None:
            return
        try:
            await self._client.delete(key)
        except Exception as e:
            getattr(self._log, on_error_level)(f"redis delete failed (key={key}): {e}")
