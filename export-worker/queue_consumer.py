"""
Redis Queue Consumer: Process export jobs from Java Bot.

Handles:
- Connection to Redis queue
- Job deserialization from JSON
- Error handling and job failure tracking
- Job result storage
- Graceful shutdown
"""

import logging
import json
import asyncio
from typing import Optional, Callable, Any
from datetime import datetime

import redis.asyncio as redis

from config import settings
from models import ExportRequest

logger = logging.getLogger(__name__)


JOB_MARKER_TTL = 3600  # 1 hour — TTL for completed/failed job markers in Redis


class QueueConsumer:
    """Consumer for Redis-backed job queue."""

    def __init__(self):
        """Initialize queue consumer."""
        self.redis_url = (
            f"redis://:"
            f"{settings.REDIS_PASSWORD}@"
            f"{settings.REDIS_HOST}:"
            f"{settings.REDIS_PORT}/"
            f"{settings.REDIS_DB}"
            if settings.REDIS_PASSWORD
            else f"redis://{settings.REDIS_HOST}:{settings.REDIS_PORT}/{settings.REDIS_DB}"
        )
        self.queue_name = settings.REDIS_QUEUE_NAME
        self.redis_client: Optional[redis.Redis] = None

        logger.info(
            f"Queue Consumer initialized "
            f"(Redis: {settings.REDIS_HOST}:{settings.REDIS_PORT}, "
            f"Queue: {self.queue_name})"
        )

    async def connect(self) -> bool:
        """
        Connect to Redis.

        Returns:
            True if successful, False otherwise
        """
        try:
            self.redis_client = await redis.from_url(
                self.redis_url,
                decode_responses=True,
                socket_timeout=10,        # Таймаут на операции (сек)
                socket_connect_timeout=5, # Таймаут на установку соединения (сек)
            )

            # Test connection
            await self.redis_client.ping()
            logger.info("✅ Connected to Redis")
            return True

        except Exception as e:
            logger.error(f"❌ Failed to connect to Redis: {e}")
            return False

    async def _reconnect(self, max_retries: int = 3) -> bool:
        """
        Reconnect to Redis with exponential backoff.

        Args:
            max_retries: Max reconnection attempts

        Returns:
            True if reconnected successfully, False otherwise
        """
        for attempt in range(max_retries):
            try:
                if self.redis_client:
                    await self.redis_client.close()
                    self.redis_client = None

                # Exponential backoff: 1s, 2s, 4s
                wait_time = 2 ** attempt
                logger.warning(
                    f"Reconnecting to Redis (attempt {attempt + 1}/{max_retries}) "
                    f"after {wait_time}s..."
                )
                await asyncio.sleep(wait_time)

                if await self.connect():
                    return True

            except Exception as e:
                logger.error(f"Reconnection attempt {attempt + 1} failed: {e}")

        return False

    @property
    def is_connected(self) -> bool:
        """Check if client is connected to Redis."""
        return self.redis_client is not None

    async def disconnect(self):
        """Disconnect from Redis."""
        try:
            if self.redis_client:
                await self.redis_client.close()
                logger.info("✅ Disconnected from Redis")
        except Exception as e:
            logger.error(f"Error during disconnect: {e}")

    async def get_job(self) -> Optional[ExportRequest]:
        """
        Get next job from queue (blocking).

        Uses BLPOP to block until job available.
        Handles reconnection on connection errors.

        Returns:
            ExportRequest object or None if error
        """
        if not self.redis_client:
            raise RuntimeError("Not connected to Redis")

        try:
            # BLPOP: blocks with timeout so SIGTERM can be handled
            result = await self.redis_client.blpop(self.queue_name, timeout=5)

            if not result:
                return None

            queue_name, job_json = result

            try:
                job_data = json.loads(job_json)
                job = ExportRequest(**job_data)
                logger.debug(f"Got job from queue: {job.task_id}")
                return job

            except json.JSONDecodeError as e:
                logger.error(f"Failed to parse job JSON: {e}. Moving to DLQ.")
                await self._move_to_dlq(job_json, f"JSON parse error: {e}")
                return None

            except Exception as e:
                logger.error(f"Failed to create ExportRequest: {e}. Moving to DLQ.")
                await self._move_to_dlq(job_json, f"Validation error: {e}")
                return None

        except redis.ConnectionError as e:
            logger.warning(f"Redis connection lost: {e}")
            # Attempt to reconnect
            if await self._reconnect(max_retries=3):
                logger.info("Redis reconnected successfully")
                return None  # Signal main loop to retry
            else:
                logger.error("Failed to reconnect to Redis after 3 attempts")
                raise RuntimeError("Redis connection lost and reconnection failed")

        except Exception as e:
            logger.error(f"Error getting job from queue: {e}", exc_info=True)
            return None

    async def _move_to_dlq(self, job_json: str, reason: str) -> None:
        """
        Перекладывает невалидную задачу в Dead Letter Queue.

        Отравленные сообщения (невалидный JSON, ошибка валидации Pydantic)
        не теряются и могут быть проверены вручную в Redis-ключе <queue>_dead.
        """
        if not self.redis_client:
            return
        try:
            import json as _json
            dlq_entry = _json.dumps({
                "raw": job_json,
                "reason": reason,
                "timestamp": datetime.now().isoformat(),
            })
            dlq_name = f"{self.queue_name}_dead"
            await self.redis_client.rpush(dlq_name, dlq_entry)
            logger.warning(f"Moved invalid job to DLQ '{dlq_name}': {reason}")
        except Exception as e:
            logger.error(f"Failed to move job to DLQ: {e}")

    async def push_job(self, job: ExportRequest) -> bool:
        """
        Push job back to queue (for retries).

        Args:
            job: ExportRequest to push

        Returns:
            True if successful, False otherwise
        """
        if not self.redis_client:
            raise RuntimeError("Not connected to Redis")

        try:
            job_json = json.dumps(job.model_dump())
            await self.redis_client.rpush(self.queue_name, job_json)
            logger.debug(f"Pushed job back to queue: {job.task_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to push job to queue: {e}")
            return False

    async def mark_job_processing(self, task_id: str) -> bool:
        """
        Mark job as being processed (in Redis).

        Args:
            task_id: Task ID

        Returns:
            True if successful
        """
        if not self.redis_client:
            return False

        try:
            processing_key = f"job:processing:{task_id}"
            await self.redis_client.setex(
                processing_key,
                settings.JOB_TIMEOUT,  # Auto-expire after timeout
                str(datetime.now().isoformat())
            )
            return True

        except Exception as e:
            logger.error(f"Failed to mark job as processing: {e}")
            return False

    async def mark_job_completed(self, task_id: str) -> bool:
        """
        Mark job as completed.

        Args:
            task_id: Task ID

        Returns:
            True if successful
        """
        if not self.redis_client:
            return False

        try:
            processing_key = f"job:processing:{task_id}"
            completed_key = f"job:completed:{task_id}"

            # Remove from processing
            await self.redis_client.delete(processing_key)

            # Set completed marker
            await self.redis_client.setex(
                completed_key,
                JOB_MARKER_TTL,
                str(datetime.now().isoformat())
            )

            logger.debug(f"Marked job completed: {task_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to mark job completed: {e}")
            return False

    async def mark_job_failed(self, task_id: str, error: str) -> bool:
        """
        Mark job as failed.

        Args:
            task_id: Task ID
            error: Error message

        Returns:
            True if successful
        """
        if not self.redis_client:
            return False

        try:
            processing_key = f"job:processing:{task_id}"
            failed_key = f"job:failed:{task_id}"

            # Remove from processing
            await self.redis_client.delete(processing_key)

            # Set failed marker
            await self.redis_client.setex(
                failed_key,
                JOB_MARKER_TTL,
                json.dumps({
                    "error": error,
                    "timestamp": datetime.now().isoformat()
                })
            )

            logger.debug(f"Marked job failed: {task_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to mark job failed: {e}")
            return False

    async def get_pending_jobs(self) -> list[ExportRequest]:
        """
        Return all pending jobs from queue without removing them (LRANGE 0 -1).

        Used to notify users of their updated queue position before processing starts.
        """
        if not self.redis_client:
            return []

        try:
            items = await self.redis_client.lrange(self.queue_name, 0, -1)
            jobs = []
            for item in items:
                try:
                    job_data = json.loads(item)
                    jobs.append(ExportRequest(**job_data))
                except Exception:
                    pass
            return jobs
        except Exception as e:
            logger.error(f"Failed to get pending jobs: {e}")
            return []

    async def get_queue_stats(self) -> Optional[dict]:
        """
        Get queue statistics.

        Returns:
            Dict with stats or None
        """
        if not self.redis_client:
            return None

        try:
            queue_length = await self.redis_client.llen(self.queue_name)

            return {
                "queue_name": self.queue_name,
                "pending_jobs": queue_length,
                "timestamp": datetime.now().isoformat(),
            }

        except Exception as e:
            logger.error(f"Failed to get queue stats: {e}")
            return None

    async def __aenter__(self):
        """Async context manager entry."""
        await self.connect()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        """Async context manager exit."""
        await self.disconnect()


async def create_queue_consumer() -> QueueConsumer:
    """
    Factory function to create and verify queue consumer.

    Returns:
        Connected QueueConsumer instance

    Raises:
        RuntimeError: If connection fails
    """
    consumer = QueueConsumer()

    if not await consumer.connect():
        raise RuntimeError("Failed to connect to Redis queue")

    return consumer
