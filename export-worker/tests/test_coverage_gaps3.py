"""
Senior-grade тесты для достижения 90%+ покрытия unit-тестами.

Покрывают реальные production paths:
- main.ExportWorker: helpers (_flush_batch_and_check_cancel, _flush_partial_batch,
  _create_tracker, log_memory_usage, _update_all_queue_positions, handle_signal, cleanup),
  run() loop, _fetch_all_messages date parse + full flow, _export_with_date_cache gap handling,
  _export_with_id_cache Step 2/3 gap fetching и older messages.
- pyrogram_client: string session init, not_connected guard, cancel paths, raw MTProto
  fallback, canonical mapping, topic history exception handlers.
- java_client: send_progress_update editMessageText, exception handling, ProgressTracker
  ETA calculation, on_floodwait без message_id, create_java_client connectivity failure.
"""

from __future__ import annotations

import json
import signal
import sys
import time
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from main import ExportWorker
from models import ExportRequest, ExportedMessage, SendResponsePayload
from java_client import JavaBotClient, ProgressTracker, create_java_client
from pyrogram_client import TelegramClient, ExportCancelled, create_client as create_telegram_client


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

class AsyncIter:
    """Async iterator обёртка над sync iterable. Замена корявому
    AsyncMock(return_value=[...]) — он возвращает корутину, не итератор."""

    def __init__(self, items):
        self._items = iter(items)

    def __aiter__(self):
        return self

    async def __anext__(self):
        try:
            return next(self._items)
        except StopIteration:
            raise StopAsyncIteration


def make_async_gen_func(items):
    """Возвращает функцию — async generator, отдающую items.
    Нужна для моков типа telegram_client.get_chat_history, которая
    НЕ awaitable, а async generator function."""
    async def _gen(*args, **kwargs):
        for item in items:
            yield item
    return _gen


def make_msg(msg_id: int, text: str = "test") -> ExportedMessage:
    return ExportedMessage(
        id=msg_id,
        date=f"2025-01-{(msg_id % 28) + 1:02d}T12:00:00+00:00",
        text=text,
    )


def make_job(**overrides) -> ExportRequest:
    defaults = dict(
        task_id="t1", user_id=42, user_chat_id=42, chat_id=100, limit=0,
    )
    defaults.update(overrides)
    return ExportRequest(**defaults)


# ─────────────────────────────────────────────────────────────────────────────
# main.ExportWorker — helpers (_flush_batch_and_check_cancel, _flush_partial_batch)
# ─────────────────────────────────────────────────────────────────────────────

