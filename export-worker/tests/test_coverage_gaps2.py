"""
Второй набор целевых тестов для достижения 90% покрытия:

- main: run() main loop (happy + CancelledError + KeyboardInterrupt + generic exception)
- main: handle_signal, ExportCancelled handler в process_job, cleanup fallbacks
- main: exception-tolerance методов control_redis (heartbeat/clear_active_*/set_active_*)
- queue_consumer: connect/disconnect error paths, mark_job_* exception tolerance,
  staging helpers best-effort поведение, get_queue_stats, get_pending_jobs paths,
  recover_staging_jobs, push_job error
- java_client: _transform_entities, _format_period, _split_text_by_size,
  _build_filename, _sanitize_filename, _build_progress_bar, _format_eta,
  update_queue_position no-token, notify_empty_export period branches,
  _send_file_to_user size-based split, verify_connectivity
- pyrogram_client: _build_chat_info, disconnect exception, get_topic_name cache,
  get_chat_messages_count error, get_date_range_count paths
"""
import asyncio
import json
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

import redis.asyncio as aioredis
import redis

from main import ExportWorker
from models import ExportRequest
from queue_consumer import QueueConsumer
from java_client import JavaBotClient, ProgressTracker, _format_eta
from pyrogram_client import TelegramClient, ExportCancelled


# ─────────────────────────────────────────────────────────────────────────────
# main.ExportWorker — run() main loop
# ─────────────────────────────────────────────────────────────────────────────

class TestExportWorkerRunLoop:

    def _make_worker_ready(self):
        w = ExportWorker()
        w.queue_consumer = AsyncMock()
        w.telegram_client = AsyncMock()
        w.java_client = AsyncMock()
        w.message_cache = AsyncMock()
        w.control_redis = AsyncMock()
        return w

    @pytest.mark.asyncio
    async def test_run_initialize_failure_exits(self):
        w = ExportWorker()
        with patch.object(w, "initialize", AsyncMock(return_value=False)):
            with pytest.raises(SystemExit):
                await w.run()

    @pytest.mark.asyncio
    async def test_run_processes_job_and_stops(self):
        w = self._make_worker_ready()
        job = ExportRequest(task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0)
        # Первый get_job возвращает job, второй — stop loop
        w.queue_consumer.get_job = AsyncMock(side_effect=[job, None])

        async def _fake_initialize():
            w.running = True  # initialize выставляет
            return True

        async def _fake_process_job(j):
            w.running = False  # остановить loop после первого job
            return True

        with patch.object(w, "initialize", AsyncMock(side_effect=_fake_initialize)), \
             patch.object(w, "_update_all_queue_positions", AsyncMock()), \
             patch.object(w, "process_job", AsyncMock(side_effect=_fake_process_job)), \
             patch.object(w, "cleanup", AsyncMock()):
            await w.run()
            w.process_job.assert_called_once()
            w.cleanup.assert_called_once()

    @pytest.mark.asyncio
    async def test_run_handles_cancelled_error(self):
        w = self._make_worker_ready()
        w.queue_consumer.get_job = AsyncMock(side_effect=asyncio.CancelledError())

        with patch.object(w, "initialize", AsyncMock(return_value=True)), \
             patch.object(w, "cleanup", AsyncMock()):
            await w.run()
            w.cleanup.assert_called_once()

    @pytest.mark.asyncio
    async def test_run_handles_keyboard_interrupt(self):
        w = self._make_worker_ready()
        w.queue_consumer.get_job = AsyncMock(side_effect=KeyboardInterrupt())

        with patch.object(w, "initialize", AsyncMock(return_value=True)), \
             patch.object(w, "cleanup", AsyncMock()):
            await w.run()
            w.cleanup.assert_called_once()

    @pytest.mark.asyncio
    async def test_run_generic_exception_continues_with_sleep(self):
        w = self._make_worker_ready()
        # Первое — RuntimeError, второе — закрываем loop
        call_count = 0

        async def _get_job():
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise RuntimeError("transient error")
            w.running = False
            return None

        w.queue_consumer.get_job = AsyncMock(side_effect=_get_job)

        with patch.object(w, "initialize", AsyncMock(return_value=True)), \
             patch.object(w, "cleanup", AsyncMock()), \
             patch("main.asyncio.sleep", AsyncMock()) as sleep_mock:
            await w.run()
            # После исключения был вызван asyncio.sleep(5)
            sleeps = [c.args[0] for c in sleep_mock.call_args_list]
            assert 5 in sleeps


# ─────────────────────────────────────────────────────────────────────────────
# main.ExportWorker — handle_signal (используется в Windows-fallback теста e2e)
# ─────────────────────────────────────────────────────────────────────────────

