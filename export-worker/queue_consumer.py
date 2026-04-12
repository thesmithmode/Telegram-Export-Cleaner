
import logging
import json
import asyncio
from typing import Optional
from datetime import datetime

import redis.asyncio as redis

from config import settings
from models import ExportRequest

logger = logging.getLogger(__name__)

JOB_MARKER_TTL = 3600  # 1 hour — TTL for completed/failed job markers in Redis
MAX_PENDING_RETURN = 100  # Max number of pending jobs to deserialize (prevents OOM)

class QueueConsumer:

    def __init__(self):
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

        # Staging queues: jobs are atomically moved here via LMOVE before processing.
        # If the worker crashes, jobs remaining in staging are recovered on restart.
        self.staging_name = self.queue_name + "_processing"
        self.staging_express_name = self.express_queue_name + "_processing"

        logger.info(
            f"Queue Consumer initialized "
            f"(Redis: {settings.REDIS_HOST}:{settings.REDIS_PORT}, "
            f"Queue: {self.queue_name})"
        )

    async def connect(self) -> bool:
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
        return self.redis_client is not None

    async def disconnect(self):
        try:
            if self.redis_client:
                await self.redis_client.close()
                logger.info("✅ Disconnected from Redis")
        except Exception as e:
            logger.error(f"Error during disconnect: {e}")

    @property
    def express_queue_name(self) -> str:
        return self.queue_name + "_express"

    async def get_job(self) -> Optional[ExportRequest]:
        if not self.redis_client:
            raise RuntimeError("Not connected to Redis")

        try:
            # BLPOP to find which queue has a job
            result = await self.redis_client.blpop(
                [self.express_queue_name, self.queue_name], timeout=5
            )

            if not result:
                return None

            source_queue, job_json = result

            # BLPOP already removed the job — push the SAME job to staging
            # for crash recovery. Using RPUSH preserves the job payload.
            dest_queue = (
                self.staging_express_name if source_queue == self.express_queue_name
                else self.staging_name
            )
            await self.redis_client.rpush(dest_queue, job_json)

            try:
                job_data = json.loads(job_json)
                job = ExportRequest(**job_data)
                # Track job in staging for crash recovery
                await self._track_staging_job(job.task_id)
                # Store payload + queue name so we can LREM on completion
                await self._store_staging_payload(job.task_id, job_json, dest_queue)
                logger.debug(f"Got job from queue: {job.task_id}")
                return job

            except json.JSONDecodeError as e:
                logger.error(f"Failed to parse job JSON: {e}. Moving to DLQ.")
                # Remove THIS specific payload from staging (LREM, not LPOP — LPOP
                # would blindly drop the head of the list, which may belong to a
                # concurrent worker or a recovered staging entry, causing data loss).
                await self.redis_client.lrem(dest_queue, 1, job_json)
                await self._move_to_dlq(job_json, f"JSON parse error: {e}")
                return None

            except Exception as e:
                logger.error(f"Failed to create ExportRequest: {e}. Moving to DLQ.")
                await self.redis_client.lrem(dest_queue, 1, job_json)
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
        if not self.redis_client:
            return
        try:
            dlq_entry = json.dumps({
                "raw": job_json,
                "reason": reason,
                "timestamp": datetime.now().isoformat(),
            })
            dlq_name = f"{self.queue_name}_dead"
            await self.redis_client.rpush(dlq_name, dlq_entry)
            logger.warning(f"Moved invalid job to DLQ '{dlq_name}': {reason}")
        except Exception as e:
            logger.error(f"Failed to move job to DLQ: {e}")

    async def _track_staging_job(self, task_id: str) -> None:
        if self.redis_client:
            try:
                await self.redis_client.sadd("staging:jobs", task_id)
            except Exception:
                pass

    async def _untrack_staging_job(self, task_id: str) -> None:
        if self.redis_client:
            try:
                await self.redis_client.srem("staging:jobs", task_id)
            except Exception:
                pass

    async def _store_staging_payload(
        self, task_id: str, job_json: str, staging_queue: str
    ) -> None:
        if not self.redis_client:
            return
        try:
            meta = json.dumps({"payload": job_json, "queue": staging_queue})
            await self.redis_client.setex(
                f"staging:meta:{task_id}", JOB_MARKER_TTL, meta
            )
        except Exception:
            pass

    async def _remove_from_staging(self, task_id: str) -> None:
        if not self.redis_client:
            return
        try:
            raw = await self.redis_client.get(f"staging:meta:{task_id}")
            if raw:
                meta = json.loads(raw)
                await self.redis_client.lrem(meta["queue"], 1, meta["payload"])
            await self.redis_client.delete(f"staging:meta:{task_id}")
        except Exception as e:
            logger.debug(f"Could not remove staging entry for {task_id}: {e}")

    async def push_job(self, job: ExportRequest) -> bool:
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
        if not self.redis_client:
            return False

        try:
            processing_key = f"job:processing:{task_id}"
            completed_key = f"job:completed:{task_id}"

            # Resolve staging metadata before pipeline (needs a GET)
            raw = await self.redis_client.get(f"staging:meta:{task_id}")

            # Batch the 4 write operations into one pipeline (4 RTT → 1 RTT)
            # transaction=True (MULTI/EXEC) — явно указано, чтобы не зависеть от
            # дефолта redis-py: частичный fail в середине оставил бы job
            # в несогласованном состоянии (delete processing выполнено, но staging
            # ещё числит job → recover_staging_jobs перезапустит уже завершённую).
            pipe = self.redis_client.pipeline(transaction=True)
            pipe.delete(processing_key)
            pipe.setex(completed_key, JOB_MARKER_TTL, str(datetime.now().isoformat()))
            pipe.srem("staging:jobs", task_id)
            if raw:
                try:
                    meta = json.loads(raw)
                    pipe.lrem(meta["queue"], 1, meta["payload"])
                except Exception:
                    pass
            pipe.delete(f"staging:meta:{task_id}")
            await pipe.execute()

            logger.debug(f"Marked job completed: {task_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to mark job completed: {e}")
            return False

    async def mark_job_failed(self, task_id: str, error: str) -> bool:
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
            await self._untrack_staging_job(task_id)
            await self._remove_from_staging(task_id)
            return True

        except Exception as e:
            logger.error(f"Failed to mark job failed: {e}")
            return False

    async def get_pending_jobs(self) -> dict:
        if not self.redis_client:
            return {"jobs": [], "total_count": 0}

        try:
            # Get true totals via LLEN (O(1), no memory cost)
            express_total = await self.redis_client.llen(self.express_queue_name)
            main_total = await self.redis_client.llen(self.queue_name)
            total_count = express_total + main_total

            # Deserialize only the first MAX_PENDING_RETURN items
            express_limit = min(express_total, MAX_PENDING_RETURN)
            remaining = MAX_PENDING_RETURN - express_limit
            main_limit = min(main_total, remaining)

            jobs = []
            if express_limit > 0:
                express_items = await self.redis_client.lrange(self.express_queue_name, 0, express_limit - 1)
                for item in express_items:
                    try:
                        job_data = json.loads(item)
                        jobs.append(ExportRequest(**job_data))
                    except Exception:
                        pass
            if main_limit > 0:
                main_items = await self.redis_client.lrange(self.queue_name, 0, main_limit - 1)
                for item in main_items:
                    try:
                        job_data = json.loads(item)
                        jobs.append(ExportRequest(**job_data))
                    except Exception:
                        pass

            return {"jobs": jobs, "total_count": total_count}

        except Exception as e:
            logger.error(f"Failed to get pending jobs: {e}")
            return {"jobs": [], "total_count": 0}

    async def recover_staging_jobs(self) -> int:
        if not self.redis_client:
            return 0

        recovered = 0
        try:
            # staging_express → express
            while True:
                item = await self.redis_client.lmove(
                    self.staging_express_name, self.express_queue_name, "LEFT", "RIGHT"
                )
                if item is None:
                    break
                recovered += 1

            # staging → main
            while True:
                item = await self.redis_client.lmove(
                    self.staging_name, self.queue_name, "LEFT", "RIGHT"
                )
                if item is None:
                    break
                recovered += 1

            if recovered:
                logger.info(f"🔄 Recovered {recovered} staging jobs from previous crash")

            # Cleanup stale tracking set
            await self.redis_client.delete("staging:jobs")
        except Exception as e:
            logger.error(f"Failed to recover staging jobs: {e}")

        return recovered

    async def get_queue_stats(self) -> Optional[dict]:
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
        await self.connect()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        await self.disconnect()

async def create_queue_consumer() -> QueueConsumer:
    consumer = QueueConsumer()

    if not await consumer.connect():
        raise RuntimeError("Failed to connect to Redis queue")

    return consumer