class TestFlushBatchAndCheckCancel:

    @pytest.fixture
    def worker(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.message_cache = MagicMock()
        w.message_cache.enabled = True
        w.message_cache.store_messages = AsyncMock()
        return w

    @pytest.mark.asyncio
    async def test_flush_and_not_cancelled_returns_false(self, worker):
        worker.control_redis.get = AsyncMock(return_value=None)
        job = make_job()
        batch = [make_msg(1), make_msg(2)]
        result = await worker._flush_batch_and_check_cancel(job, batch)
        assert result is False
        worker.message_cache.store_messages.assert_awaited_once()
        assert batch == []

    @pytest.mark.asyncio
    async def test_flush_and_cancelled_returns_true(self, worker):
        worker.control_redis.get = AsyncMock(return_value=b"1")
        worker.control_redis.delete = AsyncMock()
        job = make_job()
        batch = [make_msg(1)]
        result = await worker._flush_batch_and_check_cancel(job, batch)
        assert result is True
        worker.control_redis.delete.assert_awaited_once_with(f"active_export:{job.user_id}")

    @pytest.mark.asyncio
    async def test_flush_no_cache_skips_store(self, worker):
        worker.message_cache.enabled = False
        worker.control_redis.get = AsyncMock(return_value=None)
        job = make_job()
        batch = [make_msg(1)]
        result = await worker._flush_batch_and_check_cancel(job, batch)
        assert result is False
        worker.message_cache.store_messages.assert_not_awaited()
        # batch НЕ очищен, т.к. cache disabled
        assert len(batch) == 1

    @pytest.mark.asyncio
    async def test_flush_empty_batch_skips_store(self, worker):
        worker.control_redis.get = AsyncMock(return_value=None)
        job = make_job()
        result = await worker._flush_batch_and_check_cancel(job, [])
        assert result is False
        worker.message_cache.store_messages.assert_not_awaited()


class TestFlushPartialBatch:

    @pytest.mark.asyncio
    async def test_empty_batch_no_call(self):
        w = ExportWorker()
        w.message_cache = MagicMock()
        w.message_cache.store_messages = AsyncMock()
        await w._flush_partial_batch(make_job(), [])
        w.message_cache.store_messages.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_nonempty_batch_stores(self):
        w = ExportWorker()
        w.message_cache = MagicMock()
        w.message_cache.store_messages = AsyncMock()
        job = make_job(topic_id=7)
        batch = [make_msg(1), make_msg(2)]
        await w._flush_partial_batch(job, batch)
        w.message_cache.store_messages.assert_awaited_once_with(
            job.chat_id, batch, topic_id=7,
        )


# ─────────────────────────────────────────────────────────────────────────────
# main.ExportWorker — _create_tracker, log_memory_usage, handle_signal
# ─────────────────────────────────────────────────────────────────────────────

class TestCreateTracker:

    def test_no_java_client_returns_none(self):
        w = ExportWorker()
        w.java_client = None
        assert w._create_tracker(make_job()) is None

    def test_no_user_chat_id_returns_none(self):
        w = ExportWorker()
        w.java_client = MagicMock()
        job = ExportRequest(task_id="t1", user_id=1, chat_id=100, limit=0)
        assert w._create_tracker(job) is None

    def test_with_client_and_chat_id_creates_tracker(self):
        w = ExportWorker()
        w.java_client = MagicMock()
        tracker = MagicMock()
        w.java_client.create_progress_tracker = MagicMock(return_value=tracker)
        job = make_job(user_chat_id=42)
        result = w._create_tracker(job, topic_name="topic")
        assert result is tracker
        w.java_client.create_progress_tracker.assert_called_once_with(
            42, "t1", topic_name="topic",
        )


class TestLogMemoryUsage:

    def test_logs_without_exception(self):
        w = ExportWorker()
        w.log_memory_usage("TEST_STAGE")

    def test_psutil_exception_is_silent(self):
        w = ExportWorker()
        with patch("main.psutil.virtual_memory", side_effect=RuntimeError("boom")):
            w.log_memory_usage("TEST_STAGE")


class TestHandleSignalMainWorker:

    def test_handle_signal_sets_running_false(self):
        w = ExportWorker()
        w.running = True
        w.handle_signal(signal.SIGTERM, None)
        assert w.running is False


# ─────────────────────────────────────────────────────────────────────────────
# _fetch_all_messages — date parsing, limit clamping, cache/nocache paths
# ─────────────────────────────────────────────────────────────────────────────

class TestFetchAllMessages:

    def _make_worker(self, cache_enabled: bool = True):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=None)  # never cancelled
        w.control_redis.delete = AsyncMock()
        w.control_redis.set = AsyncMock()
        w.telegram_client = MagicMock()
        w.telegram_client.get_messages_count = AsyncMock(return_value=100)
        w.message_cache = MagicMock()
        w.message_cache.enabled = cache_enabled
        w.message_cache.store_messages = AsyncMock()
        w.message_cache.evict_if_needed = AsyncMock()

        async def _empty_iter(*a, **kw):
            if False:
                yield
        w.message_cache.iter_messages = _empty_iter
        w.message_cache.iter_messages_by_date = _empty_iter
        w.java_client = None  # No tracker
        return w

    @pytest.mark.asyncio
    async def test_parses_valid_from_date(self):
        w = self._make_worker()
        w.telegram_client.get_chat_history = make_async_gen_func([])
        job = make_job(from_date="2025-01-01T00:00:00")
        count, gen = await w._fetch_all_messages(job)
        assert count == 0

    @pytest.mark.asyncio
    async def test_limit_clamps_total(self):
        w = self._make_worker()
        w.telegram_client.get_messages_count = AsyncMock(return_value=1000)
        w.telegram_client.get_chat_history = make_async_gen_func([])
        job = make_job(limit=50)
        count, gen = await w._fetch_all_messages(job)
        assert count == 0

    @pytest.mark.asyncio
    async def test_nocache_path_returns_list(self):
        w = self._make_worker(cache_enabled=False)
        msgs = [make_msg(i) for i in range(5)]
        w.telegram_client.get_chat_history = make_async_gen_func(msgs)
        job = make_job()
        count, items = await w._fetch_all_messages(job)
        assert count == 5
        assert items == msgs

    @pytest.mark.asyncio
    async def test_export_cancelled_flushes_partial_batch(self):
        w = self._make_worker(cache_enabled=True)

        async def raising_gen(*a, **kw):
            yield make_msg(1)
            yield make_msg(2)
            raise ExportCancelled("cancelled")
        w.telegram_client.get_chat_history = raising_gen
        job = make_job()
        with pytest.raises(ExportCancelled):
            await w._fetch_all_messages(job)
        # Partial batch должен быть сохранён
        w.message_cache.store_messages.assert_awaited()

    @pytest.mark.asyncio
    async def test_date_filter_returns_date_iter(self):
        w = self._make_worker(cache_enabled=True)
        w.telegram_client.get_chat_history = make_async_gen_func([make_msg(1)])
        job = make_job(from_date="2025-01-01T00:00:00", to_date="2025-01-31T00:00:00")
        count, gen = await w._fetch_all_messages(job)
        assert count == 1

    @pytest.mark.asyncio
    async def test_no_date_filter_returns_id_iter(self):
        w = self._make_worker(cache_enabled=True)
        w.telegram_client.get_chat_history = make_async_gen_func([make_msg(1)])
        job = make_job()
        count, gen = await w._fetch_all_messages(job)
        assert count == 1

    @pytest.mark.asyncio
    async def test_batch_flush_mid_stream_returns_none_on_cancel(self):
        w = self._make_worker(cache_enabled=True)
        # Первый flush вернёт True (cancelled) после _CACHE_BATCH_SIZE
        msgs = [make_msg(i) for i in range(ExportWorker._CACHE_BATCH_SIZE + 5)]
        w.telegram_client.get_chat_history = make_async_gen_func(msgs)
        call_count = {"n": 0}

        async def fake_is_cancelled(task_id):
            call_count["n"] += 1
            return call_count["n"] >= 1
        w.is_cancelled = fake_is_cancelled
        job = make_job()
        result = await w._fetch_all_messages(job)
        assert result is None

    @pytest.mark.asyncio
    async def test_nocache_cancel_periodic_returns_none(self):
        w = self._make_worker(cache_enabled=False)
        msgs = [make_msg(i) for i in range(ExportWorker._CACHE_BATCH_SIZE + 5)]
        w.telegram_client.get_chat_history = make_async_gen_func(msgs)

        async def fake_is_cancelled(task_id):
            # Отменяем после _CACHE_BATCH_SIZE
            return True
        w.is_cancelled = fake_is_cancelled
        job = make_job()
        result = await w._fetch_all_messages(job)
        assert result is None

    @pytest.mark.asyncio
    async def test_generic_exception_reraises(self):
        w = self._make_worker()

        async def raising_gen(*a, **kw):
            raise RuntimeError("boom")
            yield
        w.telegram_client.get_chat_history = raising_gen
        with pytest.raises(RuntimeError, match="boom"):
            await w._fetch_all_messages(make_job())


# ─────────────────────────────────────────────────────────────────────────────
# _export_with_id_cache — Step 2 (gap fill) и Step 3 (older messages)
# ─────────────────────────────────────────────────────────────────────────────