class TestHandleSignal:

    def test_handle_signal_sets_running_false(self):
        w = ExportWorker()
        w.running = True
        w.handle_signal(signum=15, frame=None)
        assert w.running is False


# ─────────────────────────────────────────────────────────────────────────────
# main.process_job — ExportCancelled handler
# ─────────────────────────────────────────────────────────────────────────────

class TestProcessJobCancelled:

    @pytest.fixture
    def worker(self):
        w = ExportWorker()
        w.queue_consumer = AsyncMock()
        w.queue_consumer.mark_job_processing = AsyncMock(return_value=True)
        w.queue_consumer.mark_job_completed = AsyncMock(return_value=True)
        w.telegram_client = AsyncMock()
        w.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": 1, "title": "T"}, None)
        )
        w.telegram_client.get_messages_count = AsyncMock(return_value=100)
        w.telegram_client.get_chat_history = MagicMock()
        # Generator бросит ExportCancelled
        async def _hist(**kwargs):
            raise ExportCancelled("user cancelled")
            yield  # unreachable — but required to mark as async gen
        w.telegram_client.get_chat_history.side_effect = lambda **kw: _hist(**kw)
        w.java_client = AsyncMock()
        w.java_client.send_response = AsyncMock(return_value=True)
        w.java_client.create_progress_tracker = MagicMock(return_value=None)
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=None)
        w.control_redis.set = AsyncMock()
        w.control_redis.delete = AsyncMock()
        from message_cache import MessageCache
        w.message_cache = MessageCache(enabled=False)
        return w

    @pytest.mark.asyncio
    async def test_export_cancelled_cleanly_completes(self, worker):
        job = ExportRequest(
            task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0
        )
        result = await worker.process_job(job)
        # ExportCancelled → result=True + mark_job_completed вызван (не mark_job_failed)
        assert result is True
        worker.queue_consumer.mark_job_completed.assert_called()

    @pytest.mark.asyncio
    async def test_export_cancelled_cleanup_fail_still_returns_true(self, worker):
        worker.queue_consumer.mark_job_completed = AsyncMock(
            side_effect=RuntimeError("redis down")
        )
        job = ExportRequest(
            task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0
        )
        result = await worker.process_job(job)
        assert result is True


# ─────────────────────────────────────────────────────────────────────────────
# main.ExportWorker — exception-tolerance control_redis методов
# ─────────────────────────────────────────────────────────────────────────────

