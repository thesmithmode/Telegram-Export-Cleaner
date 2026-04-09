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
from datetime import date as date_cls, datetime, timedelta, timezone
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
        self._redis_pool: Optional[aioredis.ConnectionPool] = None
        self.running = False
        self.jobs_processed = 0
        self.jobs_failed = 0

    async def _check_cancel_and_save(
        self, job: ExportRequest, all_messages: list[ExportedMessage], count: int
    ) -> bool:
        """Check cancellation every 100 messages, save ALL accumulated messages to cache if cancelled.
        Returns True if cancelled."""
        if count % 100 != 0:
            return False
        if not await self.is_cancelled(job.task_id):
            return False
        logger.info(f"🛑 Export {job.task_id} cancelled by user at {count} messages")
        if self.message_cache and self.message_cache.enabled and all_messages:
            await self.message_cache.store_messages(job.chat_id, all_messages)
            logger.info(f"  Saved {len(all_messages)} messages to cache before cancel")
        await self.clear_active_export(job.user_id)
        return True

    async def is_cancelled(self, task_id: str) -> bool:
        """Check if export was cancelled by user."""
        if not self.control_redis:
            return False
        try:
            val = await self.control_redis.get(f"cancel_export:{task_id}")
            return val is not None
        except Exception:
            return False

    async def clear_active_export(self, user_id: int):
        """Clear active export marker for user."""
        if self.control_redis:
            try:
                await self.control_redis.delete(f"active_export:{user_id}")
            except Exception:
                pass

    async def set_active_processing_job(self, task_id: str):
        """Сообщаем Java-боту что воркер сейчас занят этой задачей."""
        if self.control_redis:
            try:
                await self.control_redis.set("active_processing_job", task_id, ex=3600)
            except Exception:
                pass

    async def clear_active_processing_job(self):
        """Сбрасываем флаг занятости воркера."""
        if self.control_redis:
            try:
                await self.control_redis.delete("active_processing_job")
            except Exception:
                pass

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
            cpu_percent = psutil.cpu_percent(interval=None)
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

    async def _cleanup_job(self, job: ExportRequest):
        """Общий cleanup после завершения задачи (успех, ошибка, отмена)."""
        await self.cleanup_temp_files(job.task_id)
        await self.clear_active_export(job.user_id)
        await self.clear_active_processing_job()

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

            # 1.5. Recover jobs from previous crash (durability)
            recovered = await self.queue_consumer.recover_staging_jobs()
            if recovered:
                logger.info(f"  Recovered {recovered} orphaned jobs — they will be reprocessed")

            # 2. Connect to Telegram API
            logger.info("2️⃣  Connecting to Telegram API...")
            self.telegram_client = await create_telegram_client()

            # 3. Connect to Java Bot API
            logger.info("3️⃣  Connecting to Java Bot API...")
            self.java_client = await create_java_client()

            # 4. Initialize Redis (один пул, два клиентских вида: str и bytes)
            self._redis_pool = aioredis.ConnectionPool(
                host=settings.REDIS_HOST,
                port=settings.REDIS_PORT,
                db=settings.REDIS_DB,
                password=settings.REDIS_PASSWORD,
                socket_timeout=10,
                socket_connect_timeout=5,
            )
            self.control_redis = aioredis.Redis(
                connection_pool=self._redis_pool,
                decode_responses=True,
            )

            # Передаём Redis-клиент в Telegram-клиент для canonical-маппинга
            self.telegram_client.redis_client = self.control_redis

            # 5. Initialize message cache
            logger.info("4️⃣  Initializing message cache...")
            cache_redis = aioredis.Redis(
                connection_pool=self._redis_pool,
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
            await self.set_active_processing_job(job.task_id)
            # Обновляем active_export чтобы /cancel работал даже после OOM-рестарта.
            # Java-бот ставит этот ключ при постановке в очередь (TTL 60 мин), но после
            # OOM-рестарта воркер восстанавливает задачу из staging — без обновления ключ
            # может истечь и /cancel вернёт "Нет активного экспорта".
            if self.control_redis and job.user_id:
                try:
                    await self.control_redis.set(
                        f"active_export:{job.user_id}", job.task_id, ex=3600
                    )
                except Exception:
                    pass

            # Проверяем отмену сразу при старте — задача могла быть отменена пока ждала в очереди
            if await self.is_cancelled(job.task_id):
                logger.info(f"🛑 Job {job.task_id} отменена до начала обработки")
                await self.queue_consumer.mark_job_completed(job.task_id)
                await self._cleanup_job(job)
                return True

            # Verify access and get chat info in single call
            accessible, chat_info, error_reason = await self.telegram_client.verify_and_get_info(job.chat_id)
            if not accessible:
                error_messages = {
                    "CHANNEL_PRIVATE": f"Канал {job.chat_id} приватный. Аккаунт worker-а должен быть участником.",
                    "USERNAME_NOT_FOUND": f"Username {job.chat_id} не найден. Проверьте правильность.",
                    "ADMIN_REQUIRED": f"Для экспорта чата {job.chat_id} нужны права администратора.",
                    "CHAT_NOT_ACCESSIBLE": (
                        f"Нет доступа к чату {job.chat_id}. "
                        f"Попробуйте отправить ссылку (t.me/...) вместо выбора через кнопку, "
                        f"или убедитесь что аккаунт worker-а — участник чата."
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
                await self._cleanup_job(job)
                return True

            if chat_info:
                logger.info(f"  Chat: {chat_info.get('title')} (type: {chat_info.get('type')})")
                # Нормализуем chat_id до канонического числового ID.
                # Пикер может передать числовой ID (-100...), ссылка — username.
                # Оба варианта должны использовать один и тот же ключ кэша.
                canonical_id = chat_info.get("id")
                original_chat_input = job.chat_id
                if canonical_id and canonical_id != job.chat_id:
                    logger.info(
                        f"  Normalizing chat_id: {job.chat_id!r} → {canonical_id}"
                    )
                    job = job.model_copy(update={"chat_id": canonical_id})
                # Сохраняем маппинг canonical: input → numeric_id, чтобы Java мог
                # проверить наличие кэша перед постановкой в очередь (fast-path).
                # Также сохраняем обратный маппинг canonical:<numeric_id> → username,
                # чтобы Java-бот мог резолвить числовые ID из пикера в username.
                if self.control_redis and canonical_id:
                    try:
                        await self.control_redis.set(
                            f"canonical:{original_chat_input}",
                            str(canonical_id),
                            ex=86400 * 30,
                        )
                        # Обратный маппинг: по numeric_id находим username
                        chat_username = chat_info.get("username")
                        if chat_username:
                            await self.control_redis.set(
                                f"canonical:{canonical_id}",
                                chat_username,
                                ex=86400 * 30,
                            )
                    except Exception:
                        pass

            # --- Cache-aware export ---
            # _export_with_id_cache  → returns (count, AsyncGenerator) or None
            # _export_with_date_cache / _fetch_all_messages → returns list or None
            # send_response() handles both via streaming temp-file approach.

            has_date_filter = job.from_date is not None or job.to_date is not None
            messages_for_send = None   # list[ExportedMessage] | AsyncGenerator
            msg_count = 0

            if self.message_cache and self.message_cache.enabled:
                if has_date_filter:
                    result = await self._export_with_date_cache(job)
                    if result is not None:
                        messages_for_send = result
                        msg_count = len(result)
                else:
                    result = await self._export_with_id_cache(job)
                    if result is not None:
                        msg_count, messages_for_send = result  # (int, AsyncGenerator)

            # Fallback: no cache, cache disabled, or cache export failed
            if messages_for_send is None:
                fallback = await self._fetch_all_messages(job)
                if fallback is None:
                    return True
                # Сохраняем в кэш — следующий запрос пойдёт через cache path
                if fallback and self.message_cache and self.message_cache.enabled:
                    try:
                        await self.message_cache.store_messages(job.chat_id, fallback)
                        await self.message_cache.evict_if_needed()
                        logger.info(f"  Cached {len(fallback)} messages for chat {job.chat_id} (fallback path)")
                    except Exception as e:
                        logger.warning(f"Failed to cache messages after fallback fetch: {e}")
                messages_for_send = fallback
                msg_count = len(fallback)

            # Проверяем отмену перед долгой отправкой в Java — на cache-hit пути
            # cancel_check в цикле фетча срабатывает 0 раз (новых сообщений мало).
            if await self.is_cancelled(job.task_id):
                logger.info(f"🛑 Job {job.task_id} отменена перед отправкой результата")
                await self.queue_consumer.mark_job_completed(job.task_id)
                await self._cleanup_job(job)
                return True

            # Send results to Java API, deliver cleaned text to user
            chat_title = chat_info.get('title') or chat_info.get('username') if chat_info else None
            success = await self.java_client.send_response(
                task_id=job.task_id,
                status="completed",
                messages=messages_for_send,
                count=msg_count,
                user_chat_id=job.user_chat_id,
                chat_title=chat_title,
                from_date=job.from_date,
                to_date=job.to_date,
                keywords=job.keywords,
                exclude_keywords=job.exclude_keywords,
            )

            if success:
                logger.info(f"✅ Job {job.task_id} completed ({msg_count} messages)")
                await self.queue_consumer.mark_job_completed(job.task_id)
                self.jobs_processed += 1
                self.log_memory_usage("JOB_DONE")
            else:
                error = "Failed to send response to Java Bot"
                logger.error(f"❌ {error}")
                await self.queue_consumer.mark_job_failed(job.task_id, error)
                self.jobs_failed += 1
                self.log_memory_usage("JOB_FAILED")

            await self._cleanup_job(job)
            return True

        except Exception as e:
            logger.error(f"❌ Unexpected error in job {job.task_id}: {e}", exc_info=True)
            # ВСЕГДА уведомляем юзера об ошибке
            if job.user_chat_id and self.java_client:
                await self.java_client._notify_user_failure(
                    job.user_chat_id, job.task_id, str(e)
                )
            # Всегда отправляем ответ об ошибке через Java API
            if self.java_client:
                await self.java_client.send_response(
                    task_id=job.task_id,
                    status="failed",
                    messages=[],
                    error=str(e),
                    user_chat_id=job.user_chat_id,
                )
            await self.queue_consumer.mark_job_failed(job.task_id, str(e))
            self.jobs_failed += 1
            self.log_memory_usage("JOB_ERROR")
            await self._cleanup_job(job)
            return True

    @staticmethod
    def _compute_cached_ranges(
        from_date: str, to_date: str, missing: list[tuple[str, str]]
    ) -> list[tuple[str, str]]:
        """Compute cached date ranges = [from_date, to_date] minus missing gaps."""
        if not missing:
            return [(from_date, to_date)]

        cached = []
        current = date_cls.fromisoformat(from_date)
        req_end = date_cls.fromisoformat(to_date)

        for gap_from, gap_to in sorted(missing):
            gap_start = date_cls.fromisoformat(gap_from)
            gap_end = date_cls.fromisoformat(gap_to)
            if gap_start > current:
                cached.append((current.isoformat(), (gap_start - timedelta(days=1)).isoformat()))
            current = gap_end + timedelta(days=1)

        if current <= req_end:
            cached.append((current.isoformat(), req_end.isoformat()))

        return cached

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
            to_date_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")

        # Find which date ranges we're missing
        missing = await self.message_cache.get_missing_date_ranges(
            job.chat_id, from_date_str, to_date_str
        )

        if not missing:
            # Everything cached — read directly
            logger.info(f"  Cache HIT (полный) для чата {job.chat_id} [{from_date_str} - {to_date_str}]")
            cached = await self.message_cache.get_messages_by_date(
                job.chat_id, from_date_str, to_date_str
            )
            return self.message_cache.merge_and_sort(cached, [])

        # Вычисляем закэшированные диапазоны (всё что НЕ в missing)
        cached_date_ranges = self._compute_cached_ranges(
            from_date_str, to_date_str, missing
        )
        logger.info(f"  Cache MISS (частичный) для чата {job.chat_id}: "
                     f"missing={missing}, cached={cached_date_ranges}")

        # Читаем закэшированные сообщения (только нужные диапазоны, не всё)
        cached_messages: list[ExportedMessage] = []
        for c_from, c_to in cached_date_ranges:
            chunk = await self.message_cache.get_messages_by_date(
                job.chat_id, c_from, c_to
            )
            cached_messages.extend(chunk)
        logger.info(f"  Loaded {len(cached_messages)} messages from cache")

        # Получаем total для прогресса (только для запрошенного диапазона)
        from_dt = datetime.fromisoformat(from_date_str + "T00:00:00+00:00")
        to_dt = datetime.fromisoformat(to_date_str + "T23:59:59+00:00")
        total = await self.telegram_client.get_messages_count(
            job.chat_id, from_dt, to_dt
        )

        # Прогресс-трекер
        tracker = self._create_tracker(job)
        if tracker:
            await tracker.start(total)
            # Отправить статус кэшированного прогресса одним обновлением (избежать скачка от 0 к N%)
            if cached_messages:
                await tracker.track(len(cached_messages))

        # Fetch each missing date range from Telegram
        fresh_messages: list[ExportedMessage] = []
        fetched_count = len(cached_messages)
        # all_fetched accumulates ALL messages for cancel-save
        all_fetched: list[ExportedMessage] = list(cached_messages)
        for gap_from, gap_to in missing:
            gap_from_dt = datetime.fromisoformat(gap_from + "T00:00:00+00:00")
            gap_to_dt = datetime.fromisoformat(gap_to + "T23:59:59+00:00")

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
                    all_fetched.append(msg)
                    fetched_count += 1
                    if await self._check_cancel_and_save(job, all_fetched, fetched_count):
                        return None
                    if tracker:
                        await tracker.track(fetched_count)
            except Exception as e:
                logger.warning(f"Failed fetching date gap [{gap_from} - {gap_to}]: {e}")

            if gap_msgs:
                logger.info(f"  Fetched {len(gap_msgs)} messages for [{gap_from} - {gap_to}]")
                await self.message_cache.store_messages(job.chat_id, gap_msgs)
                fresh_messages.extend(gap_msgs)

        # Merge cached + fresh (не перечитываем из Redis — всё уже в памяти)
        messages = self.message_cache.merge_and_sort(cached_messages, fresh_messages)
        logger.info(f"  Date-range export complete: {len(messages)} messages")

        if tracker:
            await tracker.finalize(len(messages))

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
            logger.info(f"  Cache MISS для чата {job.chat_id} — полная загрузка")
            # No cache — full fetch, populate cache
            messages = await self._fetch_all_messages(job)
            if messages:
                await self.message_cache.store_messages(job.chat_id, messages)
                await self.message_cache.evict_if_needed()
            return messages

        cache_max_id = max(r[1] for r in cached_ranges)
        logger.info(f"  Cache HIT для чата {job.chat_id}: ranges={cached_ranges}, cache_max_id={cache_max_id}")

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
        # all_fetched accumulates ALL messages across all fetch phases for cancel-save
        all_fetched: list[ExportedMessage] = list(fresh_messages)
        try:
            async for msg in self.telegram_client.get_chat_history(
                chat_id=job.chat_id,
                limit=job.limit,
                offset_id=0,
                min_id=cache_max_id,
            ):
                fresh_messages.append(msg)
                all_fetched.append(msg)
                fetched_count += 1
                if await self._check_cancel_and_save(job, all_fetched, fetched_count):
                    return None
                if tracker:
                    await tracker.track(fetched_count)
        except Exception as e:
            logger.warning(f"Failed fetching new messages above cache: {e}")

        if fresh_messages:
            logger.info(f"  Fetched {len(fresh_messages)} new messages above cache max {cache_max_id}")
            await self.message_cache.store_messages(job.chat_id, fresh_messages)

        # Step 2: fill ID gaps
        logger.info(f"  Step 2: Computing missing ID ranges...")
        full_min = min(r[0] for r in cached_ranges)
        full_max = max(cache_max_id, fresh_messages[0].id if fresh_messages else cache_max_id)
        logger.info(f"    Range to check: [{full_min}, {full_max}]")
        missing = await self.message_cache.get_missing_ranges(job.chat_id, full_min, full_max)
        logger.info(f"    Found {len(missing)} gaps: {missing}")

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
                    all_fetched.append(gap_msgs[-1])
                    if await self._check_cancel_and_save(job, all_fetched, fetched_count):
                        return None
                    if tracker:
                        await tracker.track(fetched_count)
            except Exception as e:
                logger.warning(f"Failed fetching gap [{gap_low}-{gap_high}]: {e}")

            if gap_msgs:
                logger.info(f"  Filled gap [{gap_low}-{gap_high}]: {len(gap_msgs)} messages")
                await self.message_cache.store_messages(job.chat_id, gap_msgs)
                fresh_messages.extend(gap_msgs)

        # Step 3: fetch messages OLDER than cache minimum
        logger.info(f"  Step 3: Fetching older messages...")
        if not job.limit or job.limit <= 0:
            cache_min_id = min(r[0] for r in cached_ranges)
            logger.info(f"    Cache min ID: {cache_min_id}")
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
                        all_fetched.append(older_msgs[-1])
                        if await self._check_cancel_and_save(job, all_fetched, fetched_count):
                            return None
                        if tracker:
                            await tracker.track(fetched_count)
                except Exception as e:
                    logger.warning(f"Failed fetching older messages below cache min {cache_min_id}: {e}")

                if older_msgs:
                    logger.info(f"  Fetched {len(older_msgs)} older messages below cache min {cache_min_id}")
                    await self.message_cache.store_messages(job.chat_id, older_msgs)
                    fresh_messages.extend(older_msgs)

        # Освобождаем fresh_messages — они уже сохранены в кэш выше.
        del fresh_messages

        # Считаем итоговое кол-во через ZCOUNT (O(1), без загрузки в память).
        actual_min = 0 if (not job.limit or job.limit <= 0) else full_min
        count = await self.message_cache.count_messages(job.chat_id, actual_min, full_max)
        logger.info(f"  Total after cache merge: {count} messages")

        # Проверяем отмену ДО finalize — иначе спамит 100% при каждом рестарте
        if await self.is_cancelled(job.task_id):
            logger.info(f"🛑 Export {job.task_id} отменён (перед finalize)")
            await self.clear_active_export(job.user_id)
            return None

        if tracker:
            await tracker.finalize(count)

        await self.message_cache.evict_if_needed()

        # Возвращаем (count, generator) — вызывающий код стримит через iter_messages,
        # не держа все 252K объектов в памяти одновременно.
        return count, self.message_cache.iter_messages(job.chat_id, actual_min, full_max)

    async def _fetch_all_messages(self, job: ExportRequest) -> Optional[list[ExportedMessage]]:
        """
        Fetch all messages from Telegram API with progress reporting.

        Returns list of messages, or None if export failed (error already reported).
        """
        messages: list[ExportedMessage] = []

        # Parse date filters (ensure UTC-aware для корректного сравнения с Pyrogram)
        from_date = None
        to_date = None
        if job.from_date:
            try:
                dt = datetime.fromisoformat(job.from_date)
                from_date = dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
            except ValueError:
                pass
        if job.to_date:
            try:
                dt = datetime.fromisoformat(job.to_date)
                to_date = dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
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
            # Re-raise so the caller (process_job) handles notification consistently,
            # avoiding duplicate failure messages to the user.
            raise

        if tracker:
            await tracker.finalize(len(messages))

        return messages

    async def _update_all_queue_positions(self, current_task_id: str) -> None:
        """
        Notify each queued user of their updated position.

        Called right before processing a job: the job being processed gets
        position=0 ("started"), the rest get their 1-based queue position.
        """
        if not self.control_redis or not self.java_client:
            return
        try:
            pending_result = await self.queue_consumer.get_pending_jobs()
            pending = pending_result["jobs"]
            total = pending_result["total_count"]
            # Notify the job that is about to start
            val = await self.control_redis.get(f"queue_msg:{current_task_id}")
            if val:
                user_chat_id_str, msg_id_str = val.split(":", 1)
                await self.java_client.update_queue_position(
                    int(user_chat_id_str), int(msg_id_str), 0, total
                )
            # Notify remaining queued jobs of their new position
            for i, job in enumerate(pending):
                val = await self.control_redis.get(f"queue_msg:{job.task_id}")
                if not val:
                    continue
                user_chat_id_str, msg_id_str = val.split(":", 1)
                await self.java_client.update_queue_position(
                    int(user_chat_id_str), int(msg_id_str), i + 1, total
                )
        except Exception as e:
            logger.warning(f"Could not update queue positions: {e}")

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
                        await self._update_all_queue_positions(job.task_id)
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

        if self._redis_pool:
            await self._redis_pool.aclose()

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