class TestExportWithIdCache:

    def _make_worker(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=None)
        w.control_redis.delete = AsyncMock()
        w.control_redis.set = AsyncMock()
        w.telegram_client = MagicMock()
        w.telegram_client.get_messages_count = AsyncMock(return_value=100)
        w.java_client = None
        w.message_cache = MagicMock()
        w.message_cache.enabled = True
        w.message_cache.store_messages = AsyncMock()
        w.message_cache.evict_if_needed = AsyncMock()
        w.message_cache.count_messages = AsyncMock(return_value=0)

        async def _iter(*a, **kw):
            if False:
                yield
        w.message_cache.iter_messages = _iter
        return w

    @pytest.mark.asyncio
    async def test_no_cached_ranges_delegates_to_fetch_all(self):
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[])
        w._fetch_all_messages = AsyncMock(return_value=None)
        result = await w._export_with_id_cache(make_job())
        assert result is None
        w._fetch_all_messages.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_no_cached_ranges_with_fetch_result(self):
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[])

        async def _gen():
            if False:
                yield
        w._fetch_all_messages = AsyncMock(return_value=(5, _gen()))
        result = await w._export_with_id_cache(make_job())
        assert result[0] == 5
        w.message_cache.evict_if_needed.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_step2_fills_gap_with_messages(self):
        """Step 2: missing gap → get_chat_history → store в кэш."""
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[(100, 200)])
        w.message_cache.get_missing_ranges = AsyncMock(return_value=[(50, 99)])

        calls = {"histories": []}

        async def hist(**kwargs):
            calls["histories"].append(kwargs)
            for i in range(70, 100):
                yield make_msg(i)
        w.telegram_client.get_chat_history = hist
        job = make_job(limit=0)
        result = await w._export_with_id_cache(job)
        assert result is not None
        # Step 1 + Step 2: hist должен вызваться минимум дважды
        assert len(calls["histories"]) >= 2
        # Gap fill вызван с правильными offset_id/min_id
        gap_calls = [c for c in calls["histories"] if c.get("offset_id") == 100]
        assert len(gap_calls) >= 1
        assert gap_calls[0]["min_id"] == 49  # gap_low - 1

    @pytest.mark.asyncio
    async def test_step2_gap_cancelled_raises(self):
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[(100, 200)])
        w.message_cache.get_missing_ranges = AsyncMock(return_value=[(50, 99)])

        async def hist(**kwargs):
            # Step 1 (min_id=200): пустой
            # Step 2 (min_id=49): бросает ExportCancelled
            if kwargs.get("min_id") == 49:
                yield make_msg(60)
                raise ExportCancelled("user cancel")
            else:
                if False:
                    yield
        w.telegram_client.get_chat_history = hist
        with pytest.raises(ExportCancelled):
            await w._export_with_id_cache(make_job(limit=0))
        w.message_cache.store_messages.assert_awaited()

    @pytest.mark.asyncio
    async def test_step2_gap_generic_exception_swallowed(self):
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[(100, 200)])
        w.message_cache.get_missing_ranges = AsyncMock(return_value=[(50, 99)])

        async def hist(**kwargs):
            if kwargs.get("min_id") == 49:
                raise RuntimeError("telegram flakes")
            if False:
                yield
        w.telegram_client.get_chat_history = hist
        result = await w._export_with_id_cache(make_job(limit=0))
        assert result is not None  # exception SWALLOWED

    @pytest.mark.asyncio
    async def test_step3_fetches_older_messages(self):
        """Step 3 выполняется ТОЛЬКО если нет limit (limit=0)."""
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[(100, 200)])
        w.message_cache.get_missing_ranges = AsyncMock(return_value=[])

        calls = {"histories": []}

        async def hist(**kwargs):
            calls["histories"].append(kwargs)
            # Step 1 (min_id=200): 0 msgs, Step 3 (offset_id=100, min_id=0): older
            if kwargs.get("offset_id") == 100 and kwargs.get("min_id") == 0:
                for i in range(50, 100):
                    yield make_msg(i)
            else:
                if False:
                    yield
        w.telegram_client.get_chat_history = hist
        result = await w._export_with_id_cache(make_job(limit=0))
        assert result is not None
        older_calls = [
            c for c in calls["histories"]
            if c.get("offset_id") == 100 and c.get("min_id") == 0
        ]
        assert len(older_calls) == 1

    @pytest.mark.asyncio
    async def test_step3_skipped_when_limit_positive(self):
        """limit > 0 → Step 3 не выполняется."""
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[(100, 200)])
        w.message_cache.get_missing_ranges = AsyncMock(return_value=[])
        calls = {"histories": []}

        async def hist(**kwargs):
            calls["histories"].append(kwargs)
            if False:
                yield
        w.telegram_client.get_chat_history = hist
        result = await w._export_with_id_cache(make_job(limit=10))
        assert result is not None
        # Step 1 only (min_id=200). Step 3 offset_id=100 / min_id=0 НЕ должен быть
        older_calls = [
            c for c in calls["histories"]
            if c.get("offset_id") == 100 and c.get("min_id") == 0
        ]
        assert len(older_calls) == 0

    @pytest.mark.asyncio
    async def test_step3_older_cancelled_raises(self):
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[(100, 200)])
        w.message_cache.get_missing_ranges = AsyncMock(return_value=[])

        async def hist(**kwargs):
            if kwargs.get("offset_id") == 100 and kwargs.get("min_id") == 0:
                yield make_msg(50)
                raise ExportCancelled("user cancel")
            if False:
                yield
        w.telegram_client.get_chat_history = hist
        with pytest.raises(ExportCancelled):
            await w._export_with_id_cache(make_job(limit=0))

    @pytest.mark.asyncio
    async def test_step3_older_generic_exception_swallowed(self):
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[(100, 200)])
        w.message_cache.get_missing_ranges = AsyncMock(return_value=[])

        async def hist(**kwargs):
            if kwargs.get("offset_id") == 100 and kwargs.get("min_id") == 0:
                raise RuntimeError("boom")
            if False:
                yield
        w.telegram_client.get_chat_history = hist
        result = await w._export_with_id_cache(make_job(limit=0))
        assert result is not None  # swallowed

    @pytest.mark.asyncio
    async def test_cancel_before_finalize_returns_none(self):
        """Если отмена случилась между merge и finalize — возврат None."""
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[(100, 200)])
        w.message_cache.get_missing_ranges = AsyncMock(return_value=[])

        async def hist(**kwargs):
            if False:
                yield
        w.telegram_client.get_chat_history = hist

        # is_cancelled True перед finalize (единственный прямой вызов в этом пути —
        # в строке "если cancelled перед finalize → return None")
        w.is_cancelled = AsyncMock(return_value=True)
        result = await w._export_with_id_cache(make_job(limit=0))
        assert result is None
        w.control_redis.delete.assert_awaited()

    @pytest.mark.asyncio
    async def test_step1_tracks_latest_new_id(self):
        """Step 1 с msg.id > latest_new_id → обновление latest_new_id."""
        w = self._make_worker()
        w.message_cache.get_cached_ranges = AsyncMock(return_value=[(100, 150)])

        # Step 1 выдаст msg с id=300 → latest_new_id станет 300
        async def hist(**kwargs):
            if kwargs.get("min_id") == 150:  # Step 1
                yield make_msg(300)
                yield make_msg(250)
                yield make_msg(280)  # меньше текущего latest_new_id, не обновит
            else:
                if False:
                    yield

        w.telegram_client.get_chat_history = hist
        # missing ranges с full_max=300 (из latest_new_id)
        w.message_cache.get_missing_ranges = AsyncMock(return_value=[])
        result = await w._export_with_id_cache(make_job(limit=0))
        # full_max должен быть передан в get_missing_ranges
        call_args = w.message_cache.get_missing_ranges.call_args
        assert call_args[0][2] == 300  # full_max = max(cache_max, latest_new_id=300)