class TestControlRedisExceptionTolerance:

    @pytest.fixture
    def worker(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        return w

    @pytest.mark.asyncio
    async def test_heartbeat_redis_failure_silent(self, worker):
        worker.control_redis.set = AsyncMock(side_effect=RuntimeError("boom"))
        await worker.heartbeat("task1", stage="fetch")

    @pytest.mark.asyncio
    async def test_heartbeat_no_redis_silent(self):
        w = ExportWorker()
        w.control_redis = None
        await w.heartbeat("task1")

    @pytest.mark.asyncio
    async def test_clear_heartbeat_redis_failure_silent(self, worker):
        worker.control_redis.delete = AsyncMock(side_effect=RuntimeError("boom"))
        await worker.clear_heartbeat("task1")

    @pytest.mark.asyncio
    async def test_clear_heartbeat_no_redis_silent(self):
        w = ExportWorker()
        w.control_redis = None
        await w.clear_heartbeat("task1")

    @pytest.mark.asyncio
    async def test_clear_active_export_redis_failure_silent(self, worker):
        worker.control_redis.delete = AsyncMock(side_effect=RuntimeError("boom"))
        await worker.clear_active_export(user_id=42)

    @pytest.mark.asyncio
    async def test_set_active_processing_job_redis_failure_silent(self, worker):
        worker.control_redis.set = AsyncMock(side_effect=RuntimeError("boom"))
        await worker.set_active_processing_job("task1")

    @pytest.mark.asyncio
    async def test_clear_active_processing_job_redis_failure_silent(self, worker):
        worker.control_redis.delete = AsyncMock(side_effect=RuntimeError("boom"))
        await worker.clear_active_processing_job()

    @pytest.mark.asyncio
    async def test_is_cancelled_redis_failure_returns_false(self, worker):
        worker.control_redis.get = AsyncMock(side_effect=RuntimeError("boom"))
        result = await worker.is_cancelled("task1")
        assert result is False

    @pytest.mark.asyncio
    async def test_is_cancelled_no_redis_returns_false(self):
        w = ExportWorker()
        w.control_redis = None
        assert await w.is_cancelled("task1") is False


# ─────────────────────────────────────────────────────────────────────────────
# queue_consumer — connect/disconnect errors
# ─────────────────────────────────────────────────────────────────────────────

class TestQueueConsumerConnectDisconnect:

    @pytest.mark.asyncio
    async def test_connect_exception_returns_false(self):
        c = QueueConsumer()
        with patch("queue_consumer.redis.from_url", side_effect=RuntimeError("down")):
            result = await c.connect()
            assert result is False

    @pytest.mark.asyncio
    async def test_disconnect_exception_silent(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.close = AsyncMock(side_effect=RuntimeError("close fail"))
        await c.disconnect()  # не должен пробрасывать

    @pytest.mark.asyncio
    async def test_disconnect_no_client_silent(self):
        c = QueueConsumer()
        c.redis_client = None
        await c.disconnect()

    @pytest.mark.asyncio
    async def test_is_connected_property(self):
        c = QueueConsumer()
        c.redis_client = None
        assert c.is_connected is False
        c.redis_client = AsyncMock()
        assert c.is_connected is True


# ─────────────────────────────────────────────────────────────────────────────
# queue_consumer — get_job error paths
# ─────────────────────────────────────────────────────────────────────────────

class TestQueueConsumerGetJob:

    @pytest.mark.asyncio
    async def test_get_job_not_connected_raises(self):
        c = QueueConsumer()
        c.redis_client = None
        with pytest.raises(RuntimeError, match="Not connected"):
            await c.get_job()

    @pytest.mark.asyncio
    async def test_get_job_empty_queues_returns_none(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.blmove = AsyncMock(return_value=None)
        result = await c.get_job()
        assert result is None

    @pytest.mark.asyncio
    async def test_get_job_connection_error_reconnects(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        # blmove → ConnectionError
        c.redis_client.blmove = AsyncMock(
            side_effect=redis.ConnectionError("lost")
        )
        with patch.object(c, "_reconnect", AsyncMock(return_value=True)):
            result = await c.get_job()
            # reconnect успешен → None (сигнал retry)
            assert result is None

    @pytest.mark.asyncio
    async def test_get_job_connection_error_reconnect_fails(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.blmove = AsyncMock(
            side_effect=redis.ConnectionError("lost")
        )
        with patch.object(c, "_reconnect", AsyncMock(return_value=False)):
            with pytest.raises(RuntimeError, match="Redis connection lost"):
                await c.get_job()

    @pytest.mark.asyncio
    async def test_get_job_generic_exception_returns_none(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.blmove = AsyncMock(side_effect=RuntimeError("weird"))
        result = await c.get_job()
        assert result is None


# ─────────────────────────────────────────────────────────────────────────────
# queue_consumer — mark_job_* exception tolerance
# ─────────────────────────────────────────────────────────────────────────────

class TestQueueConsumerMarkExceptions:

    @pytest.fixture
    def consumer(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        # Atomic claim в _finalize_job: SET NX вернул True → по умолчанию мы выиграли
        # финализацию, тесты могут fall through в pipeline-логику.
        c.redis_client.set = AsyncMock(return_value=True)
        return c

    @pytest.mark.asyncio
    async def test_mark_processing_no_redis_returns_false(self):
        c = QueueConsumer()
        c.redis_client = None
        assert await c.mark_job_processing("t1") is False

    @pytest.mark.asyncio
    async def test_mark_processing_redis_error_returns_false(self, consumer):
        consumer.redis_client.setex = AsyncMock(side_effect=RuntimeError("boom"))
        assert await consumer.mark_job_processing("t1") is False

    @pytest.mark.asyncio
    async def test_mark_completed_no_redis_returns_false(self):
        c = QueueConsumer()
        c.redis_client = None
        assert await c.mark_job_completed("t1") is False

    @pytest.mark.asyncio
    async def test_mark_completed_pipeline_error(self, consumer):
        consumer.redis_client.get = AsyncMock(return_value=None)
        pipe = MagicMock()
        pipe.execute = AsyncMock(side_effect=RuntimeError("pipeline fail"))
        consumer.redis_client.pipeline = MagicMock(return_value=pipe)
        assert await consumer.mark_job_completed("t1") is False
        pipe.execute.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_mark_failed_no_redis_returns_false(self):
        c = QueueConsumer()
        c.redis_client = None
        assert await c.mark_job_failed("t1", "err") is False

    @pytest.mark.asyncio
    async def test_mark_failed_pipeline_error(self, consumer):
        consumer.redis_client.get = AsyncMock(return_value=None)
        pipe = MagicMock()
        pipe.execute = AsyncMock(side_effect=RuntimeError("pipeline fail"))
        consumer.redis_client.pipeline = MagicMock(return_value=pipe)
        assert await consumer.mark_job_failed("t1", "err") is False
        pipe.execute.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_mark_completed_with_staging_metadata_success(self, consumer):
        # staging:meta:<task> существует → LREM добавляется в pipeline
        consumer.redis_client.get = AsyncMock(
            return_value=json.dumps({"queue": "staging", "payload": "{}"})
        )
        pipe = MagicMock()
        pipe.execute = AsyncMock(return_value=None)
        consumer.redis_client.pipeline = MagicMock(return_value=pipe)
        result = await consumer.mark_job_completed("t1")
        assert result is True
        pipe.lrem.assert_called_once_with("staging", 1, "{}")

    @pytest.mark.asyncio
    async def test_mark_completed_staging_json_bad_silent(self, consumer):
        # Broken JSON в staging:meta — должно быть проглочено в pipeline
        consumer.redis_client.get = AsyncMock(return_value="{bad json")
        pipe = MagicMock()
        pipe.execute = AsyncMock(return_value=None)
        consumer.redis_client.pipeline = MagicMock(return_value=pipe)
        result = await consumer.mark_job_completed("t1")
        assert result is True
        pipe.lrem.assert_not_called()

    @pytest.mark.asyncio
    async def test_mark_completed_idempotent_when_already_final(self, consumer):
        # REGRESSION: второй mark_job_completed после финализации — no-op, иначе
        # дубль stats-события и перезаписанный completed_key. SET NX вернул None
        # → кто-то уже взял ключ, pipeline не должен стартовать.
        consumer.redis_client.set = AsyncMock(return_value=None)
        pipe = MagicMock()
        pipe.execute = AsyncMock(return_value=None)
        consumer.redis_client.pipeline = MagicMock(return_value=pipe)
        assert await consumer.mark_job_completed("t1") is False
        pipe.execute.assert_not_called()

    @pytest.mark.asyncio
    async def test_mark_failed_idempotent_when_already_final(self, consumer):
        consumer.redis_client.set = AsyncMock(return_value=None)
        pipe = MagicMock()
        pipe.execute = AsyncMock(return_value=None)
        consumer.redis_client.pipeline = MagicMock(return_value=pipe)
        assert await consumer.mark_job_failed("t1", "err") is False
        pipe.execute.assert_not_called()


# ─────────────────────────────────────────────────────────────────────────────
# queue_consumer — staging helpers best-effort
# ─────────────────────────────────────────────────────────────────────────────

class TestStagingHelpers:

    @pytest.mark.asyncio
    async def test_track_staging_job_exception_silent(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.sadd = AsyncMock(side_effect=RuntimeError("boom"))
        await c._track_staging_job("t1")  # best-effort

    @pytest.mark.asyncio
    async def test_untrack_staging_job_exception_silent(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.srem = AsyncMock(side_effect=RuntimeError("boom"))
        await c._untrack_staging_job("t1")

    @pytest.mark.asyncio
    async def test_store_staging_payload_exception_silent(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.setex = AsyncMock(side_effect=RuntimeError("boom"))
        await c._store_staging_payload("t1", "{}", "queue")

    @pytest.mark.asyncio
    async def test_remove_from_staging_no_meta(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.get = AsyncMock(return_value=None)
        c.redis_client.delete = AsyncMock()
        await c._remove_from_staging("t1")
        c.redis_client.delete.assert_called_once_with("staging:meta:t1")

    @pytest.mark.asyncio
    async def test_remove_from_staging_with_meta(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.get = AsyncMock(
            return_value=json.dumps({"queue": "q", "payload": "payload"})
        )
        c.redis_client.lrem = AsyncMock()
        c.redis_client.delete = AsyncMock()
        await c._remove_from_staging("t1")
        c.redis_client.lrem.assert_called_once_with("q", 1, "payload")

    @pytest.mark.asyncio
    async def test_remove_from_staging_exception_silent(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.get = AsyncMock(side_effect=RuntimeError("boom"))
        await c._remove_from_staging("t1")


# ─────────────────────────────────────────────────────────────────────────────
# queue_consumer — push_job, pending, stats, recover
# ─────────────────────────────────────────────────────────────────────────────

class TestQueueConsumerMisc:

    @pytest.mark.asyncio
    async def test_push_job_success(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.rpush = AsyncMock()
        job = ExportRequest(task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0)
        result = await c.push_job(job)
        assert result is True

    @pytest.mark.asyncio
    async def test_push_job_error_returns_false(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.rpush = AsyncMock(side_effect=RuntimeError("boom"))
        job = ExportRequest(task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0)
        assert await c.push_job(job) is False

    @pytest.mark.asyncio
    async def test_push_job_no_redis_raises(self):
        c = QueueConsumer()
        c.redis_client = None
        job = ExportRequest(task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0)
        with pytest.raises(RuntimeError):
            await c.push_job(job)

    @pytest.mark.asyncio
    async def test_get_pending_jobs_no_redis(self):
        c = QueueConsumer()
        c.redis_client = None
        result = await c.get_pending_jobs()
        assert result == {"jobs": [], "total_count": 0}

    @pytest.mark.asyncio
    async def test_get_pending_jobs_error_returns_empty(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.llen = AsyncMock(side_effect=RuntimeError("boom"))
        result = await c.get_pending_jobs()
        assert result["jobs"] == []
        assert result["total_count"] == 0

    @pytest.mark.asyncio
    async def test_get_pending_jobs_happy_path(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.llen = AsyncMock(side_effect=[2, 1])  # express_total, main_total
        job_data = json.dumps(ExportRequest(
            task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0
        ).model_dump())
        c.redis_client.lrange = AsyncMock(side_effect=[[job_data, job_data], [job_data]])
        result = await c.get_pending_jobs()
        assert result["total_count"] == 3
        assert len(result["jobs"]) == 3

    @pytest.mark.asyncio
    async def test_get_pending_jobs_skips_corrupt(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.llen = AsyncMock(side_effect=[1, 0])
        c.redis_client.lrange = AsyncMock(return_value=["{not-json"])
        result = await c.get_pending_jobs()
        assert result["total_count"] == 1  # total из llen
        assert result["jobs"] == []  # corrupted item не включен

    @pytest.mark.asyncio
    async def test_get_queue_stats_no_redis(self):
        c = QueueConsumer()
        c.redis_client = None
        assert await c.get_queue_stats() is None

    @pytest.mark.asyncio
    async def test_get_queue_stats_error_returns_none(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.llen = AsyncMock(side_effect=RuntimeError("boom"))
        assert await c.get_queue_stats() is None

    @pytest.mark.asyncio
    async def test_get_queue_stats_happy(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.llen = AsyncMock(return_value=5)
        stats = await c.get_queue_stats()
        assert stats["pending_jobs"] == 5

    @pytest.mark.asyncio
    async def test_recover_staging_no_redis(self):
        c = QueueConsumer()
        c.redis_client = None
        assert await c.recover_staging_jobs() == 0

    @pytest.mark.asyncio
    async def test_recover_staging_happy(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        # staging_express → express: 2 items, потом None
        # staging → main: 1 item, потом None
        c.redis_client.lmove = AsyncMock(
            side_effect=["item1", "item2", None, "item3", None]
        )
        c.redis_client.delete = AsyncMock()
        recovered = await c.recover_staging_jobs()
        assert recovered == 3

    @pytest.mark.asyncio
    async def test_recover_staging_error_returns_zero(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.lmove = AsyncMock(side_effect=RuntimeError("boom"))
        assert await c.recover_staging_jobs() == 0

    @pytest.mark.asyncio
    async def test_publish_failed_event_exception_silent(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.xadd = AsyncMock(side_effect=RuntimeError("stream down"))
        await c._publish_failed_event("t1", "error")  # не должно пробросить

    @pytest.mark.asyncio
    async def test_context_manager(self):
        c = QueueConsumer()
        with patch.object(c, "connect", AsyncMock()) as cn, \
             patch.object(c, "disconnect", AsyncMock()) as dc:
            async with c as ctx:
                assert ctx is c
            cn.assert_called_once()
            dc.assert_called_once()


# ─────────────────────────────────────────────────────────────────────────────
# java_client — pure/utility functions
# ─────────────────────────────────────────────────────────────────────────────

class TestJavaClientUtilities:

    def test_format_period_both_dates(self):
        assert JavaBotClient._format_period("2025-01-01T00:00:00", "2025-12-31T23:59:59") \
            == "период 2025-01-01 — 2025-12-31"

    def test_format_period_from_only(self):
        assert JavaBotClient._format_period("2025-01-01T00:00:00", None) \
            == "период с 2025-01-01"

    def test_format_period_to_only(self):
        assert JavaBotClient._format_period(None, "2025-12-31T23:59:59") \
            == "период до 2025-12-31"

    def test_format_period_none(self):
        assert JavaBotClient._format_period(None, None) is None

    def test_sanitize_filename_special_chars(self):
        result = JavaBotClient._sanitize_filename("My Chat / Test?!")
        # Специальные символы удалены, пробелы → underscore
        assert "/" not in result
        assert "?" not in result
        assert "!" not in result

    def test_sanitize_filename_empty(self):
        assert JavaBotClient._sanitize_filename("") == "export"

    def test_sanitize_filename_only_specials(self):
        # Только спец. символы → пустая → fallback "export"
        assert JavaBotClient._sanitize_filename("!@#$%^&*()") == "export"

    def test_sanitize_filename_truncates_long(self):
        result = JavaBotClient._sanitize_filename("a" * 200)
        assert len(result) <= 80

    def test_transform_entities_happy_path(self):
        text = "Hello world"
        entities = [{"offset": 0, "length": 5, "type": "bold"}]
        result = JavaBotClient._transform_entities(text, entities)
        assert len(result) == 1
        assert result[0]["type"] == "bold"
        assert result[0]["text"] == "Hello"

    def test_transform_entities_with_url(self):
        text = "Click here"
        entities = [{"offset": 0, "length": 5, "type": "link", "url": "https://ex.com"}]
        result = JavaBotClient._transform_entities(text, entities)
        assert result[0]["href"] == "https://ex.com"

    def test_transform_entities_with_user_id(self):
        text = "User"
        entities = [{"offset": 0, "length": 4, "type": "mention_name", "user_id": 123}]
        result = JavaBotClient._transform_entities(text, entities)
        assert result[0]["user_id"] == 123

    def test_transform_entities_empty_returns_original(self):
        result = JavaBotClient._transform_entities("", [{"offset": 0, "length": 0}])
        assert result == [{"offset": 0, "length": 0}]

    def test_transform_entities_no_entities_returns_empty(self):
        result = JavaBotClient._transform_entities("text", [])
        assert result == []

    def test_build_progress_bar_empty(self):
        bar = JavaBotClient._build_progress_bar(0, width=10)
        assert "▓" not in bar
        assert bar.count("░") == 10

    def test_build_progress_bar_full(self):
        bar = JavaBotClient._build_progress_bar(100, width=10)
        assert bar.count("▓") == 10
        assert "░" not in bar

    def test_build_progress_bar_half(self):
        bar = JavaBotClient._build_progress_bar(50, width=10)
        assert bar.count("▓") == 5
        assert bar.count("░") == 5

    def test_build_progress_bar_clamps_over_100(self):
        bar = JavaBotClient._build_progress_bar(150, width=10)
        assert bar.count("▓") == 10

    def test_build_progress_bar_clamps_negative(self):
        bar = JavaBotClient._build_progress_bar(-10, width=10)
        assert bar.count("░") == 10

    def test_format_eta_zero_or_negative(self):
        assert _format_eta(0) is None
        assert _format_eta(-5) is None

    def test_format_eta_seconds(self):
        assert _format_eta(45) == "45 сек"

    def test_format_eta_minutes(self):
        assert _format_eta(125) == "2 мин"

    def test_format_eta_hours_no_minutes(self):
        assert _format_eta(3600) == "1 ч"

    def test_format_eta_hours_and_minutes(self):
        assert _format_eta(3660) == "1 ч 1 мин"


class TestSplitTextBySize:

    def test_short_text_single_part(self):
        client = JavaBotClient.__new__(JavaBotClient)
        parts = client._split_text_by_size("hello\nworld", max_bytes=1024)
        assert len(parts) == 1

    def test_long_text_splits(self):
        client = JavaBotClient.__new__(JavaBotClient)
        # Ровно 100 строк по ~10 байт, max 50 байт → >= 2 частей
        text = "\n".join([f"line{i}" for i in range(100)])
        parts = client._split_text_by_size(text, max_bytes=50)
        assert len(parts) >= 2


class TestBuildFilename:

    def test_with_dates(self):
        client = JavaBotClient.__new__(JavaBotClient)
        fn = client._build_filename(
            "My Chat", "2025-01-01T00:00:00", "2025-12-31T23:59:59"
        )
        assert fn.endswith(".txt")
        assert "2025-01-01" in fn
        assert "2025-12-31" in fn

    def test_without_dates(self):
        client = JavaBotClient.__new__(JavaBotClient)
        fn = client._build_filename("My Chat", None, None)
        assert fn == "My_Chat_all.txt"

    def test_no_title_fallback(self):
        client = JavaBotClient.__new__(JavaBotClient)
        fn = client._build_filename(None, None, None)
        assert fn == "export_all.txt"


# ─────────────────────────────────────────────────────────────────────────────
# java_client — async methods with exception tolerance
# ─────────────────────────────────────────────────────────────────────────────

def _make_java_client():
    client = JavaBotClient.__new__(JavaBotClient)
    client.base_url = "http://java:8080"
    client.bot_token = "fake:token"
    client._http_client = AsyncMock()
    client.max_retries = 1
    return client


class TestJavaClientAsyncMethods:

    @pytest.mark.asyncio
    async def test_verify_connectivity_200_returns_true(self):
        client = _make_java_client()
        resp = MagicMock()
        resp.status_code = 200
        client._http_client.get = AsyncMock(return_value=resp)
        assert await client.verify_connectivity() is True

    @pytest.mark.asyncio
    async def test_verify_connectivity_non_200_returns_false(self):
        client = _make_java_client()
        resp = MagicMock()
        resp.status_code = 500
        client._http_client.get = AsyncMock(return_value=resp)
        assert await client.verify_connectivity() is False

    @pytest.mark.asyncio
    async def test_verify_connectivity_exception_returns_false(self):
        client = _make_java_client()
        client._http_client.get = AsyncMock(side_effect=RuntimeError("timeout"))
        assert await client.verify_connectivity() is False

    @pytest.mark.asyncio
    async def test_notify_user_failure_exception_silent(self):
        client = _make_java_client()
        client._http_client.post = AsyncMock(side_effect=RuntimeError("tg down"))
        # Не должен пробросить
        await client.notify_user_failure(123, "task1", "Some error")

    @pytest.mark.asyncio
    async def test_notify_empty_export_with_period(self):
        client = _make_java_client()
        client._http_client.post = AsyncMock()
        await client.notify_empty_export(
            123, "task1",
            "2025-01-01T00:00:00", "2025-12-31T23:59:59"
        )
        client._http_client.post.assert_called_once()
        call = client._http_client.post.call_args
        text = call.kwargs["data"]["text"]
        assert "2025-01-01" in text

    @pytest.mark.asyncio
    async def test_notify_empty_export_no_dates(self):
        client = _make_java_client()
        client._http_client.post = AsyncMock()
        await client.notify_empty_export(123, "task1", None, None)
        call = client._http_client.post.call_args
        text = call.kwargs["data"]["text"]
        # Нет упоминания "период"
        assert "период" not in text.lower()

    @pytest.mark.asyncio
    async def test_notify_empty_export_exception_silent(self):
        client = _make_java_client()
        client._http_client.post = AsyncMock(side_effect=RuntimeError("boom"))
        await client.notify_empty_export(123, "task1", None, None)

    @pytest.mark.asyncio
    async def test_update_queue_position_no_bot_token_skips(self):
        client = _make_java_client()
        client.bot_token = None
        client._http_client.post = AsyncMock()
        await client.update_queue_position(123, 456, 3, 10)
        client._http_client.post.assert_not_called()

    @pytest.mark.asyncio
    async def test_update_queue_position_pos_zero_message(self):
        client = _make_java_client()
        client._http_client.post = AsyncMock()
        await client.update_queue_position(123, 456, 0, 5)
        call = client._http_client.post.call_args
        assert "начался" in call.kwargs["data"]["text"].lower()

    @pytest.mark.asyncio
    async def test_update_queue_position_pos_nonzero_message(self):
        client = _make_java_client()
        client._http_client.post = AsyncMock()
        await client.update_queue_position(123, 456, 3, 10)
        call = client._http_client.post.call_args
        assert "3/10" in call.kwargs["data"]["text"]

    @pytest.mark.asyncio
    async def test_update_queue_position_exception_silent(self):
        client = _make_java_client()
        client._http_client.post = AsyncMock(side_effect=RuntimeError("boom"))
        await client.update_queue_position(123, 456, 3, 10)

    @pytest.mark.asyncio
    async def test_send_single_file_non_200_returns_false(self):
        client = _make_java_client()
        resp = MagicMock()
        resp.status_code = 400
        client._http_client.post = AsyncMock(return_value=resp)
        ok = await client._send_single_file(123, "t1", b"content", "f.txt", "✅")
        assert ok is False

    @pytest.mark.asyncio
    async def test_send_single_file_exception_returns_false(self):
        client = _make_java_client()
        client._http_client.post = AsyncMock(side_effect=RuntimeError("boom"))
        ok = await client._send_single_file(123, "t1", b"content", "f.txt", "✅")
        assert ok is False

    @pytest.mark.asyncio
    async def test_send_file_to_user_small_text(self):
        client = _make_java_client()
        with patch.object(client, "_send_single_file", AsyncMock(return_value=True)):
            ok = await client._send_file_to_user(123, "t1", "small text", "f.txt")
            assert ok is True

    @pytest.mark.asyncio
    async def test_send_file_to_user_large_text_splits(self):
        client = _make_java_client()
        large_text = "line\n" * 100_000  # много строк
        with patch.object(client, "_split_text_by_size",
                         return_value=["part1", "part2", "part3"]), \
             patch.object(client, "_send_single_file", AsyncMock(return_value=True)) as send:
            # Принудительно превысить порог 45MB
            huge_text = "x" * (50 * 1024 * 1024)
            ok = await client._send_file_to_user(123, "t1", huge_text, "f.txt")
            assert ok is True
            assert send.call_count == 3

    @pytest.mark.asyncio
    async def test_aclose_closes_client(self):
        client = _make_java_client()
        client._http_client.aclose = AsyncMock()
        await client.aclose()
        client._http_client.aclose.assert_called_once()


# ─────────────────────────────────────────────────────────────────────────────
# pyrogram_client — pure functions + disconnect error path
# ─────────────────────────────────────────────────────────────────────────────

def _make_telegram_client():
    tc = TelegramClient.__new__(TelegramClient)
    tc.client = AsyncMock()
    tc.is_connected = True
    tc.redis_client = None
    tc._topic_name_cache = {}
    tc._TOPIC_NAME_CACHE_MAX = 500
    return tc


class TestBuildChatInfo:

    def test_full_fields(self):
        chat = MagicMock()
        chat.id = 100
        chat.title = "Title"
        chat.username = "user"
        chat.type = "private"
        chat.is_bot = True
        chat.is_self = False
        chat.is_contact = True
        chat.members_count = 5
        chat.description = "desc"

        info = TelegramClient._build_chat_info(chat)
        assert info["id"] == 100
        assert info["title"] == "Title"
        assert info["username"] == "user"
        assert info["is_bot"] is True
        assert info["members_count"] == 5

    def test_missing_fields_use_defaults(self):
        chat = MagicMock(spec=[])  # без атрибутов
        chat.id = 100
        chat.type = "private"

        info = TelegramClient._build_chat_info(chat)
        assert info["title"] == ""
        assert info["username"] == ""
        assert info["is_bot"] is False
        assert info["members_count"] == 0


class TestDisconnectErrors:

    @pytest.mark.asyncio
    async def test_disconnect_exception_silent(self):
        tc = _make_telegram_client()
        tc.client.is_connected = True
        tc.client.stop = AsyncMock(side_effect=RuntimeError("boom"))
        await tc.disconnect()  # не должен пробросить

    @pytest.mark.asyncio
    async def test_disconnect_not_connected_no_stop(self):
        tc = _make_telegram_client()
        tc.is_connected = False
        tc.client.stop = AsyncMock()
        await tc.disconnect()
        tc.client.stop.assert_not_called()


class TestGetChatMessagesCount:

    @pytest.mark.asyncio
    async def test_get_count_success(self):
        tc = _make_telegram_client()
        tc.client.get_chat_history_count = AsyncMock(return_value=42)
        assert await tc.get_chat_messages_count(123) == 42

    @pytest.mark.asyncio
    async def test_get_count_zero_returns_none(self):
        tc = _make_telegram_client()
        tc.client.get_chat_history_count = AsyncMock(return_value=0)
        # count > 0 проверка → 0 → None
        assert await tc.get_chat_messages_count(123) is None

    @pytest.mark.asyncio
    async def test_get_count_exception_returns_none(self):
        tc = _make_telegram_client()
        tc.client.get_chat_history_count = AsyncMock(side_effect=RuntimeError("boom"))
        assert await tc.get_chat_messages_count(123) is None


class TestGetTopicName:

    @pytest.mark.asyncio
    async def test_cached_returns_from_cache(self):
        tc = _make_telegram_client()
        tc._topic_name_cache = {(123, 5): "Cached Topic"}
        assert await tc.get_topic_name(123, 5) == "Cached Topic"

    @pytest.mark.asyncio
    async def test_exception_returns_none(self):
        tc = _make_telegram_client()
        tc.client.resolve_peer = AsyncMock(side_effect=RuntimeError("peer fail"))
        assert await tc.get_topic_name(123, 5) is None

    @pytest.mark.asyncio
    async def test_cache_eviction_at_max(self):
        tc = _make_telegram_client()
        tc._TOPIC_NAME_CACHE_MAX = 2
        tc._topic_name_cache = {(1, 1): "a", (2, 2): "b"}

        tc.client.resolve_peer = AsyncMock(return_value=MagicMock())
        topic = MagicMock(title="New Topic")
        result = MagicMock(topics=[topic])
        tc.client.invoke = AsyncMock(return_value=result)

        name = await tc.get_topic_name(3, 3)
        assert name == "New Topic"
        # Cache был очищен при достижении max
        assert len(tc._topic_name_cache) == 1


class TestGetMessagesCountDispatch:

    @pytest.mark.asyncio
    async def test_with_topic_id_calls_topic_count(self):
        tc = _make_telegram_client()
        with patch.object(tc, "get_topic_messages_count",
                         AsyncMock(return_value=10)) as m:
            result = await tc.get_messages_count(123, topic_id=5)
            assert result == 10
            m.assert_called_once()

    @pytest.mark.asyncio
    async def test_with_date_calls_date_range(self):
        tc = _make_telegram_client()
        with patch.object(tc, "get_date_range_count",
                         AsyncMock(return_value=20)) as m:
            result = await tc.get_messages_count(
                123,
                from_date=datetime(2025, 1, 1),
                to_date=datetime(2025, 1, 31),
            )
            assert result == 20

    @pytest.mark.asyncio
    async def test_no_topic_no_date_calls_chat_count(self):
        tc = _make_telegram_client()
        with patch.object(tc, "get_chat_messages_count",
                         AsyncMock(return_value=50)) as m:
            result = await tc.get_messages_count(123)
            assert result == 50
