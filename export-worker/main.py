#!/usr/bin/env python3
"""
Export Worker: Main entry point.

Architecture:
1. Connect to Redis queue
2. Connect to Telegram API (Pyrogram)
3. Connect to Java Bot API
4. Loop: Get job → Export → Send response
5. Graceful shutdown on SIGTERM/SIGINT

Error handling:
- Temp errors (network, rate limit): Retry with exponential backoff
- Perm errors (invalid chat, no access): Mark as failed, continue
- Critical errors (auth, config): Exit with error
"""

import logging
import asyncio
import signal
import sys
import shutil
import psutil
from datetime import datetime
from pathlib import Path
from typing import Optional

import redis.asyncio as aioredis

from config import settings
from pyrogram_client import TelegramClient, create_client as create_telegram_client
from queue_consumer import QueueConsumer, create_queue_consumer
from java_client import JavaBotClient, ProgressTracker, create_java_client
from message_cache import MessageCache
from models import ExportRequest, ExportedMessage

# Setup logging
logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)

logger = logging.getLogger(__name__)


class ExportWorker:
    """Main worker that processes export jobs."""

    def __init__(self):
        """Initialize worker."""
        self.telegram_client: Optional[TelegramClient] = None
        self.queue_consumer: Optional[QueueConsumer] = None
        self.java_client: Optional[JavaBotClient] = None
        self.message_cache: Optional[MessageCache] = None
        self.control_redis: Optional[aioredis.Redis] = None
        self.running = False
        self.jobs_processed = 0
        self.jobs_failed = 0

    async def _check_cancel_and_save(
        self, job: ExportRequest, messages: list[ExportedMessage], count: int
    ) -> bool:
        """Check cancellation every 1000 messages, save to cache if cancelled. Returns True if cancelled."""
        if count % 1000 != 0:
            return False
        if not await self.is_cancelled(job.task_id):
            return False
        logger.info(f"🛑 Export {job.task_id} cancelled by user at {count} messages")
        if self.message_cache and self.message_cache.enabled and messages:
            await self.message_cache.store_messages(job.chat_id, messages)
            logger.info(f"  Saved {count} messages to cache before cancel")
        await self.clear_active_export(job.user_id)
        return True

    async def is_cancelled(self, task_id: str) -> bool:
        """Check if export was cancelled by user."""
        if not self.control_redis:
            return False
        val = await self.control_redis.get(f"cancel_export:{task_id}")
        return val is not None

    async def clear_active_export(self, user_id: int):
        """Clear active export marker for user."""
        if self.control_redis:
            await self.control_redis.delete(f"active_export:{user_id}")

    def _create_tracker(self, job: ExportRequest) -> Optional[ProgressTracker]:
        """Create a ProgressTracker if user notifications are possible."""
        if job.user_chat_id and self.java_client:
            return self.java_client.create_progress_tracker(
                job.user_chat_id, job.task_id
            )
        return None

    def log_memory_usage(self, stage: str):
        """Log current memory usage for monitoring weak server resources."""
        try:
            mem = psutil.virtual_memory()
            cpu_percent = psutil.cpu_percent(interval=0.1)
            logger.info(
                f"📊 Resource usage [{stage}]: "
                f"Memory {mem.percent}% ({mem.available/1024/1024:.0f}MB free), "
                f"CPU {cpu_percent}%"
            )
        except Exception as e:
            logger.warning(f"Could not get resource stats: {e}")

    async def cleanup_temp_files(self, task_id: str):
        """Delete temporary files for a task to prevent disk fill."""
        try:
            temp_dir = Path(f"/tmp/export_{task_id}")
            if temp_dir.exists():
                shutil.rmtree(temp_dir)
                logger.debug(f"Cleaned up temp files for task {task_id}")
        except Exception as e:
            logger.warning(f"Failed to cleanup temp files for {task_id}: {e}")

    async def initialize(self) -> bool:
        """
        Initialize all components.

        Returns:
            True if all components initialized successfully
        """
        try:
            logger.info("🚀 Initializing Export Worker...")

            # 1. Connect to Redis queue
            logger.info("1️⃣  Connecting to Redis queue...")
            self.queue_consumer = await create_queue_consumer()

            # 2. Connect to Telegram API
            logger.info("2️⃣  Connecting to Telegram API...")
            self.telegram_client = await create_telegram_client()

            # 3. Connect to Java Bot API
            logger.info("3️⃣  Connecting to Java Bot API...")
            self.java_client = await create_java_client()

            # 4. Initialize control Redis (for cancel/active export)
            self.control_redis = aioredis.Redis(
                host=settings.REDIS_HOST,
                port=settings.REDIS_PORT,
                db=settings.REDIS_DB,
                password=settings.REDIS_PASSWORD,
                decode_responses=True,
            )

            # 5. Initialize message cache
            logger.info("4️⃣  Initializing message cache...")
            cache_redis = aioredis.Redis(
                host=settings.REDIS_HOST,
                port=settings.REDIS_PORT,
                db=settings.REDIS_DB,
                password=settings.REDIS_PASSWORD,
                decode_responses=False,
            )
            self.message_cache = MessageCache(
                redis_client=cache_redis,
                ttl_seconds=settings.CACHE_TTL_SECONDS,
                max_memory_mb=settings.CACHE_MAX_MEMORY_MB,
                max_messages_per_chat=settings.CACHE_MAX_MESSAGES_PER_CHAT,
                enabled=settings.CACHE_ENABLED,
            )
            logger.info(f"  Cache: enabled={settings.CACHE_ENABLED}, TTL={settings.CACHE_TTL_SECONDS}s, max_memory={settings.CACHE_MAX_MEMORY_MB}MB")

            logger.info("✅ All components initialized successfully")
            return True

        except Exception as e:
            logger.error(f"❌ Initialization failed: {e}")
            await self.cleanup()
            return False

    async def process_job(self, job: ExportRequest) -> bool:
        """
        Process single export job.

        Args:
            job: Export request from queue

        Returns:
            True if processed successfully (even if no messages)
        """
        try:
            logger.info(f"📝 Processing job {job.task_id} (chat {job.chat_id})")
            self.log_memory_usage("JOB_START")

            # Mark job as processing
            await self.queue_consumer.mark_job_processing(job.task_id)

            # Verify access and get chat info in single call
            accessible, chat_info, error_reason = await self.telegram_client.verify_and_get_info(job.chat_id)
            if not accessible:
                error_messages = {
                    "CHANNEL_PRIVATE": f"Канал {job.chat_id} приватный. Аккаунт worker-а должен быть участником.",
                    "USERNAME_NOT_FOUND": f"Username {job.chat_id} не найден. Проверьте правильность.",
                    "ADMIN_REQUIRED": f"Для экспорта чата {job.chat_id} нужны права администратора.",
                    "CHAT_NOT_ACCESSIBLE": (
                        f"Нет доступа к чату {job.chat_id}. "
                        f"Аккаунт worker-а должен быть участником этого чата/канала."
                    ),
                    "SESSION_INVALID": (
                        "Сессия Telegram worker-а истекла или заблокирована. "
                        "Необходимо пересоздать TELEGRAM_SESSION_STRING."
                    ),
                    "FLOOD_RESTRICTED": (
                        "Аккаунт worker-а временно ограничен Telegram (flood). "
                        "Попробуйте позже."
                    ),
                    "UNKNOWN": (
                        f"Не удалось получить доступ к чату {job.chat_id}. "
                        f"Проверьте логи worker-а для подробностей."
                    ),
                }
                error = error_messages.get(error_reason, f"No access to chat {job.chat_id}")
                error_code = error_reason or "CHAT_NOT_ACCESSIBLE"
                logger.error(f"❌ {error} (reason: {error_reason})")
                await self.java_client.send_response(
                    task_id=job.task_id,
                    status="failed",
                    messages=[],
                    error=error,
                    error_code=error_code,
                    user_chat_id=job.user_chat_id,
                )
                await self.queue_consumer.mark_job_failed(job.task_id, error)
                await self.clear_active_export(job.user_id)
                return True

            if chat_info:
                logger.info(f"  Chat: {chat_info.get('title')} (type: {chat_info.get('type')})")

            # --- Cache-aware export ---
            # Both full and date-range exports use cache:
            # 1. Check cache for already-fetched messages (by ID or by date)
            # 2. Fetch only missing ranges from Telegram API
            # 3. Store fresh messages in cache
            # 4. Merge cached + fresh for the final result

            has_date_filter = job.from_date is not None or job.to_date is not None
            messages: Optional[list[ExportedMessage]] = None

            if self.message_cache and self.message_cache.enabled:
                if has_date_filter:
                    messages = await self._export_with_date_cache(job)
                else:
                    messages = await self._export_with_id_cache(job)

            # Fallback: no cache, cache disabled, or cache export failed
            if messages is None:
                messages = await self._fetch_all_messages(job)

            if messages is None:
                # _fetch_all_messages / cache export returned None → error already reported
                return True

            # Send results to Java API, deliver cleaned text to user
            chat_title = chat_info.get('title') or chat_info.get('username') if chat_info else None
            success = await self.java_client.send_response(
                task_id=job.task_id,
                status="completed",
                messages=messages,
                user_chat_id=job.user_chat_id,
                chat_title=chat_title,
                from_date=job.from_date,
                to_date=job.to_date,
            )

            if success:
                logger.info(f"✅ Job {job.task_id} completed ({len(messages)} messages)")
                await self.queue_consumer.mark_job_completed(job.task_id)
                self.jobs_processed += 1
                self.log_memory_usage("JOB_DONE")
                await self.cleanup_temp_files(job.task_id)
                await self.clear_active_export(job.user_id)
                return True

            else:
                error = "Failed to send response to Java Bot"
                logger.error(f"❌ {error}")
                await self.queue_consumer.mark_job_failed(job.task_id, error)
                self.jobs_failed += 1
                self.log_memory_usage("JOB_FAILED")
                await self.cleanup_temp_files(job.task_id)
                await self.clear_active_export(job.user_id)
                return True

        except Exception as e:
            logger.error(f"❌ Unexpected error in job {job.task_id}: {e}", exc_info=True)
            # ВСЕГДА уведомляем юзера об ошибке
            if job.user_chat_id and self.java_client:
                await self.java_client._notify_user_failure(
                    job.user_chat_id, job.task_id, str(e)
                )
            await self.queue_consumer.mark_job_failed(job.task_id, str(e))
            self.jobs_failed += 1
            self.log_memory_usage("JOB_ERROR")
            await self.cleanup_temp_files(job.task_id)
            await self.clear_active_export(job.user_id)
            return True

    async def _export_with_date_cache(self, job: ExportRequest) -> Optional[list[ExportedMessage]]:
        """
        Date-range export with cache support.

        1. Check which date sub-ranges are already cached
        2. Fetch only missing date ranges from Telegram
        3. Store fresh messages in cache
        4. Merge cached + fresh → return complete result
        """
        from_date_str = job.from_date[:10] if job.from_date else None
        to_date_str = job.to_date[:10] if job.to_date else None

        # Fill defaults for partial date filters
        if not from_date_str and to_date_str:
            from_date_str = "2000-01-01"
        if from_date_str and not to_date_str:
            to_date_str = datetime.now().strftime("%Y-%m-%d")

        # Find which date ranges we're missing
        missing = await self.message_cache.get_missing_date_ranges(
            job.chat_id, from_date_str, to_date_str
        )

        if not missing:
            # Everything cached — read directly
            logger.info(f"  Full date cache hit for {job.chat_id} [{from_date_str} - {to_date_str}]")
            cached = await self.message_cache.get_messages_by_date(
                job.chat_id, from_date_str, to_date_str
            )
            return self.message_cache.merge_and_sort(cached, [])

        logger.info(f"  Date cache partial hit for {job.chat_id}: missing={missing}")

        # Получаем total для прогресса (только для запрошенного диапазона)
        from_dt = datetime.fromisoformat(from_date_str + "T00:00:00")
        to_dt = datetime.fromisoformat(to_date_str + "T23:59:59")
        total = await self.telegram_client.get_messages_count(
            job.chat_id, from_dt, to_dt
        )

        # Прогресс-трекер
        tracker = self._create_tracker(job)
        if tracker:
            await tracker.start(total)

        # Fetch each missing date range from Telegram
        fresh_messages: list[ExportedMessage] = []
        fetched_count = 0
        for gap_from, gap_to in missing:
            gap_from_dt = datetime.fromisoformat(gap_from + "T00:00:00")
            gap_to_dt = datetime.fromisoformat(gap_to + "T23:59:59")

            gap_msgs: list[ExportedMessage] = []
            try:
                async for msg in self.telegram_client.get_chat_history(
                    chat_id=job.chat_id,
                    limit=0,
                    offset_id=0,
                    min_id=0,
                    from_date=gap_from_dt,
                    to_date=gap_to_dt,
                ):
                    gap_msgs.append(msg)
                    fetched_count += 1
                    if await self._check_cancel_and_save(job, gap_msgs, fetched_count):
                        return None
                    if tracker:
                        await tracker.track(fetched_count)
            except Exception as e:
                logger.warning(f"Failed fetching date gap [{gap_from} - {gap_to}]: {e}")

            if gap_msgs:
                logger.info(f"  Fetched {len(gap_msgs)} messages for [{gap_from} - {gap_to}]")
                await self.message_cache.store_messages(job.chat_id, gap_msgs)
                fresh_messages.extend(gap_msgs)

        # Retrieve all messages for the full requested date range from cache
        cached = await self.message_cache.get_messages_by_date(
            job.chat_id, from_date_str, to_date_str
        )
        messages = self.message_cache.merge_and_sort(cached, fresh_messages)
        logger.info(f"  Date-range export complete: {len(messages)} messages")

        await self.message_cache.evict_if_needed()
        return messages

    async def _export_with_id_cache(self, job: ExportRequest) -> Optional[list[ExportedMessage]]:
        """
        Full export (no date filter) with cache by message ID.

        1. Check which ID ranges are cached
        2. Fetch newer messages + fill ID gaps
        3. Store in cache, merge, return
        """
        cached_ranges = await self.message_cache.get_cached_ranges(job.chat_id)

        if not cached_ranges:
            # No cache — full fetch, populate cache
            messages = await self._fetch_all_messages(job)
            if messages:
                await self.message_cache.store_messages(job.chat_id, messages)
                await self.message_cache.evict_if_needed()
            return messages

        cache_max_id = max(r[1] for r in cached_ranges)
        logger.info(f"  Cache hit for chat {job.chat_id}: ranges={cached_ranges}")

        # Получаем total для прогресс-репортинга
        total = await self.telegram_client.get_messages_count(job.chat_id)
        if job.limit and job.limit > 0 and (total is None or job.limit < total):
            total = job.limit

        # Прогресс-трекер
        tracker = self._create_tracker(job)
        if tracker:
            await tracker.start(total)

        fresh_messages: list[ExportedMessage] = []
        fetched_count = 0

        # Step 1: fetch messages NEWER than cache
        try:
            async for msg in self.telegram_client.get_chat_history(
                chat_id=job.chat_id,
                limit=job.limit,
                offset_id=0,
                min_id=cache_max_id,
            ):
                fresh_messages.append(msg)
                fetched_count += 1
                if await self._check_cancel_and_save(job, fresh_messages, fetched_count):
                    return None
                if tracker:
                    await tracker.track(fetched_count)
        except Exception as e:
            logger.warning(f"Failed fetching new messages above cache: {e}")

        if fresh_messages:
            logger.info(f"  Fetched {len(fresh_messages)} new messages above cache max {cache_max_id}")
            await self.message_cache.store_messages(job.chat_id, fresh_messages)

        # Step 2: fill ID gaps (use lowest cached range start, not 1)
        full_min = min(r[0] for r in cached_ranges)
        full_max = max(cache_max_id, fresh_messages[0].id if fresh_messages else cache_max_id)
        missing = await self.message_cache.get_missing_ranges(job.chat_id, full_min, full_max)

        for gap_low, gap_high in missing:
            gap_msgs: list[ExportedMessage] = []
            try:
                async for msg in self.telegram_client.get_chat_history(
                    chat_id=job.chat_id,
                    limit=0,
                    offset_id=gap_high + 1,
                    min_id=gap_low - 1,
                ):
                    gap_msgs.append(msg)
                    fetched_count += 1
                    if await self._check_cancel_and_save(job, gap_msgs, fetched_count):
                        return None
                    if tracker:
                        await tracker.track(fetched_count)
            except Exception as e:
                logger.warning(f"Failed fetching gap [{gap_low}-{gap_high}]: {e}")

            if gap_msgs:
                logger.info(f"  Filled gap [{gap_low}-{gap_high}]: {len(gap_msgs)} messages")
                await self.message_cache.store_messages(job.chat_id, gap_msgs)
                fresh_messages.extend(gap_msgs)

        # Step 3: fetch messages OLDER than cache minimum (full export only)
        if not job.limit or job.limit <= 0:
            cache_min_id = min(r[0] for r in cached_ranges)
            if cache_min_id > 1:
                older_msgs: list[ExportedMessage] = []
                try:
                    async for msg in self.telegram_client.get_chat_history(
                        chat_id=job.chat_id,
                        limit=0,
                        offset_id=cache_min_id,
                        min_id=0,
                    ):
                        older_msgs.append(msg)
                        fetched_count += 1
                        if await self._check_cancel_and_save(job, older_msgs, fetched_count):
                            return None
                        if tracker:
                            await tracker.track(fetched_count)
                except Exception as e:
                    logger.warning(f"Failed fetching older messages below cache min {cache_min_id}: {e}")

                if older_msgs:
                    logger.info(f"  Fetched {len(older_msgs)} older messages below cache min {cache_min_id}")
                    await self.message_cache.store_messages(job.chat_id, older_msgs)
                    fresh_messages.extend(older_msgs)

        # Retrieve all from cache (use ID 0 as lower bound to include everything)
        actual_min = 0 if (not job.limit or job.limit <= 0) else full_min
        cached_messages = await self.message_cache.get_messages(job.chat_id, actual_min, full_max)
        messages = self.message_cache.merge_and_sort(cached_messages, fresh_messages)
        logger.info(f"  Total after cache merge: {len(messages)} messages")

        await self.message_cache.evict_if_needed()
        return messages

    async def _fetch_all_messages(self, job: ExportRequest) -> Optional[list[ExportedMessage]]:
        """
        Fetch all messages from Telegram API with progress reporting.

        Returns list of messages, or None if export failed (error already reported).
        """
        messages: list[ExportedMessage] = []

        # Parse date filters
        from_date = None
        to_date = None
        if job.from_date:
            try:
                from_date = datetime.fromisoformat(job.from_date)
            except ValueError:
                pass
        if job.to_date:
            try:
                to_date = datetime.fromisoformat(job.to_date)
            except ValueError:
                pass

        # Получаем total — ВСЕГДА перед экспортом
        total = await self.telegram_client.get_messages_count(
            job.chat_id, from_date, to_date
        )
        if job.limit and job.limit > 0 and (total is None or job.limit < total):
            total = job.limit

        # Прогресс-трекер
        tracker = self._create_tracker(job)
        if tracker:
            await tracker.start(total)

        try:
            async for message in self.telegram_client.get_chat_history(
                chat_id=job.chat_id,
                limit=job.limit,
                offset_id=job.offset_id,
                min_id=0,
                from_date=from_date,
                to_date=to_date,
            ):
                messages.append(message)
                count = len(messages)

                if await self._check_cancel_and_save(job, messages, count):
                    return None

                if tracker:
                    await tracker.track(count)
                elif count % 10000 == 0:
                    logger.info(f"  Exported {count} messages...")

        except Exception as e:
            error = f"Export failed: {str(e)}"
            logger.error(f"❌ {error}")
            await self.java_client.send_response(
                task_id=job.task_id,
                status="failed",
                messages=messages,
                error=error,
                error_code="EXPORT_ERROR",
                user_chat_id=job.user_chat_id,
            )
            await self.queue_consumer.mark_job_failed(job.task_id, error)
            return None

        return messages

    async def run(self):
        """
        Main worker loop.

        Process jobs from queue until shutdown signal received.
        """
        if not await self.initialize():
            sys.exit(1)

        self.running = True
        logger.info("🔄 Worker ready, waiting for jobs...")

        try:
            while self.running:
                try:
                    # Get next job (blocking)
                    job = await self.queue_consumer.get_job()

                    if job:
                        await self.process_job(job)
                    else:
                        # No job - should not happen with BLPOP timeout=0
                        await asyncio.sleep(1)

                except asyncio.CancelledError:
                    logger.info("Worker cancelled")
                    break

                except KeyboardInterrupt:
                    logger.info("Worker interrupted")
                    break

                except Exception as e:
                    logger.error(f"Error in main loop: {e}", exc_info=True)
                    await asyncio.sleep(5)

        finally:
            await self.cleanup()

    async def cleanup(self):
        """Clean shutdown."""
        logger.info("🛑 Shutting down worker...")

        self.running = False

        if self.telegram_client:
            await self.telegram_client.disconnect()

        if self.queue_consumer:
            await self.queue_consumer.disconnect()

        if self.java_client:
            await self.java_client.aclose()

        if self.control_redis:
            await self.control_redis.aclose()

        if self.message_cache and self.message_cache.redis:
            await self.message_cache.redis.aclose()

        logger.info(
            f"📊 Final stats: {self.jobs_processed} processed, "
            f"{self.jobs_failed} failed"
        )
        logger.info("✅ Worker stopped")

    def handle_signal(self, signum, frame):
        """Handle SIGTERM/SIGINT."""
        logger.info(f"Received signal {signum}")
        self.running = False


async def main():
    """Main entry point."""
    worker = ExportWorker()

    # Setup asyncio-compatible signal handlers
    try:
        loop = asyncio.get_running_loop()

        def _on_signal():
            logger.info("Shutdown signal received")
            worker.running = False

        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, _on_signal)
    except NotImplementedError:
        # Windows doesn't support add_signal_handler
        # Fall back to synchronous handler
        signal.signal(signal.SIGTERM, worker.handle_signal)
        signal.signal(signal.SIGINT, worker.handle_signal)

    try:
        await worker.run()

    except KeyboardInterrupt:
        logger.info("Interrupted by user")

    except Exception as e:
        logger.error(f"Fatal error: {e}", exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