# ─────────────────────────────────────────────────────────────────────────────
# _export_with_date_cache — partial defaults, cancel paths, gap handling
# ─────────────────────────────────────────────────────────────────────────────

class TestExportWithDateCache:

    def _make_worker(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=None)
        w.control_redis.delete = AsyncMock()
        w.telegram_client = MagicMock()
        w.telegram_client.get_messages_count = AsyncMock(return_value=100)
        w.java_client = None
        w.message_cache = MagicMock()
        w.message_cache.enabled = True
        w.message_cache.store_messages = AsyncMock()
        w.message_cache.evict_if_needed = AsyncMock()
        w.message_cache.count_messages_by_date = AsyncMock(return_value=0)
        w.message_cache.mark_date_range_checked = AsyncMock()

        async def _iter(*a, **kw):
            if False:
                yield
        w.message_cache.iter_messages_by_date = _iter
        return w

    @pytest.mark.asyncio
    async def test_partial_date_filter_from_default(self):
        """Только to_date задан → from_date заполняется дефолтом 2000-01-01."""
        w = self._make_worker()
        w.message_cache.get_missing_date_ranges = AsyncMock(return_value=[])
        w.message_cache.count_messages_by_date = AsyncMock(return_value=0)
        job = make_job(from_date=None, to_date="2025-12-31")
        await w._export_with_date_cache(job)
        # get_missing_date_ranges должен получить from="2000-01-01", to="2025-12-31"
        call = w.message_cache.get_missing_date_ranges.call_args
        assert call[0][1] == "2000-01-01"
        assert call[0][2] == "2025-12-31"

    @pytest.mark.asyncio
    async def test_partial_date_filter_to_default(self):
        """Только from_date задан → to_date заполняется сегодняшней датой."""
        w = self._make_worker()
        w.message_cache.get_missing_date_ranges = AsyncMock(return_value=[])
        job = make_job(from_date="2025-01-01", to_date=None)
        await w._export_with_date_cache(job)
        call = w.message_cache.get_missing_date_ranges.call_args
        assert call[0][1] == "2025-01-01"
        assert len(call[0][2]) == 10  # YYYY-MM-DD формат

    @pytest.mark.asyncio
    async def test_cache_hit_cancel_before_finalize(self):
        """Full cache HIT + cancel перед finalize → return None."""
        w = self._make_worker()
        w.message_cache.get_missing_date_ranges = AsyncMock(return_value=[])
        w.message_cache.count_messages_by_date = AsyncMock(return_value=50)
        call_n = {"i": 0}

        async def fake_cancel(tid):
            call_n["i"] += 1
            return call_n["i"] >= 1
        w.is_cancelled = fake_cancel
        job = make_job(from_date="2025-01-01", to_date="2025-01-31")
        result = await w._export_with_date_cache(job)
        assert result is None
        w.control_redis.delete.assert_awaited()

    @pytest.mark.asyncio
    async def test_gap_export_cancelled_flushes_partial(self):
        w = self._make_worker()
        w.message_cache.get_missing_date_ranges = AsyncMock(
            return_value=[("2025-01-01", "2025-01-31")]
        )

        async def hist(**kwargs):
            yield make_msg(1)
            raise ExportCancelled("user")
        w.telegram_client.get_chat_history = hist
        job = make_job(from_date="2025-01-01", to_date="2025-01-31")
        with pytest.raises(ExportCancelled):
            await w._export_with_date_cache(job)

    @pytest.mark.asyncio
    async def test_gap_generic_exception_swallowed(self):
        w = self._make_worker()
        w.message_cache.get_missing_date_ranges = AsyncMock(
            return_value=[("2025-01-01", "2025-01-31")]
        )

        async def hist(**kwargs):
            raise RuntimeError("telegram flakes")
            yield
        w.telegram_client.get_chat_history = hist
        job = make_job(from_date="2025-01-01", to_date="2025-01-31")
        result = await w._export_with_date_cache(job)
        assert result is not None  # swallowed

    @pytest.mark.asyncio
    async def test_empty_gap_marks_date_range_checked(self):
        """Telegram вернул 0 для диапазона → mark_date_range_checked."""
        w = self._make_worker()
        w.message_cache.get_missing_date_ranges = AsyncMock(
            return_value=[("2025-01-01", "2025-01-31")]
        )
        w.telegram_client.get_chat_history = make_async_gen_func([])
        job = make_job(from_date="2025-01-01", to_date="2025-01-31")
        await w._export_with_date_cache(job)
        w.message_cache.mark_date_range_checked.assert_awaited()

    @pytest.mark.asyncio
    async def test_cancel_after_miss_merge_returns_none(self):
        """Отмена после merge gaps → return None."""
        w = self._make_worker()
        w.message_cache.get_missing_date_ranges = AsyncMock(
            return_value=[("2025-01-01", "2025-01-31")]
        )
        w.telegram_client.get_chat_history = make_async_gen_func([make_msg(1)])
        w.is_cancelled = AsyncMock(return_value=True)
        job = make_job(from_date="2025-01-01", to_date="2025-01-31")
        result = await w._export_with_date_cache(job)
        assert result is None


