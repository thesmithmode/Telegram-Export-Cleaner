#!/usr/bin/env python3

import logging
import asyncio
import signal
import sys
import psutil
from datetime import date as date_cls, datetime, timedelta, timezone
from typing import AsyncGenerator, Optional

import redis.asyncio as aioredis

from config import settings
from pyrogram_client import TelegramClient, create_client as create_telegram_client, ensure_utc
from queue_consumer import QueueConsumer, create_queue_consumer
from java_client import JavaBotClient, ProgressTracker, create_java_client
from message_cache import MessageCache
from models import ExportRequest, ExportedMessage, SendResponsePayload

# Setup logging
logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)

logger = logging.getLogger(__name__)

class ExportWorker:

    def __init__(self):
        self.telegram_client: Optional[TelegramClient] = None
        self.queue_consumer: Optional[QueueConsumer] = None
        self.java_client: Optional[JavaBotClient] = None
        self.message_cache: Optional[MessageCache] = None
        self.control_redis: Optional[aioredis.Redis] = None
        self._redis_pool: Optional[aioredis.ConnectionPool] = None
        self.running = False
        self.jobs_processed = 0
        self.jobs_failed = 0

    async def _flush_batch_and_check_cancel(
        self,
        job: ExportRequest,
        batch: list[ExportedMessage],
    ) -> bool:
        if self.message_cache and self.message_cache.enabled and batch:
            await self.message_cache.store_messages(job.chat_id, batch)
            batch.clear()

        if not await self.is_cancelled(job.task_id):
            return False

        logger.info(f"🛑 Export {job.task_id} cancelled by user")
        await self.clear_active_export(job.user_id)
        return True

    # Maximum messages buffered in RAM before writing to cache.
    # 1000 × ~200 B ≈ 200 KB — safe even on 512 MB containers.
    _CACHE_BATCH_SIZE: int = 1_000

    async def is_cancelled(self, task_id: str) -> bool:
        if not self.control_redis:
            return False
        try:
            val = await self.control_redis.get(f"cancel_export:{task_id}")
            return val is not None
        except Exception:
            return False

    async def clear_active_export(self, user_id: int) -> None:
        if self.control_redis:
            try:
                await self.control_redis.delete(f"active_export:{user_id}")
            except Exception:
                pass

    async def set_active_processing_job(self, task_id: str) -> None:
        if self.control_redis:
            try:
                await self.control_redis.set("active_processing_job", task_id, ex=3600)
            except Exception:
                pass

    async def clear_active_processing_job(self) -> None:
        if self.control_redis:
            try:
                await self.control_redis.delete("active_processing_job")
            except Exception:
                pass

    def _create_tracker(self, job: ExportRequest) -> Optional[ProgressTracker]:
        if job.user_chat_id and self.java_client:
            return self.java_client.create_progress_tracker(
                job.user_chat_id, job.task_id
            )
        return None

    def log_memory_usage(self, stage: str) -> None:
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

    async def _cleanup_job(self, job: ExportRequest) -> None:
        await self.clear_active_export(job.user_id)
        await self.clear_active_processing_job()

    async def initialize(self) -> bool:
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

            # 4. Initialize Redis connections (два отдельных клиента — разные decode_responses)
            _redis_kwargs = dict(
                host=settings.REDIS_HOST,
                port=settings.REDIS_PORT,
                db=settings.REDIS_DB,
                password=settings.REDIS_PASSWORD,
                socket_timeout=10,
                socket_connect_timeout=5,
            )
            # control_redis — строки (cancel, active_export, canonical mappings)
            self.control_redis = aioredis.Redis(
                decode_responses=True,
                **_redis_kwargs,
            )

            # Передаём Redis-клиент в Telegram-клиент для canonical-маппинга
            self.telegram_client.redis_client = self.control_redis

            # 5. Initialize message cache (SQLite on disk)
            logger.info("4️⃣  Initializing message cache (SQLite)...")
            self.message_cache = MessageCache(
                db_path=settings.CACHE_DB_PATH,
                max_disk_bytes=int(settings.CACHE_MAX_DISK_GB * 1024 ** 3),
                max_messages_per_chat=settings.CACHE_MAX_MESSAGES_PER_CHAT,
                ttl_seconds=settings.CACHE_TTL_SECONDS,
                enabled=settings.CACHE_ENABLED,
            )
            await self.message_cache.initialize()
            logger.info(
                f"  Cache: enabled={settings.CACHE_ENABLED}, "
                f"db={settings.CACHE_DB_PATH}, "
                f"max_disk={settings.CACHE_MAX_DISK_GB}GB"
            )

            logger.info("✅ All components initialized successfully")
            return True

        except Exception as e:
            logger.error(f"❌ Initialization failed: {e}")
            await self.cleanup()
            return False

    async def process_job(self, job: ExportRequest) -> bool:
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
                    SendResponsePayload(
                        task_id=job.task_id,
                        status="failed",
                        messages=[],
                        error=error,
                        error_code=error_code,
                        user_chat_id=job.user_chat_id,
                    )
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
                    # Пишем оба маппинга одним pipeline (2 RTT → 1 RTT).
                    # Не глотаем исключения: если Redis недоступен или мок в тесте
                    # настроен неправильно, caller должен это УВИДЕТЬ, а не
                    # замести под ковёр. Ошибка логируется на WARN — дальнейший
                    # экспорт не блокируется (маппинг не критичен для самой job),
                    # но SRE получит сигнал в логах.
                    try:
                        pipe = self.control_redis.pipeline()
                        pipe.set(
                            f"canonical:{original_chat_input}",
                            str(canonical_id),
                            ex=86400 * 30,
                        )
                        chat_username = chat_info.get("username")
                        if chat_username:
                            pipe.set(
                                f"canonical:{canonical_id}",
                                chat_username,
                                ex=86400 * 30,
                            )
                        await pipe.execute()
                    except Exception as canonical_err:
                        logger.warning(
                            f"Failed to write canonical mapping for chat "
                            f"{original_chat_input!r} → {canonical_id}: {canonical_err}"
                        )

            # --- Cache-aware export ---
            # Оба пути возвращают (count: int, messages: AsyncGenerator) или None.
            # None означает: отменено ИЛИ ошибка кэша → нужно проверить cancel перед fallback.
            # send_response() принимает оба формата (list и AsyncGenerator).

            has_date_filter = job.from_date is not None or job.to_date is not None
            messages_for_send = None   # AsyncGenerator | list[ExportedMessage]
            msg_count = 0
            cache_was_tried = False

            if self.message_cache and self.message_cache.enabled:
                cache_was_tried = True
                if has_date_filter:
                    result = await self._export_with_date_cache(job)
                else:
                    result = await self._export_with_id_cache(job)

                if result is not None:
                    msg_count, messages_for_send = result  # (int, AsyncGenerator)

            # Если кэш вернул None — сначала проверяем отмену (не гоним в fallback зря)
            if messages_for_send is None and cache_was_tried:
                if await self.is_cancelled(job.task_id):
                    logger.info(f"🛑 Job {job.task_id} отменена после cache path")
                    await self.queue_consumer.mark_job_completed(job.task_id)
                    await self._cleanup_job(job)
                    return True

            # Fallback: кэш отключён или cache path завершился с ошибкой (не отменой)
            if messages_for_send is None:
                # Финальная проверка отмены перед тяжёлым fallback-запросом
                if await self.is_cancelled(job.task_id):
                    logger.info(f"🛑 Job {job.task_id} отменена перед fallback")
                    await self.queue_consumer.mark_job_completed(job.task_id)
                    await self._cleanup_job(job)
                    return True
                fallback_result = await self._fetch_all_messages(job)
                if fallback_result is None:
                    return True
                # _fetch_all_messages уже записал сообщения в кэш батчами
                msg_count, messages_for_send = fallback_result
                if self.message_cache and self.message_cache.enabled:
                    await self.message_cache.evict_if_needed()

            # Финальная проверка отмены перед отправкой в Java
            if await self.is_cancelled(job.task_id):
                logger.info(f"🛑 Job {job.task_id} отменена перед отправкой результата")
                await self.queue_consumer.mark_job_completed(job.task_id)
                await self._cleanup_job(job)
                return True

            # Send results to Java API, deliver cleaned text to user
            chat_title = chat_info.get('title') or chat_info.get('username') if chat_info else None
            success = await self.java_client.send_response(
                SendResponsePayload(
                    task_id=job.task_id,
                    status="completed",
                    messages=messages_for_send,
                    actual_count=msg_count,
                    user_chat_id=job.user_chat_id,
                    chat_title=chat_title,
                    from_date=job.from_date,
                    to_date=job.to_date,
                    keywords=job.keywords,
                    exclude_keywords=job.exclude_keywords,
                )
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
            # Префикс "Export failed:" даёт юзеру осмысленный текст в Telegram
            # вместо голого str(e), и совпадает с форматом, который показывает
            # _notify_user_failure ("❌ Export failed (task ...)").
            error_text = f"Export failed: {e}"

            # Preferred path: send_response умеет нотифицировать юзера и
            # выполнить server-side cleanup. Но если сам send_response падает
            # (OOM, сеть, баг в клиенте), юзер всё равно должен получить
            # сообщение — поэтому есть direct fallback к _notify_user_failure.
            # Без fallback после OOM в send_response юзер «зависает» без feedback,
            # а active_export ключ остаётся в Redis до TTL.
            notified_via_send_response = False
            if self.java_client:
                try:
                    await self.java_client.send_response(
                        SendResponsePayload(
                            task_id=job.task_id,
                            status="failed",
                            messages=[],
                            error=error_text,
                            user_chat_id=job.user_chat_id,
                        )
                    )
                    notified_via_send_response = True
                except Exception as notify_err:
                    logger.error(
                        f"send_response failed during error handling for "
                        f"{job.task_id}: {notify_err}. Falling back to direct "
                        f"user notification.",
                        exc_info=True,
                    )

            if (
                not notified_via_send_response
                and self.java_client
                and job.user_chat_id
            ):
                try:
                    await self.java_client.notify_user_failure(
                        job.user_chat_id, job.task_id, error_text
                    )
                except Exception as fallback_err:
                    logger.error(
                        f"Direct notify_user_failure also failed for "
                        f"{job.task_id}: {fallback_err}"
                    )

            try:
                await self.queue_consumer.mark_job_failed(job.task_id, error_text)
            except Exception as mark_err:
                logger.error(
                    f"Failed to mark job {job.task_id} as failed: {mark_err}"
                )

            self.jobs_failed += 1
            self.log_memory_usage("JOB_ERROR")
            try:
                await self._cleanup_job(job)
            except Exception as cleanup_err:
                logger.error(
                    f"Cleanup failed for {job.task_id}: {cleanup_err}"
                )
            return True

    @staticmethod
    def _compute_cached_ranges(
        from_date: str, to_date: str, missing: list[tuple[str, str]]
    ) -> list[tuple[str, str]]:
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

    async def _export_with_date_cache(
        self, job: ExportRequest
    ) -> Optional[tuple[int, AsyncGenerator]]:
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
            # Full cache HIT — count without loading, stream without loading
            count = await self.message_cache.count_messages_by_date(
                job.chat_id, from_date_str, to_date_str
            )
            logger.info(
                f"  Cache HIT (полный) для чата {job.chat_id} "
                f"[{from_date_str} - {to_date_str}]: {count} сообщений"
            )
            tracker = self._create_tracker(job)
            if tracker:
                await tracker.start(count)

            if await self.is_cancelled(job.task_id):
                logger.info(f"🛑 Export {job.task_id} отменён (перед finalize, date cache HIT)")
                await self.clear_active_export(job.user_id)
                return None

            if tracker:
                await tracker.finalize(count)

            await self.message_cache.evict_if_needed()
            return count, self.message_cache.iter_messages_by_date(
                job.chat_id, from_date_str, to_date_str
            )

        # Partial or full miss — fetch missing date ranges from Telegram
        logger.info(
            f"  Cache MISS (частичный) для чата {job.chat_id}: missing={missing}"
        )

        tracker = self._create_tracker(job)
        if tracker:
            await tracker.start()

        # Получаем total для прогресса (запрашиваем у Telegram для всего диапазона)
        from_dt = datetime.fromisoformat(from_date_str + "T00:00:00+00:00")
        to_dt = datetime.fromisoformat(to_date_str + "T23:59:59+00:00")
        total = await self.telegram_client.get_messages_count(
            job.chat_id, from_dt, to_dt
        )

        # Cached count at start (for progress offset)
        cached_count = await self.message_cache.count_messages_by_date(
            job.chat_id, from_date_str, to_date_str
        )

        if tracker:
            await tracker.set_total(total)
            if cached_count and not (job.limit and job.limit > 0):
                await tracker.seed(cached_count)

        # Fetch only missing gaps from Telegram, store in cache in batches of 1000
        fetched_count = cached_count
        for gap_from, gap_to in missing:
            gap_from_dt = datetime.fromisoformat(gap_from + "T00:00:00+00:00")
            gap_to_dt = datetime.fromisoformat(gap_to + "T23:59:59+00:00")

            batch: list[ExportedMessage] = []
            gap_fetched = 0

            try:
                async for msg in self.telegram_client.get_chat_history(
                    chat_id=job.chat_id,
                    limit=0,
                    offset_id=0,
                    min_id=0,
                    from_date=gap_from_dt,
                    to_date=gap_to_dt,
                    on_floodwait=tracker.on_floodwait if tracker else None,
                ):
                    batch.append(msg)
                    fetched_count += 1
                    gap_fetched += 1
                    if len(batch) >= self._CACHE_BATCH_SIZE:
                        if await self._flush_batch_and_check_cancel(job, batch):
                            return None
                    if tracker:
                        await tracker.track(fetched_count)
            except Exception as e:
                logger.warning(f"Failed fetching date gap [{gap_from} - {gap_to}]: {e}")

            # Flush remaining batch for this gap
            if batch:
                await self.message_cache.store_messages(job.chat_id, batch)
            if gap_fetched:
                logger.info(f"  Fetched {gap_fetched} messages for [{gap_from} - {gap_to}]")
            elif self.message_cache and self.message_cache.enabled:
                # Telegram вернул 0 сообщений — диапазон проверен, фиксируем чтобы не ходить снова
                await self.message_cache.mark_date_range_checked(job.chat_id, gap_from, gap_to)

        # After storing all gaps, count final total from cache (authoritative)
        count = await self.message_cache.count_messages_by_date(
            job.chat_id, from_date_str, to_date_str
        )
        logger.info(f"  Date-range export complete: {count} messages")

        if await self.is_cancelled(job.task_id):
            logger.info(f"🛑 Export {job.task_id} отменён (перед finalize, date cache MISS)")
            await self.clear_active_export(job.user_id)
            return None

        if tracker:
            await tracker.finalize(count)

        await self.message_cache.evict_if_needed()
        return count, self.message_cache.iter_messages_by_date(
            job.chat_id, from_date_str, to_date_str
        )

    async def _export_with_id_cache(
        self, job: ExportRequest
    ) -> Optional[tuple[int, AsyncGenerator]]:
        cached_ranges = await self.message_cache.get_cached_ranges(job.chat_id)

        if not cached_ranges:
            logger.info(f"  Cache MISS для чата {job.chat_id} — полная загрузка")
            # No cache — full fetch. _fetch_all_messages already caches messages
            # in batches and returns (count, AsyncGenerator) that reads from cache.
            # Forward its result directly — double-storing a tuple crashed with
            # "'int' object has no attribute 'model_dump'".
            result = await self._fetch_all_messages(job)
            if result is None:
                return None
            if self.message_cache and self.message_cache.enabled:
                await self.message_cache.evict_if_needed()
            return result

        cache_max_id = max(r[1] for r in cached_ranges)
        logger.info(f"  Cache HIT для чата {job.chat_id}: ranges={cached_ranges}, cache_max_id={cache_max_id}")

        # Прогресс-трекер
        tracker = self._create_tracker(job)
        if tracker:
            await tracker.start()

        # Получаем total для прогресс-репортинга
        total = await self.telegram_client.get_messages_count(job.chat_id)
        if job.limit and job.limit > 0 and (total is None or job.limit < total):
            total = job.limit
        if tracker:
            await tracker.set_total(total)

        # Seed прогресс-бар уже закэшированным количеством — так юзер сразу видит
        # реальный процент (напр. 40%), а не 0% → 40% прыжком. Также это сбрасывает
        # ETA-таймер, чтобы скорость считалась только по свежим сообщениям.
        #
        # Только для полного экспорта: при limit-based (total = job.limit) общее
        # число сообщений в кэше может превышать limit (кэш хранит весь чат), и
        # seed(cached_count) даст неверные 100% сразу. В limit-режиме начинаем с 0.
        cached_count = 0
        if not (job.limit and job.limit > 0):
            cached_count = await self.message_cache.count_messages(
                job.chat_id, 0, 2 ** 62
            )
            if tracker and cached_count:
                await tracker.seed(cached_count)

        fetched_count = cached_count
        fresh_count = 0
        latest_new_id = cache_max_id

        # Step 1: fetch messages NEWER than cache, store in batches of 1000
        batch: list[ExportedMessage] = []
        try:
            async for msg in self.telegram_client.get_chat_history(
                chat_id=job.chat_id,
                limit=job.limit,
                offset_id=0,
                min_id=cache_max_id,
                on_floodwait=tracker.on_floodwait if tracker else None,
            ):
                batch.append(msg)
                fetched_count += 1
                fresh_count += 1
                if msg.id > latest_new_id:
                    latest_new_id = msg.id
                if len(batch) >= self._CACHE_BATCH_SIZE:
                    if await self._flush_batch_and_check_cancel(job, batch):
                        return None
                if tracker:
                    await tracker.track(fetched_count)
        except Exception as e:
            logger.warning(f"Failed fetching new messages above cache: {e}")

        if batch:
            await self.message_cache.store_messages(job.chat_id, batch)
        if fresh_count:
            logger.info(f"  Fetched {fresh_count} new messages above cache max {cache_max_id}")

        # Step 2: fill ID gaps
        logger.info("  Step 2: Computing missing ID ranges...")
        full_min = min(r[0] for r in cached_ranges)
        full_max = max(cache_max_id, latest_new_id)
        logger.info(f"    Range to check: [{full_min}, {full_max}]")
        missing = await self.message_cache.get_missing_ranges(job.chat_id, full_min, full_max)
        logger.info(f"    Found {len(missing)} gaps: {missing}")

        for gap_low, gap_high in missing:
            batch = []
            gap_count = 0
            try:
                async for msg in self.telegram_client.get_chat_history(
                    chat_id=job.chat_id,
                    limit=0,
                    offset_id=gap_high + 1,
                    min_id=gap_low - 1,
                    on_floodwait=tracker.on_floodwait if tracker else None,
                ):
                    batch.append(msg)
                    fetched_count += 1
                    gap_count += 1
                    if len(batch) >= self._CACHE_BATCH_SIZE:
                        if await self._flush_batch_and_check_cancel(job, batch):
                            return None
                    if tracker:
                        await tracker.track(fetched_count)
            except Exception as e:
                logger.warning(f"Failed fetching gap [{gap_low}-{gap_high}]: {e}")

            if batch:
                await self.message_cache.store_messages(job.chat_id, batch)
            if gap_count:
                logger.info(f"  Filled gap [{gap_low}-{gap_high}]: {gap_count} messages")

        # Step 3: fetch messages OLDER than cache minimum
        logger.info("  Step 3: Fetching older messages...")
        if not job.limit or job.limit <= 0:
            cache_min_id = min(r[0] for r in cached_ranges)
            logger.info(f"    Cache min ID: {cache_min_id}")
            if cache_min_id > 1:
                batch = []
                older_count = 0
                try:
                    async for msg in self.telegram_client.get_chat_history(
                        chat_id=job.chat_id,
                        limit=0,
                        offset_id=cache_min_id,
                        min_id=0,
                        on_floodwait=tracker.on_floodwait if tracker else None,
                    ):
                        batch.append(msg)
                        fetched_count += 1
                        older_count += 1
                        if len(batch) >= self._CACHE_BATCH_SIZE:
                            if await self._flush_batch_and_check_cancel(job, batch):
                                return None
                        if tracker:
                            await tracker.track(fetched_count)
                except Exception as e:
                    logger.warning(f"Failed fetching older messages below cache min {cache_min_id}: {e}")

                if batch:
                    await self.message_cache.store_messages(job.chat_id, batch)
                if older_count:
                    logger.info(f"  Fetched {older_count} older messages below cache min {cache_min_id}")

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

    async def _fetch_all_messages(
        self, job: ExportRequest
    ) -> Optional[tuple[int, object]]:
        # Parse date filters
        from_date = None
        to_date = None
        if job.from_date:
            try:
                dt = datetime.fromisoformat(job.from_date)
                from_date = ensure_utc(dt)
            except ValueError:
                pass
        if job.to_date:
            try:
                dt = datetime.fromisoformat(job.to_date)
                to_date = ensure_utc(dt)
            except ValueError:
                pass

        # Получаем total ДО start(), чтобы передать в start(total=total).
        # Это позволяет сразу показать progress bar с реальным total вместо
        # двух API calls (start→spinner, set_total→0% bar).
        total = await self.telegram_client.get_messages_count(
            job.chat_id, from_date, to_date
        )
        if job.limit and job.limit > 0 and (total is None or job.limit < total):
            total = job.limit

        tracker = self._create_tracker(job)
        if tracker:
            await tracker.start(total=total)

        use_cache = bool(self.message_cache and self.message_cache.enabled)
        batch: list[ExportedMessage] = []
        # Fallback list only used when cache is disabled (edge case)
        nocache_messages: list[ExportedMessage] = []
        count = 0

        # NB: seed() здесь НЕ вызываем. _fetch_all_messages — это fallback, который
        # перекачивает весь диапазон с нуля (не только gaps), поэтому count += 1
        # сам дойдёт до total. Если seed'нуть уже закэшированным числом, то count
        # перевалит за total и прогресс-бар зафиксируется на 100% раньше времени.

        try:
            async for message in self.telegram_client.get_chat_history(
                chat_id=job.chat_id,
                limit=job.limit,
                offset_id=job.offset_id,
                min_id=0,
                from_date=from_date,
                to_date=to_date,
                on_floodwait=tracker.on_floodwait if tracker else None,
            ):
                count += 1
                if use_cache:
                    batch.append(message)
                    if len(batch) >= self._CACHE_BATCH_SIZE:
                        if await self._flush_batch_and_check_cancel(job, batch):
                            return None
                else:
                    nocache_messages.append(message)
                    if count % self._CACHE_BATCH_SIZE == 0:
                        if await self.is_cancelled(job.task_id):
                            await self.clear_active_export(job.user_id)
                            return None

                if tracker:
                    await tracker.track(count)
                elif count % 10_000 == 0:
                    logger.info(f"  Exported {count} messages...")

        except Exception as e:
            logger.error(f"❌ Export failed: {e}")
            raise

        # Flush remaining batch
        if batch and use_cache:
            await self.message_cache.store_messages(job.chat_id, batch)

        if tracker:
            await tracker.finalize(count)

        if use_cache:
            from_date_str = job.from_date[:10] if job.from_date else None
            to_date_str   = job.to_date[:10]   if job.to_date   else None
            if from_date_str and to_date_str:
                gen = self.message_cache.iter_messages_by_date(
                    job.chat_id, from_date_str, to_date_str
                )
            else:
                # No date filter: return all messages for this chat from cache
                gen = self.message_cache.iter_messages(job.chat_id, 0, 2 ** 62)
            return count, gen

        return count, nocache_messages

    async def _update_all_queue_positions(self, current_task_id: str) -> None:
        if not self.control_redis or not self.java_client or not self.queue_consumer:
            return
        try:
            pending_result = await self.queue_consumer.get_pending_jobs()
            pending = pending_result["jobs"]
            total = pending_result["total_count"]
            # Notify all jobs in parallel (one HTTP call per user, no sequential waiting)
            await asyncio.gather(
                self._notify_queue_position(current_task_id, 0, total),
                *[
                    self._notify_queue_position(job.task_id, i + 1, total)
                    for i, job in enumerate(pending)
                ],
                return_exceptions=True,
            )
        except Exception as e:
            logger.warning(f"Could not update queue positions: {e}")

    async def _notify_queue_position(
        self, task_id: str, position: int, total: int
    ) -> None:
        if not self.control_redis or not self.java_client:
            return

        raw_value = await self.control_redis.get(f"queue_msg:{task_id}")
        if not raw_value:
            return

        try:
            user_chat_id_str, msg_id_str = raw_value.split(":", 1)
            user_chat_id = int(user_chat_id_str)
            msg_id = int(msg_id_str)
        except (ValueError, AttributeError):
            logger.warning(f"Skipping malformed queue message metadata for task {task_id}")
            return

        await self.java_client.update_queue_position(user_chat_id, msg_id, position, total)

    async def run(self):
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
        logger.info("🛑 Shutting down worker...")

        self.running = False

        if self.telegram_client:
            await self.telegram_client.disconnect()

        if self.queue_consumer:
            await self.queue_consumer.disconnect()

        if self.java_client:
            await self.java_client.aclose()

        if self.message_cache:
            await self.message_cache.close()

        logger.info(
            f"📊 Final stats: {self.jobs_processed} processed, "
            f"{self.jobs_failed} failed"
        )
        logger.info("✅ Worker stopped")

    def handle_signal(self, signum, frame):
        logger.info(f"Received signal {signum}")
        self.running = False

async def main():
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