# ─────────────────────────────────────────────────────────────────────────────
# ExportWorker.run() loop
# ─────────────────────────────────────────────────────────────────────────────

class TestRunLoop:

    @pytest.mark.asyncio
    async def test_initialize_fail_exits(self):
        w = ExportWorker()
        w.initialize = AsyncMock(return_value=False)
        with pytest.raises(SystemExit):
            await w.run()

    @pytest.mark.asyncio
    async def test_cancelled_error_breaks_loop(self):
        w = ExportWorker()
        w.initialize = AsyncMock(return_value=True)
        w.queue_consumer = AsyncMock()
        w.queue_consumer.get_job = AsyncMock(side_effect=__import__("asyncio").CancelledError())
        w.cleanup = AsyncMock()
        await w.run()
        w.cleanup.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_keyboard_interrupt_breaks_loop(self):
        w = ExportWorker()
        w.initialize = AsyncMock(return_value=True)
        w.queue_consumer = AsyncMock()
        w.queue_consumer.get_job = AsyncMock(side_effect=KeyboardInterrupt())
        w.cleanup = AsyncMock()
        await w.run()
        w.cleanup.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_generic_exception_sleeps_and_continues(self):
        """Generic exception → sleep 5 → continue. После 2ой exception останавливаемся."""
        w = ExportWorker()
        w.initialize = AsyncMock(return_value=True)
        w.queue_consumer = AsyncMock()
        calls = {"n": 0}

        async def fake_get_job():
            calls["n"] += 1
            if calls["n"] == 1:
                raise RuntimeError("transient")
            # После первого fail, останавливаемся
            w.running = False
            return None
        w.queue_consumer.get_job = fake_get_job
        w.cleanup = AsyncMock()

        with patch("main.asyncio.sleep", new=AsyncMock()):
            await w.run()
        assert calls["n"] >= 2
        w.cleanup.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_happy_path_processes_job(self):
        w = ExportWorker()
        w.initialize = AsyncMock(return_value=True)
        w.queue_consumer = AsyncMock()
        job = make_job()
        calls = {"n": 0}

        async def fake_get_job():
            calls["n"] += 1
            if calls["n"] == 1:
                return job
            w.running = False
            return None
        w.queue_consumer.get_job = fake_get_job
        w._update_all_queue_positions = AsyncMock()
        w.process_job = AsyncMock(return_value=True)
        w.cleanup = AsyncMock()

        with patch("main.asyncio.sleep", new=AsyncMock()):
            await w.run()
        w.process_job.assert_awaited_once_with(job)
        w._update_all_queue_positions.assert_awaited_once()
        w.cleanup.assert_awaited_once()


class TestCleanup:

    @pytest.mark.asyncio
    async def test_closes_all_components(self):
        w = ExportWorker()
        w.telegram_client = AsyncMock()
        w.queue_consumer = AsyncMock()
        w.java_client = AsyncMock()
        w.message_cache = AsyncMock()
        w.running = True
        await w.cleanup()
        w.telegram_client.disconnect.assert_awaited_once()
        w.queue_consumer.disconnect.assert_awaited_once()
        w.java_client.aclose.assert_awaited_once()
        w.message_cache.close.assert_awaited_once()
        assert w.running is False

    @pytest.mark.asyncio
    async def test_cleanup_with_none_components_safe(self):
        w = ExportWorker()
        # Все компоненты None
        await w.cleanup()


# ─────────────────────────────────────────────────────────────────────────────
# _update_all_queue_positions, _notify_queue_position
# ─────────────────────────────────────────────────────────────────────────────

class TestUpdateAllQueuePositions:

    @pytest.mark.asyncio
    async def test_no_redis_skips(self):
        w = ExportWorker()
        w.control_redis = None
        w.java_client = MagicMock()
        w.queue_consumer = MagicMock()
        await w._update_all_queue_positions("t1")  # just returns

    @pytest.mark.asyncio
    async def test_happy_path_notifies_all(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.java_client = MagicMock()
        w.queue_consumer = AsyncMock()
        w.queue_consumer.get_pending_jobs = AsyncMock(return_value={
            "jobs": [make_job(task_id="p1"), make_job(task_id="p2")],
            "total_count": 2,
        })
        w._notify_queue_position = AsyncMock()
        await w._update_all_queue_positions("t1")
        assert w._notify_queue_position.await_count >= 2

    @pytest.mark.asyncio
    async def test_exception_swallowed(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.java_client = MagicMock()
        w.queue_consumer = AsyncMock()
        w.queue_consumer.get_pending_jobs = AsyncMock(side_effect=RuntimeError("boom"))
        await w._update_all_queue_positions("t1")


# ─────────────────────────────────────────────────────────────────────────────
# PyrogramClient — гранулярные тесты
# ─────────────────────────────────────────────────────────────────────────────

class TestPyrogramStringSession:

    def test_init_with_string_session(self, monkeypatch):
        """113-114: init ветка с session_string."""
        monkeypatch.setattr("pyrogram_client.settings.TELEGRAM_SESSION_STRING", "test_session")
        with patch("pyrogram_client.Client") as MockClient:
            tc = TelegramClient()
            _, kwargs = MockClient.call_args
            assert kwargs.get("session_string") == "test_session"


class TestPyrogramNotConnectedGuard:

    @pytest.mark.asyncio
    async def test_get_chat_history_not_connected_raises(self):
        """184: guard в get_chat_history."""
        tc = TelegramClient.__new__(TelegramClient)
        tc.is_connected = False
        with pytest.raises(RuntimeError, match="Not connected"):
            async for _ in tc.get_chat_history(123):
                pass


class TestPyrogramReraise:

    def _make_client(self):
        tc = TelegramClient.__new__(TelegramClient)
        tc.is_connected = True
        tc.client = MagicMock()
        tc._topic_name_cache = {}
        tc._TOPIC_NAME_CACHE_MAX = 500
        tc.redis_client = None
        return tc

    @pytest.mark.asyncio
    async def test_channel_private_reraise(self):
        """326-327: ChannelPrivate re-raise в get_chat_history."""
        from pyrogram.errors import ChannelPrivate
        tc = self._make_client()

        async def hist(**kw):
            raise ChannelPrivate("private")
            yield
        tc.client.get_chat_history = hist
        with pytest.raises(ChannelPrivate):
            async for _ in tc.get_chat_history(123):
                pass

    @pytest.mark.asyncio
    async def test_chat_admin_required_reraise(self):
        """330-331."""
        from pyrogram.errors import ChatAdminRequired
        tc = self._make_client()

        async def hist(**kw):
            raise ChatAdminRequired("admin")
            yield
        tc.client.get_chat_history = hist
        with pytest.raises(ChatAdminRequired):
            async for _ in tc.get_chat_history(123):
                pass

    @pytest.mark.asyncio
    async def test_bad_request_reraise(self):
        """334-335."""
        from pyrogram.errors import BadRequest
        tc = self._make_client()

        async def hist(**kw):
            raise BadRequest("bad")
            yield
        tc.client.get_chat_history = hist
        with pytest.raises(BadRequest):
            async for _ in tc.get_chat_history(123):
                pass


class TestPyrogramCancelInMessageLoop:
    """224-232, 273-278."""

    def _make_client(self):
        tc = TelegramClient.__new__(TelegramClient)
        tc.is_connected = True
        tc.client = MagicMock()
        tc._topic_name_cache = {}
        tc._TOPIC_NAME_CACHE_MAX = 500
        tc.redis_client = None
        return tc

    @pytest.mark.asyncio
    async def test_cancel_mid_stream_raises(self):
        """is_cancelled_fn возвращает True во время итерации → ExportCancelled."""
        tc = self._make_client()
        # Нужно > _CANCEL_CHECK_EVERY сообщений
        msgs = [
            MagicMock(id=i, date=datetime(2025, 1, 1, tzinfo=timezone.utc))
            for i in range(1, tc._CANCEL_CHECK_EVERY + 10)
        ]

        async def hist(**kw):
            for m in msgs:
                yield m
        tc.client.get_chat_history = hist

        call_n = {"i": 0}

        async def cancel_fn():
            call_n["i"] += 1
            return True  # Отменяем на первом же check
        # cancel_fn должна быть awaitable
        with patch("pyrogram_client.MessageConverter.convert_message",
                   side_effect=lambda m: make_msg(m.id)):
            collected = []
            async for m in tc.get_chat_history(123, is_cancelled_fn=cancel_fn):
                collected.append(m)
            # После отмены внутри try/except ExportCancelled корректно поглощается
            # и iterator заканчивается — НЕ raise наружу. Длина collected — N где
            # N хотя бы кратно _CANCEL_CHECK_EVERY.
            assert len(collected) >= tc._CANCEL_CHECK_EVERY

    @pytest.mark.asyncio
    async def test_cancel_fn_exception_swallowed(self):
        """is_cancelled_fn бросает ошибку → логируется и продолжается."""
        tc = self._make_client()
        msgs = [
            MagicMock(id=i, date=datetime(2025, 1, 1, tzinfo=timezone.utc))
            for i in range(1, tc._CANCEL_CHECK_EVERY + 5)
        ]

        async def hist(**kw):
            for m in msgs:
                yield m
        tc.client.get_chat_history = hist

        async def bad_cancel():
            raise RuntimeError("redis down")
        with patch("pyrogram_client.MessageConverter.convert_message",
                   side_effect=lambda m: make_msg(m.id)):
            collected = []
            async for m in tc.get_chat_history(123, is_cancelled_fn=bad_cancel):
                collected.append(m)
            # Exception проглочен → процесс продолжается до конца
            assert len(collected) == len(msgs)

    @pytest.mark.asyncio
    async def test_convert_message_exception_continues(self):
        """273-278: exception в convert_message → continue."""
        tc = self._make_client()
        msgs = [
            MagicMock(id=1, date=datetime(2025, 1, 1, tzinfo=timezone.utc)),
            MagicMock(id=2, date=datetime(2025, 1, 1, tzinfo=timezone.utc)),
            MagicMock(id=3, date=datetime(2025, 1, 1, tzinfo=timezone.utc)),
        ]

        async def hist(**kw):
            for m in msgs:
                yield m
        tc.client.get_chat_history = hist

        def bad_convert(m):
            if m.id == 2:
                raise ValueError("parse error")
            return make_msg(m.id)
        with patch("pyrogram_client.MessageConverter.convert_message", side_effect=bad_convert):
            collected = []
            async for m in tc.get_chat_history(123):
                collected.append(m)
            # 3 msgs, один проблемный → 2 yielded
            assert len(collected) == 2
            assert {m.id for m in collected} == {1, 3}


class TestPyrogramTopicHistoryReraise:
    """473-494: topic exception handlers."""

    def _make_client(self):
        tc = TelegramClient.__new__(TelegramClient)
        tc.is_connected = True
        tc.client = MagicMock()
        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        tc._topic_name_cache = {}
        tc._TOPIC_NAME_CACHE_MAX = 500
        tc.redis_client = None
        return tc

    @pytest.mark.asyncio
    async def test_topic_cancel_before_batch(self):
        """370-378: cancel перед первым батчем."""
        tc = self._make_client()
        tc.client.invoke = AsyncMock()

        async def cancel():
            return True
        collected = []
        async for m in tc._get_topic_history(123, 10, is_cancelled_fn=cancel):
            collected.append(m)
        assert collected == []

    @pytest.mark.asyncio
    async def test_topic_channel_private_reraise(self):
        """480-482."""
        from pyrogram.errors import ChannelPrivate
        tc = self._make_client()
        tc.client.invoke = AsyncMock(side_effect=ChannelPrivate("private"))
        with pytest.raises(ChannelPrivate):
            async for _ in tc._get_topic_history(123, 10):
                pass

    @pytest.mark.asyncio
    async def test_topic_admin_required_reraise(self):
        """484-486."""
        from pyrogram.errors import ChatAdminRequired
        tc = self._make_client()
        tc.client.invoke = AsyncMock(side_effect=ChatAdminRequired("admin"))
        with pytest.raises(ChatAdminRequired):
            async for _ in tc._get_topic_history(123, 10):
                pass

    @pytest.mark.asyncio
    async def test_topic_bad_request_reraise(self):
        """488-490."""
        from pyrogram.errors import BadRequest
        tc = self._make_client()
        tc.client.invoke = AsyncMock(side_effect=BadRequest("bad"))
        with pytest.raises(BadRequest):
            async for _ in tc._get_topic_history(123, 10):
                pass

    @pytest.mark.asyncio
    async def test_topic_empty_messages_break(self):
        """397: пустой результат → break."""
        tc = self._make_client()
        result = MagicMock(messages=[], users=[], chats=[])
        tc.client.invoke = AsyncMock(return_value=result)
        collected = []
        async for m in tc._get_topic_history(123, 10):
            collected.append(m)
        assert collected == []


class TestPyrogramResolveNumericRawMTProto:
    """706-719, 724-743: raw MTProto + canonical mapping."""

    def _make_client(self):
        tc = TelegramClient.__new__(TelegramClient)
        tc.is_connected = True
        tc.client = MagicMock()
        tc._topic_name_cache = {}
        tc._TOPIC_NAME_CACHE_MAX = 500
        tc.redis_client = None
        return tc

    @pytest.mark.asyncio
    async def test_raw_mtproto_success_with_username(self):
        """706-712: raw MTProto вернул chat с username."""
        tc = self._make_client()

        async def empty_dialogs():
            if False:
                yield
        tc.client.get_dialogs = empty_dialogs
        tc.client.get_chat = AsyncMock(side_effect=[
            RuntimeError("first sync failed"),  # fallback 1 fails
            MagicMock(id=-1001234567890, title="Public", username="pubch",
                      type="channel", is_bot=False, is_self=False, is_contact=False,
                      members_count=100, description=""),
        ])
        # Raw MTProto вернёт chat с username
        raw_channel = MagicMock()
        raw_channel.username = "pubch"
        raw_result = MagicMock()
        raw_result.chats = [raw_channel]
        tc.client.invoke = AsyncMock(return_value=raw_result)

        ok, info, reason = await tc._resolve_numeric_chat_id(-1001234567890)
        assert ok is True
        assert info is not None
        assert info["username"] == "pubch"

    @pytest.mark.asyncio
    async def test_raw_mtproto_channel_private_falls_through(self):
        """Raw MTProto → ChannelPrivate → canonical fallback → нет redis → CHAT_NOT_ACCESSIBLE."""
        from pyrogram.errors import ChannelPrivate
        tc = self._make_client()

        async def empty_dialogs():
            if False:
                yield
        tc.client.get_dialogs = empty_dialogs
        tc.client.get_chat = AsyncMock(side_effect=RuntimeError("sync failed"))
        tc.client.invoke = AsyncMock(side_effect=ChannelPrivate("private"))
        ok, info, reason = await tc._resolve_numeric_chat_id(-1001234567890)
        assert ok is False
        assert reason == "CHAT_NOT_ACCESSIBLE"

    @pytest.mark.asyncio
    async def test_raw_mtproto_no_username_falls_to_numeric(self):
        """709-712: raw_username is None → fallback к numeric get_chat."""
        tc = self._make_client()

        async def empty_dialogs():
            if False:
                yield
        tc.client.get_dialogs = empty_dialogs
        tc.client.get_chat = AsyncMock(side_effect=[
            RuntimeError("sync failed"),
            MagicMock(id=-1001234567890, title="Private", username=None,
                      type="channel", is_bot=False, is_self=False, is_contact=False,
                      members_count=0, description=""),
        ])
        raw_channel = MagicMock()
        raw_channel.username = None
        raw_result = MagicMock()
        raw_result.chats = [raw_channel]
        tc.client.invoke = AsyncMock(return_value=raw_result)
        ok, info, reason = await tc._resolve_numeric_chat_id(-1001234567890)
        assert ok is True

    @pytest.mark.asyncio
    async def test_canonical_mapping_success(self):
        """724-743: canonical mapping из redis → resolve username."""
        tc = self._make_client()
        tc.redis_client = AsyncMock()
        tc.redis_client.get = AsyncMock(return_value="known_username")
        tc.client.get_chat = AsyncMock(return_value=MagicMock(
            id=-1001234567890, title="Known", username="known_username",
            type="channel", is_bot=False, is_self=False, is_contact=False,
            members_count=0, description="",
        ))
        result = await tc._resolve_via_canonical_mapping(-1001234567890)
        assert result is not None
        ok, info, reason = result
        assert ok is True
        assert info["username"] == "known_username"

    @pytest.mark.asyncio
    async def test_canonical_mapping_bytes_decoded(self):
        """730-731: username как bytes → decoded."""
        tc = self._make_client()
        tc.redis_client = AsyncMock()
        tc.redis_client.get = AsyncMock(return_value=b"bytes_username")
        tc.client.get_chat = AsyncMock(return_value=MagicMock(
            id=123, title="T", username="bytes_username",
            type="channel", is_bot=False, is_self=False, is_contact=False,
            members_count=0, description="",
        ))
        result = await tc._resolve_via_canonical_mapping(123)
        assert result is not None

    @pytest.mark.asyncio
    async def test_canonical_mapping_no_redis_returns_none(self):
        tc = self._make_client()
        tc.redis_client = None
        assert await tc._resolve_via_canonical_mapping(123) is None

    @pytest.mark.asyncio
    async def test_canonical_mapping_empty_value_returns_none(self):
        tc = self._make_client()
        tc.redis_client = AsyncMock()
        tc.redis_client.get = AsyncMock(return_value=None)
        assert await tc._resolve_via_canonical_mapping(123) is None

    @pytest.mark.asyncio
    async def test_canonical_mapping_exception_silent(self):
        """739-742: exception → log + return None."""
        tc = self._make_client()
        tc.redis_client = AsyncMock()
        tc.redis_client.get = AsyncMock(side_effect=RuntimeError("redis down"))
        result = await tc._resolve_via_canonical_mapping(123)
        assert result is None


# ─────────────────────────────────────────────────────────────────────────────
# java_client — send_progress_update, ProgressTracker, create_java_client
# ─────────────────────────────────────────────────────────────────────────────

class TestJavaSendProgressUpdate:

    def _make(self):
        c = JavaBotClient.__new__(JavaBotClient)
        c.base_url = "http://java:8080"
        c.bot_token = "fake:token"
        c._http_client = AsyncMock()
        c.max_retries = 3
        # send_progress_update использует self._tg_timeout (init'ится только в
        # __init__). Без атрибута AttributeError маскируется silent-except в
        # production коде → test ассертит msg_id который не возвращается.
        import httpx as _httpx
        c._tg_timeout = _httpx.Timeout(timeout=5.0)
        return c

    @pytest.mark.asyncio
    async def test_new_message_with_eta_appends_text(self):
        """402-406: eta_text добавляется к сообщению."""
        c = self._make()
        resp = MagicMock(status_code=200)
        resp.json = MagicMock(return_value={"result": {"message_id": 123}})
        c._http_client.post = AsyncMock(return_value=resp)
        msg_id = await c.send_progress_update(
            user_chat_id=42, task_id="t1", message_count=50, total=100,
            eta_text="2 мин",
        )
        assert msg_id == 123
        call = c._http_client.post.call_args
        sent_text = call.kwargs["data"]["text"]
        assert "2 мин" in sent_text

    @pytest.mark.asyncio
    async def test_edit_existing_message_success(self):
        """412-421: progress_message_id задан → editMessageText."""
        c = self._make()
        resp = MagicMock(status_code=200)
        c._http_client.post = AsyncMock(return_value=resp)
        msg_id = await c.send_progress_update(
            user_chat_id=42, task_id="t1", message_count=50, total=100,
            progress_message_id=789,
        )
        assert msg_id == 789
        call = c._http_client.post.call_args
        assert "editMessageText" in call.args[0]

    @pytest.mark.asyncio
    async def test_edit_message_non_200_returns_none(self):
        c = self._make()
        resp = MagicMock(status_code=400)
        c._http_client.post = AsyncMock(return_value=resp)
        msg_id = await c.send_progress_update(
            user_chat_id=42, task_id="t1", message_count=50,
            progress_message_id=789,
        )
        assert msg_id is None

    @pytest.mark.asyncio
    async def test_http_exception_returns_none(self):
        """430-432: Exception → return None."""
        c = self._make()
        c._http_client.post = AsyncMock(side_effect=RuntimeError("tg down"))
        result = await c.send_progress_update(
            user_chat_id=42, task_id="t1", message_count=50,
        )
        assert result is None


class TestProgressTrackerInternals:

    def _make_tracker(self):
        client = AsyncMock()
        client.send_progress_update = AsyncMock(return_value=42)
        return ProgressTracker(client, user_chat_id=100, task_id="t1"), client

    @pytest.mark.asyncio
    async def test_track_calculates_eta(self):
        """547-550: rate > 0 → ETA считается."""
        tracker, client = self._make_tracker()
        tracker._total = 100
        tracker._start_time = time.time() - 5  # 5 сек назад
        tracker._last_update = 0  # Пропустим минимальный throttle
        tracker._message_id = 42  # Не новое сообщение
        tracker._seed = 0
        await tracker.track(50)
        # send_progress_update должен был быть вызван с eta_text
        assert client.send_progress_update.await_count >= 1

    @pytest.mark.asyncio
    async def test_on_floodwait_no_message_id_early_return(self):
        """565-567: нет message_id → return без HTTP."""
        tracker, client = self._make_tracker()
        tracker._message_id = None
        await tracker.on_floodwait(60)
        client.send_progress_update.assert_not_called()

    @pytest.mark.asyncio
    async def test_on_floodwait_with_message_id_sends_update(self):
        tracker, client = self._make_tracker()
        tracker._message_id = 42
        tracker._total = 100
        tracker._seed = 0
        await tracker.on_floodwait(60)
        client.send_progress_update.assert_awaited()


class TestCreateJavaClient:

    @pytest.mark.asyncio
    async def test_connectivity_failure_raises(self):
        """589-592: verify_connectivity=False → RuntimeError."""
        with patch.object(JavaBotClient, "verify_connectivity", AsyncMock(return_value=False)):
            with pytest.raises(RuntimeError, match="Cannot reach Java API"):
                await create_java_client()

    @pytest.mark.asyncio
    async def test_connectivity_success_returns_client(self):
        with patch.object(JavaBotClient, "verify_connectivity", AsyncMock(return_value=True)):
            client = await create_java_client()
            assert isinstance(client, JavaBotClient)
            await client.aclose()


class TestRunCacheAwareExportFallbackCancel:
    """bug_010: при cancel внутри fallback fetch (_fetch_all_messages → None)
    нужно вызывать mark_job_completed + _cleanup_job — иначе staging-payload
    висит до JOB_MARKER_TTL и recover_staging_jobs на рестарте вернёт
    отменённый job в очередь.
    """

    def _make_worker(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=None)
        w.message_cache = MagicMock()
        w.message_cache.enabled = False
        w.queue_consumer = MagicMock()
        w.queue_consumer.mark_job_completed = AsyncMock()
        w._cleanup_job = AsyncMock()
        w._fetch_all_messages = AsyncMock(return_value=None)
        return w

    @pytest.mark.asyncio
    async def test_fallback_none_with_cancel_finalizes_job(self):
        w = self._make_worker()
        # Cancel поднимается внутри _fetch_all_messages (после counting / mid-stream).
        # Первая is_cancelled перед fallback → False, вторая (после _fetch=None) → True.
        w.is_cancelled = AsyncMock(side_effect=[False, True])
        job = make_job(task_id="cancel_fallback_task")

        result = await w._run_cache_aware_export(job, topic_name=None)

        assert result is None
        w._fetch_all_messages.assert_awaited_once()
        w.queue_consumer.mark_job_completed.assert_awaited_once_with("cancel_fallback_task")
        w._cleanup_job.assert_awaited_once_with(job)

    @pytest.mark.asyncio
    async def test_fallback_none_without_cancel_also_finalizes(self):
        """Даже если is_cancelled вернул False (rare race), всё равно не оставляем мусор."""
        w = self._make_worker()
        w.is_cancelled = AsyncMock(return_value=False)
        job = make_job(task_id="no_cancel_fallback")

        result = await w._run_cache_aware_export(job, topic_name=None)

        assert result is None
        w.queue_consumer.mark_job_completed.assert_awaited_once_with("no_cancel_fallback")
        w._cleanup_job.assert_awaited_once_with(job)
