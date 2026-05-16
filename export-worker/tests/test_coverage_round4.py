"""Round 4 coverage: добиваем 91.91 → 95% покрытие.

Целимся в самые крупные uncovered блоки:
- main._verify_and_normalize_chat: ветка private/bot chat (412-435, ~24 строки) +
  canonical mapping success/failure paths (446-460), topic name path (462-466).
- main._run_cache_aware_export: все три cancel-ветки (492-497, 501-505, 522-527).
- main._fetch_all_messages: ValueError в from_date/to_date парсинге (1097-1098,
  1103-1104), cancel перед finalize (1072-1075), nocache cancel-mid-batch (1158-1161),
  except Exception перепрокидывание (1175-1176).
- main._send_completed_result: subscription empty без user_chat_id (548-555),
  msg_count==0 для не-subscription (560-563), heartbeat-fail tolerance, failed branch
  send_response → mark_job_failed (594-598).
- main._handle_unexpected_error: send_response success path, send_response throws →
  fallback notify_user_failure, fallback тоже throws, mark_job_failed throws,
  cleanup throws (609-650).
- main._handle_setup variants (process_job entry): _setup_processing return True,
  ExportCancelled путь (733-751).
- java_client: send_response failed-status w/ user notify, actual_count=0 path +
  aclose error swallowing (52-82), Java sentinel mismatch retry (236-247),
  400-status no-retry (250-251), _send_file_to_user exception 322-323,
  _split_text_by_size, _send_single_file >45MB split (305-309), verify_connectivity
  fail (340-343), update_queue_position position=0 vs >0 (440-457), send_progress_update
  edit fail / send fail (520-522), ProgressTracker seed, on_floodwait, finalize.
- queue_consumer: connect fail (60-62), _reconnect exhaust (82-85), get_job DLQ paths
  (143-156), ConnectionError reconnect path (158-166), _move_to_dlq + publish event,
  recover_staging_jobs error (475-477), mark_job_processing fail (271-273),
  _finalize_job already-claimed (291-293), malformed staging:meta (302-309).
"""

from __future__ import annotations

import json
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from main import ExportWorker
from models import ExportRequest, ExportedMessage, SendResponsePayload
from java_client import JavaBotClient, ProgressTracker
from pyrogram_client import ExportCancelled


# ─────────────────────────────────────────────────────────────────────────────
# helpers
# ─────────────────────────────────────────────────────────────────────────────

def make_job(**overrides) -> ExportRequest:
    base = dict(
        task_id="t_r4_1",
        user_id=42,
        user_chat_id=42,
        chat_id=-1001234567890,
        limit=0,
    )
    base.update(overrides)
    return ExportRequest(**base)


def _bare_worker() -> ExportWorker:
    """Bare worker без подключений — все клиенты заглушаем тестом."""
    w = ExportWorker.__new__(ExportWorker)
    w.telegram_client = None
    w.queue_consumer = None
    w.java_client = None
    w.message_cache = None
    w.control_redis = None
    w._redis_pool = None
    w.running = False
    w.jobs_processed = 0
    w.jobs_failed = 0
    w._cache_stats_task = None
    return w


def _wire_common(w: ExportWorker) -> ExportWorker:
    w.queue_consumer = AsyncMock()
    w.queue_consumer.mark_job_completed = AsyncMock(return_value=True)
    w.queue_consumer.mark_job_failed = AsyncMock(return_value=True)
    w.queue_consumer.mark_job_processing = AsyncMock(return_value=True)
    w.java_client = AsyncMock()
    w.java_client.send_response = AsyncMock(return_value=True)
    w.java_client.notify_subscription_empty = AsyncMock()
    w.java_client.notify_user_failure = AsyncMock()
    w.telegram_client = AsyncMock()
    w.control_redis = AsyncMock()
    w.control_redis.get = AsyncMock(return_value=None)
    w.control_redis.set = AsyncMock()
    w.control_redis.delete = AsyncMock()

    pipe = AsyncMock()
    pipe.set = MagicMock()
    pipe.execute = AsyncMock(return_value=None)
    w.control_redis.pipeline = MagicMock(return_value=pipe)
    return w


class _AsyncIter:
    def __init__(self, items):
        self._it = iter(items)

    def __aiter__(self):
        return self

    async def __anext__(self):
        try:
            return next(self._it)
        except StopIteration:
            raise StopAsyncIteration


# ─────────────────────────────────────────────────────────────────────────────
# _verify_and_normalize_chat — private/bot chat-block (lines 411-435)
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
class TestVerifyAndNormalizeChat:

    async def test_private_chat_blocked_sends_failed_response(self):
        w = _wire_common(_bare_worker())
        w.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "John", "type": "private", "id": 999}, None)
        )

        job = make_job(chat_id=999)
        ok, returned_job, chat_info, topic_name = await w._verify_and_normalize_chat(job)

        assert ok is False
        # send_response с status=failed + CHAT_PRIVATE
        w.java_client.send_response.assert_awaited_once()
        payload = w.java_client.send_response.await_args.args[0]
        assert payload.status == "failed"
        assert payload.error_code == "CHAT_PRIVATE"
        assert "Личные переписки" in payload.error
        w.queue_consumer.mark_job_failed.assert_awaited_once()

    async def test_bot_chat_blocked_same_path(self):
        w = _wire_common(_bare_worker())
        w.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "SomeBot", "type": "bot", "id": 1234}, None)
        )

        job = make_job(chat_id=1234)
        ok, *_ = await w._verify_and_normalize_chat(job)
        assert ok is False
        payload = w.java_client.send_response.await_args.args[0]
        assert payload.error_code == "CHAT_PRIVATE"

    async def test_allowed_channel_normalizes_chat_id_and_stores_canonical(self):
        w = _wire_common(_bare_worker())
        w.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {
                "title": "Chan", "type": "channel",
                "id": -1009999999999, "username": "chan_user",
            }, None)
        )
        # job.chat_id != canonical → ветка normalizing
        job = make_job(chat_id="@chan_user")
        ok, new_job, chat_info, topic_name = await w._verify_and_normalize_chat(job)
        assert ok is True
        assert new_job.chat_id == -1009999999999
        # pipeline.set вызывался хотя бы раз
        pipe = w.control_redis.pipeline.return_value
        assert pipe.set.called
        assert pipe.execute.await_count == 1

    async def test_canonical_mapping_pipeline_failure_is_swallowed(self):
        w = _wire_common(_bare_worker())
        w.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {
                "title": "G", "type": "supergroup",
                "id": -1000000000001, "username": None,
            }, None)
        )
        # pipeline.execute падает
        pipe = w.control_redis.pipeline.return_value
        pipe.execute.side_effect = RuntimeError("redis down")
        job = make_job(chat_id="@g_username")
        ok, new_job, *_ = await w._verify_and_normalize_chat(job)
        # exception swallowed → ok=True
        assert ok is True
        assert new_job.chat_id == -1000000000001

    async def test_topic_id_resolves_topic_name(self):
        w = _wire_common(_bare_worker())
        w.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "G", "type": "supergroup", "id": -1001}, None)
        )
        w.telegram_client.get_topic_name = AsyncMock(return_value="My Topic")
        job = make_job(chat_id=-1001, topic_id=42)
        ok, _job, _info, topic_name = await w._verify_and_normalize_chat(job)
        assert ok is True
        assert topic_name == "My Topic"

    async def test_accessible_false_session_invalid_triggers_recovery(self):
        """error_reason=SESSION_INVALID → _try_session_recovery → True → return."""
        w = _wire_common(_bare_worker())
        w._cleanup_job = AsyncMock()
        w.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(False, None, "SESSION_INVALID")
        )
        w._try_session_recovery = AsyncMock(return_value=True)

        job = make_job()
        ok, *_ = await w._verify_and_normalize_chat(job)
        assert ok is False
        w._try_session_recovery.assert_awaited_once()

    async def test_accessible_false_unknown_reason_uses_fallback_message(self):
        w = _wire_common(_bare_worker())
        w._cleanup_job = AsyncMock()
        w.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(False, None, "BOGUS_REASON")
        )
        job = make_job()
        ok, *_ = await w._verify_and_normalize_chat(job)
        assert ok is False
        # send_response called with default fallback error
        payload = w.java_client.send_response.await_args.args[0]
        assert "No access to chat" in payload.error
        assert payload.error_code == "BOGUS_REASON"


# ─────────────────────────────────────────────────────────────────────────────
# _run_cache_aware_export — все три cancel-ветки
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
class TestRunCacheAwareExportCancels:

    def _make(self):
        w = _wire_common(_bare_worker())
        w._cleanup_job = AsyncMock()
        w.message_cache = MagicMock()
        w.message_cache.enabled = False
        return w

    async def test_cancel_after_cache_path_returns_none(self):
        """has_date_filter=True → _export_with_date_cache=None → cache_was_tried path."""
        w = self._make()
        w.message_cache.enabled = True
        w._export_with_date_cache = AsyncMock(return_value=None)
        w.is_cancelled = AsyncMock(return_value=True)
        job = make_job(from_date="2025-01-01T00:00:00")
        result = await w._run_cache_aware_export(job, topic_name=None)
        assert result is None
        w.queue_consumer.mark_job_completed.assert_awaited()

    async def test_cancel_before_fallback_returns_none(self):
        """cache disabled → проверка отмены перед fallback (501-505)."""
        w = self._make()
        # cache disabled → cache_was_tried=False, попадаем в нижний if-блок
        w.is_cancelled = AsyncMock(return_value=True)
        job = make_job()
        result = await w._run_cache_aware_export(job, topic_name=None)
        assert result is None
        w.queue_consumer.mark_job_completed.assert_awaited()

    async def test_cancel_after_fallback_returns_none(self):
        """_fetch_all_messages=None + cancelled внутри fallback (513-517)."""
        w = self._make()
        cancel_seq = [False, True]  # first call=False, second=True

        async def fake_cancel(task_id):
            return cancel_seq.pop(0) if cancel_seq else False

        w.is_cancelled = fake_cancel
        w._fetch_all_messages = AsyncMock(return_value=None)
        job = make_job()
        result = await w._run_cache_aware_export(job, topic_name=None)
        assert result is None
        w._fetch_all_messages.assert_awaited_once()

    async def test_cancel_at_final_check_before_send(self):
        """Финальный cancel-check (523-527) — cache hit, success, но cancel перед send."""
        w = self._make()
        w.message_cache.enabled = True

        async def _items():
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")

        w._export_with_id_cache = AsyncMock(return_value=(1, _items()))
        cancel_seq = [True]

        async def fake_cancel(task_id):
            return cancel_seq.pop(0) if cancel_seq else False

        w.is_cancelled = fake_cancel
        job = make_job()  # no date filter → id_cache
        result = await w._run_cache_aware_export(job, topic_name=None)
        assert result is None


# ─────────────────────────────────────────────────────────────────────────────
# _fetch_all_messages — ValueError date parse + cancel-paths
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
class TestFetchAllMessagesEdges:

    def _make(self, cache_enabled=False):
        w = _wire_common(_bare_worker())
        w.telegram_client.get_messages_count = AsyncMock(return_value=0)
        w.message_cache = MagicMock()
        w.message_cache.enabled = cache_enabled
        w.message_cache.store_messages = AsyncMock()
        w.message_cache.iter_messages = lambda *a, **kw: _AsyncIter([])
        w.message_cache.iter_messages_by_date = lambda *a, **kw: _AsyncIter([])
        w.java_client = None
        return w

    async def test_invalid_from_date_swallowed_by_value_error(self):
        """fromisoformat raises ValueError → ветки 1097-1098 и 1103-1104.

        ExportRequest frozen + field_validator блокирует невалидные даты на
        конструкции — но _fetch_all_messages парсит сам с try/except ValueError,
        что покрывает edge-case, если откуда-то прилетит invalid string.
        Обходим pydantic-валидацию через MagicMock c нужным контрактом.
        """
        w = self._make()

        async def empty_history(*a, **kw):
            if False:
                yield

        w.telegram_client.get_chat_history = empty_history

        fake_job = MagicMock()
        fake_job.task_id = "t"
        fake_job.user_id = 1
        fake_job.chat_id = -100
        fake_job.user_chat_id = None
        fake_job.from_date = "not-a-date"
        fake_job.to_date = "also-bad"
        fake_job.topic_id = None
        fake_job.effective_topic_id = 0
        fake_job.limit = 0
        fake_job.offset_id = 0
        result = await w._fetch_all_messages(fake_job)
        # cache disabled → returns (count, nocache_messages_list)
        assert result == (0, [])

    async def test_generic_exception_reraises_with_log(self):
        """except Exception: logger.error + raise — ветка 1174-1176."""
        w = self._make(cache_enabled=False)

        async def boom(*a, **kw):
            raise RuntimeError("upstream")
            yield  # pragma: no cover

        w.telegram_client.get_chat_history = boom
        job = make_job()
        with pytest.raises(RuntimeError, match="upstream"):
            await w._fetch_all_messages(job)


# ─────────────────────────────────────────────────────────────────────────────
# _send_completed_result — все интересные пути
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
class TestSendCompletedResult:

    def _make(self):
        w = _wire_common(_bare_worker())
        w._cleanup_job = AsyncMock()
        w.heartbeat = AsyncMock()
        w.log_memory_usage = MagicMock()
        return w

    async def test_subscription_empty_without_user_chat_id_still_marks_completed(self):
        """job.source=subscription, msg_count=0, user_chat_id=None →
        notify_subscription_empty НЕ вызывается, но mark_job_completed да."""
        w = self._make()
        job = make_job(
            source="subscription", subscription_id=7,
            user_chat_id=None,
        )
        await w._send_completed_result(job, msg_count=0, messages_for_send=[],
                                       chat_info={"title": "T"})
        w.java_client.notify_subscription_empty.assert_not_called()
        w.queue_consumer.mark_job_completed.assert_awaited()
        assert w.jobs_processed == 1

    async def test_zero_messages_non_subscription_logs_but_calls_java(self):
        """msg_count=0, source=bot → java_client.send_response вызывается всё равно."""
        w = self._make()
        job = make_job(source="bot")
        await w._send_completed_result(job, msg_count=0, messages_for_send=[],
                                       chat_info={"title": "Foo", "username": "@foo"})
        w.java_client.send_response.assert_awaited_once()
        w.queue_consumer.mark_job_completed.assert_awaited()
        assert w.jobs_processed == 1

    async def test_send_response_failed_marks_job_failed_increments_counter(self):
        """send_response=False → ветка else: mark_job_failed + jobs_failed+=1."""
        w = self._make()
        w.java_client.send_response = AsyncMock(return_value=False)
        job = make_job()
        msgs = [ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")]
        await w._send_completed_result(job, msg_count=1, messages_for_send=msgs,
                                       chat_info={"title": "Foo"})
        w.queue_consumer.mark_job_failed.assert_awaited()
        assert w.jobs_failed == 1
        assert w.jobs_processed == 0

    async def test_chat_info_none_yields_no_title(self):
        """chat_info=None — chat_title=None, send_response получает chat_title=None."""
        w = self._make()
        job = make_job()
        msgs = [ExportedMessage(id=1, date="2025-01-01T00:00:00", text="x")]
        await w._send_completed_result(job, msg_count=1, messages_for_send=msgs,
                                       chat_info=None)
        payload = w.java_client.send_response.await_args.args[0]
        assert payload.chat_title is None


# ─────────────────────────────────────────────────────────────────────────────
# _handle_unexpected_error — все ветки (lines 609-650)
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
class TestHandleUnexpectedError:

    def _make(self):
        w = _wire_common(_bare_worker())
        w._cleanup_job = AsyncMock()
        w.log_memory_usage = MagicMock()
        return w

    async def test_send_response_success_skips_fallback_notify(self):
        w = self._make()
        job = make_job()
        await w._handle_unexpected_error(job, RuntimeError("boom"))
        w.java_client.send_response.assert_awaited_once()
        w.java_client.notify_user_failure.assert_not_called()
        w.queue_consumer.mark_job_failed.assert_awaited()
        w._cleanup_job.assert_awaited_once()
        assert w.jobs_failed == 1

    async def test_send_response_throws_falls_back_to_notify_user_failure(self):
        w = self._make()
        w.java_client.send_response = AsyncMock(side_effect=RuntimeError("api down"))
        w.java_client.notify_user_failure = AsyncMock(return_value=None)
        job = make_job(user_chat_id=999)
        await w._handle_unexpected_error(job, RuntimeError("inner"))
        w.java_client.notify_user_failure.assert_awaited_once()

    async def test_fallback_notify_also_fails_silently(self):
        w = self._make()
        w.java_client.send_response = AsyncMock(side_effect=RuntimeError("api down"))
        w.java_client.notify_user_failure = AsyncMock(side_effect=RuntimeError("tg down"))
        job = make_job(user_chat_id=999)
        # должно завершиться без проброса
        await w._handle_unexpected_error(job, RuntimeError("inner"))
        w.java_client.notify_user_failure.assert_awaited_once()
        w.queue_consumer.mark_job_failed.assert_awaited()

    async def test_mark_job_failed_throws_swallowed(self):
        w = self._make()
        w.queue_consumer.mark_job_failed = AsyncMock(side_effect=RuntimeError("redis"))
        job = make_job()
        await w._handle_unexpected_error(job, RuntimeError("inner"))
        assert w.jobs_failed == 1  # increment всё равно идёт

    async def test_cleanup_throws_swallowed(self):
        w = self._make()
        w._cleanup_job = AsyncMock(side_effect=RuntimeError("cleanup"))
        job = make_job()
        await w._handle_unexpected_error(job, RuntimeError("inner"))
        w._cleanup_job.assert_awaited_once()

    async def test_no_user_chat_id_skips_fallback_notify(self):
        """send_response throws + user_chat_id=None → fallback не вызывается."""
        w = self._make()
        w.java_client.send_response = AsyncMock(side_effect=RuntimeError("api"))
        job = make_job(user_chat_id=None)
        await w._handle_unexpected_error(job, RuntimeError("x"))
        w.java_client.notify_user_failure.assert_not_called()

    async def test_no_java_client_skips_both_notifies(self):
        w = self._make()
        w.java_client = None
        job = make_job(user_chat_id=999)
        await w._handle_unexpected_error(job, RuntimeError("x"))
        w.queue_consumer.mark_job_failed.assert_awaited()


# ─────────────────────────────────────────────────────────────────────────────
# process_job — entry router (711-755)
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
class TestProcessJobRouter:

    def _make(self):
        w = _wire_common(_bare_worker())
        w._setup_processing = AsyncMock(return_value=False)
        w._verify_and_normalize_chat = AsyncMock()
        w._run_cache_aware_export = AsyncMock()
        w._send_completed_result = AsyncMock()
        w._cleanup_job = AsyncMock()
        w._handle_unexpected_error = AsyncMock()
        w.log_memory_usage = MagicMock()
        return w

    async def test_setup_returns_true_short_circuits(self):
        """_setup_processing=True → return True раньше."""
        w = self._make()
        w._setup_processing = AsyncMock(return_value=True)
        result = await w.process_job(make_job())
        assert result is True
        w._verify_and_normalize_chat.assert_not_called()

    async def test_verify_returns_false_short_circuits(self):
        w = self._make()
        w._verify_and_normalize_chat.return_value = (False, make_job(), None, None)
        result = await w.process_job(make_job())
        assert result is True
        w._run_cache_aware_export.assert_not_called()

    async def test_run_cache_aware_returns_none_short_circuits(self):
        w = self._make()
        job = make_job()
        w._verify_and_normalize_chat.return_value = (True, job, {"title": "x"}, None)
        w._run_cache_aware_export.return_value = None
        result = await w.process_job(job)
        assert result is True
        w._send_completed_result.assert_not_called()

    async def test_export_cancelled_path_clean_finalize(self):
        w = self._make()
        job = make_job()
        w._verify_and_normalize_chat.side_effect = ExportCancelled("x")
        result = await w.process_job(job)
        assert result is True
        w.queue_consumer.mark_job_completed.assert_awaited()
        w._cleanup_job.assert_awaited()

    async def test_export_cancelled_mark_completed_fails_swallowed(self):
        w = self._make()
        job = make_job()
        w._verify_and_normalize_chat.side_effect = ExportCancelled("x")
        w.queue_consumer.mark_job_completed = AsyncMock(side_effect=RuntimeError("r"))
        # не пробрасывает
        await w.process_job(job)

    async def test_export_cancelled_cleanup_fails_swallowed(self):
        w = self._make()
        job = make_job()
        w._verify_and_normalize_chat.side_effect = ExportCancelled("x")
        w._cleanup_job = AsyncMock(side_effect=RuntimeError("c"))
        await w.process_job(job)

    async def test_generic_exception_routes_to_handler(self):
        w = self._make()
        job = make_job()
        w._verify_and_normalize_chat.side_effect = RuntimeError("boom")
        result = await w.process_job(job)
        assert result is True
        w._handle_unexpected_error.assert_awaited_once()


# ─────────────────────────────────────────────────────────────────────────────
# JavaBotClient — send_response, sentinel retry, file split
# ─────────────────────────────────────────────────────────────────────────────

def _patch_jc_settings(**ov):
    p = patch("java_client.settings")
    m = p.start()
    m.JAVA_API_BASE_URL = ov.get("JAVA_API_BASE_URL", "http://localhost:8080")
    m.TELEGRAM_BOT_TOKEN = ov.get("TELEGRAM_BOT_TOKEN", "TOK")
    m.RETRY_BASE_DELAY = ov.get("RETRY_BASE_DELAY", 0.0)
    m.RETRY_MAX_DELAY = ov.get("RETRY_MAX_DELAY", 60.0)
    m.JAVA_API_KEY = ov.get("JAVA_API_KEY", "")
    return p, m


@pytest.mark.asyncio
class TestJavaClientSendResponse:

    async def test_failed_status_calls_notify_user_failure(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient(max_retries=1)
            jc._http_client = AsyncMock()
            jc.notify_user_failure = AsyncMock()
            payload = SendResponsePayload(
                task_id="t", status="failed", messages=[],
                error="X", user_chat_id=42,
            )
            result = await jc.send_response(payload)
            assert result is True
            jc.notify_user_failure.assert_awaited_once()
        finally:
            p.stop()

    async def test_actual_count_zero_aclose_async_iter_and_notify_empty(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient(max_retries=1)
            jc._http_client = AsyncMock()
            jc.notify_empty_export = AsyncMock()

            class _Iter:
                def __aiter__(self):
                    return self

                async def __anext__(self):
                    raise StopAsyncIteration

                async def aclose(self):
                    self.closed = True

            it = _Iter()
            payload = SendResponsePayload(
                task_id="t", status="completed", messages=it,
                actual_count=0, user_chat_id=42,
                from_date="2025-01-01", to_date="2025-01-31",
            )
            result = await jc.send_response(payload)
            assert result is True
            assert getattr(it, "closed", False) is True
            jc.notify_empty_export.assert_awaited_once()
        finally:
            p.stop()

    async def test_actual_count_zero_aclose_raises_is_swallowed(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient(max_retries=1)
            jc._http_client = AsyncMock()
            jc.notify_empty_export = AsyncMock()

            class _Iter:
                def __aiter__(self):
                    return self

                async def __anext__(self):
                    raise StopAsyncIteration

                async def aclose(self):
                    raise RuntimeError("boom")

            payload = SendResponsePayload(
                task_id="t", status="completed", messages=_Iter(),
                actual_count=0, user_chat_id=42,
            )
            result = await jc.send_response(payload)
            assert result is True
        finally:
            p.stop()


@pytest.mark.asyncio
class TestUploadSentinelAnd400:

    async def test_sentinel_missing_triggers_retry_then_returns_none(self, tmp_path):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient(max_retries=1)
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 200
            resp.text = "no sentinel here"
            jc._http_client.post.return_value = resp
            f = tmp_path / "result.json"
            f.write_bytes(b'{"x":1}')
            result = await jc._upload_file_to_java(file_path=str(f))
            assert result is None
            # max_retries=1 → first try + 1 retry = 2 calls total
            assert jc._http_client.post.await_count == 2
        finally:
            p.stop()

    async def test_sentinel_present_returns_clean_text(self, tmp_path):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient(max_retries=0)
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 200
            resp.text = "Hello world\n##OK##"
            jc._http_client.post.return_value = resp
            f = tmp_path / "result.json"
            f.write_bytes(b'{"x":1}')
            result = await jc._upload_file_to_java(file_path=str(f))
            assert result == "Hello world"
        finally:
            p.stop()

    async def test_400_no_retry_returns_none(self, tmp_path):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient(max_retries=3)
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 400
            resp.text = "Bad request"
            jc._http_client.post.return_value = resp
            f = tmp_path / "result.json"
            f.write_bytes(b'{"x":1}')
            result = await jc._upload_file_to_java(file_path=str(f))
            assert result is None
            # 400 → break immediately, без retry
            assert jc._http_client.post.await_count == 1
        finally:
            p.stop()


class TestSplitTextBySize:

    def test_split_below_threshold_returns_single_chunk(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            chunks = jc._split_text_by_size("a\nb\nc", 1024)
            assert chunks == ["a\nb\nc"]
        finally:
            p.stop()

    def test_split_above_threshold_creates_multiple(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            text = "x" * 100 + "\n" + "y" * 100 + "\n" + "z" * 100
            chunks = jc._split_text_by_size(text, 150)
            assert len(chunks) >= 2

        finally:
            p.stop()


@pytest.mark.asyncio
class TestVerifyConnectivityAndQueuePos:

    async def test_verify_connectivity_failure(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            jc._http_client.get.side_effect = RuntimeError("no route")
            ok = await jc.verify_connectivity()
            assert ok is False
        finally:
            p.stop()

    async def test_verify_connectivity_500_returns_false(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 500
            jc._http_client.get.return_value = resp
            ok = await jc.verify_connectivity()
            assert ok is False
        finally:
            p.stop()

    async def test_update_queue_position_zero(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            jc._http_client.post.return_value = MagicMock()
            await jc.update_queue_position(1, 100, 0, 5)
            args, kwargs = jc._http_client.post.call_args
            assert "начался" in kwargs["data"]["text"]
        finally:
            p.stop()

    async def test_update_queue_position_nonzero_shows_position(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            jc._http_client.post.return_value = MagicMock()
            await jc.update_queue_position(1, 100, 3, 7)
            args, kwargs = jc._http_client.post.call_args
            assert "3/7" in kwargs["data"]["text"]
        finally:
            p.stop()

    async def test_update_queue_position_post_throws_swallowed(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            jc._http_client.post.side_effect = RuntimeError("tg down")
            await jc.update_queue_position(1, 100, 0, 5)  # no raise
        finally:
            p.stop()

    async def test_update_queue_position_no_bot_token_skips(self):
        p, _ = _patch_jc_settings(TELEGRAM_BOT_TOKEN="")
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            await jc.update_queue_position(1, 100, 0, 5)
            jc._http_client.post.assert_not_called()
        finally:
            p.stop()


@pytest.mark.asyncio
class TestSendProgressUpdateEdges:

    async def test_edit_message_returns_message_id_on_200(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 200
            jc._http_client.post.return_value = resp
            mid = await jc.send_progress_update(
                user_chat_id=1, task_id="t", message_count=50,
                total=100, progress_message_id=999,
            )
            assert mid == 999
        finally:
            p.stop()

    async def test_edit_message_non_200_returns_none(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 400
            jc._http_client.post.return_value = resp
            mid = await jc.send_progress_update(
                user_chat_id=1, task_id="t", message_count=50,
                total=100, progress_message_id=999,
            )
            assert mid is None
        finally:
            p.stop()

    async def test_send_new_message_200_returns_new_id(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 200
            resp.json = MagicMock(return_value={"result": {"message_id": 555}})
            jc._http_client.post.return_value = resp
            mid = await jc.send_progress_update(
                user_chat_id=1, task_id="t", message_count=50, started=True,
            )
            assert mid == 555
        finally:
            p.stop()

    async def test_send_new_message_non_200_returns_none(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 500
            jc._http_client.post.return_value = resp
            mid = await jc.send_progress_update(
                user_chat_id=1, task_id="t", message_count=50, started=True,
            )
            assert mid is None
        finally:
            p.stop()

    async def test_exception_returns_none(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            jc._http_client.post.side_effect = RuntimeError("net")
            mid = await jc.send_progress_update(
                user_chat_id=1, task_id="t", message_count=10, total=100,
            )
            assert mid is None
        finally:
            p.stop()

    async def test_counting_branch(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 200
            resp.json = MagicMock(return_value={"result": {"message_id": 7}})
            jc._http_client.post.return_value = resp
            mid = await jc.send_progress_update(
                user_chat_id=1, task_id="t", message_count=0, counting=True,
            )
            assert mid == 7
            args, kwargs = jc._http_client.post.call_args
            assert "Определяю" in kwargs["data"]["text"]
        finally:
            p.stop()

    async def test_topic_name_added(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 200
            resp.json = MagicMock(return_value={"result": {"message_id": 9}})
            jc._http_client.post.return_value = resp
            await jc.send_progress_update(
                user_chat_id=1, task_id="t", message_count=10, total=100,
                topic_name="MyTopic",
            )
            args, kwargs = jc._http_client.post.call_args
            assert "MyTopic" in kwargs["data"]["text"]
        finally:
            p.stop()

    async def test_progress_with_eta(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 200
            jc._http_client.post.return_value = resp
            await jc.send_progress_update(
                user_chat_id=1, task_id="t", message_count=10, total=100,
                progress_message_id=42, eta_text="2 мин",
            )
            args, kwargs = jc._http_client.post.call_args
            assert "2 мин" in kwargs["data"]["text"]
        finally:
            p.stop()

    async def test_total_zero_shows_zero_pct(self):
        p, _ = _patch_jc_settings()
        try:
            jc = JavaBotClient()
            jc._http_client = AsyncMock()
            resp = MagicMock()
            resp.status_code = 200
            resp.json = MagicMock(return_value={"result": {"message_id": 1}})
            jc._http_client.post.return_value = resp
            await jc.send_progress_update(
                user_chat_id=1, task_id="t", message_count=0, total=0,
            )
            args, kwargs = jc._http_client.post.call_args
            assert "0%" in kwargs["data"]["text"]
        finally:
            p.stop()


# ─────────────────────────────────────────────────────────────────────────────
# ProgressTracker — seed, on_floodwait, set_total, finalize
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
class TestProgressTracker:

    async def test_seed_with_no_total_is_noop(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        await tr.seed(100)
        client.send_progress_update.assert_not_called()

    async def test_seed_with_total_and_count_updates(self):
        client = AsyncMock()
        client.send_progress_update = AsyncMock(return_value=42)
        tr = ProgressTracker(client, 1, "t")
        tr._total = 1000
        await tr.seed(500)
        assert tr._message_id == 42
        assert tr._baseline_count == 500

    async def test_seed_zero_count_is_noop(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        tr._total = 100
        await tr.seed(0)
        client.send_progress_update.assert_not_called()

    async def test_on_floodwait_no_message_id_noop(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        await tr.on_floodwait(10)
        client.send_progress_update.assert_not_called()

    async def test_on_floodwait_with_message_id_calls(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        tr._message_id = 5
        tr._total = 100
        tr._last_count = 50
        await tr.on_floodwait(10)
        client.send_progress_update.assert_awaited_once()

    async def test_counting_no_message_id_noop(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        await tr.counting()
        client.send_progress_update.assert_not_called()

    async def test_set_total_zero_updates_to_started_state(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        tr._message_id = 5
        await tr.set_total(0)
        client.send_progress_update.assert_awaited_once()

    async def test_set_total_zero_without_message_id_noop(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        await tr.set_total(0)
        client.send_progress_update.assert_not_called()

    async def test_track_no_total_returns_early(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        tr._total = None
        await tr.track(50)
        client.send_progress_update.assert_not_called()

    async def test_track_at_100pct_no_emit(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        tr._total = 100
        await tr.track(100)
        client.send_progress_update.assert_not_called()

    async def test_finalize_emits_full(self):
        client = AsyncMock()
        tr = ProgressTracker(client, 1, "t")
        tr._total = 100
        await tr.finalize(95)
        client.send_progress_update.assert_awaited_once()


# ─────────────────────────────────────────────────────────────────────────────
# queue_consumer — paths 60-62, 82-85, 158-166, 271-273, 291-293, 302-309, 343, 384
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
class TestQueueConsumerEdges:

    def _make(self):
        from queue_consumer import QueueConsumer
        qc = QueueConsumer.__new__(QueueConsumer)
        qc.redis_client = AsyncMock()
        qc.express_queue_name = "exp"
        qc.queue_name = "main"
        qc.subscription_queue_name = "sub"
        qc.staging_express_name = "stg_exp"
        qc.staging_name = "stg_main"
        qc.staging_subscription_name = "stg_sub"
        return qc

    async def test_mark_job_processing_redis_exception_returns_false(self):
        qc = self._make()
        qc.redis_client.setex = AsyncMock(side_effect=RuntimeError("r"))
        result = await qc.mark_job_processing("t1")
        assert result is False

    async def test_mark_job_processing_no_redis_returns_false(self):
        qc = self._make()
        qc.redis_client = None
        result = await qc.mark_job_processing("t1")
        assert result is False

    async def test_finalize_already_claimed_returns_false(self):
        qc = self._make()
        qc.redis_client.set = AsyncMock(return_value=None)  # not claimed
        result = await qc._finalize_job("t1", "k", "v")
        assert result is False

    async def test_finalize_redis_exception_returns_false(self):
        qc = self._make()
        qc.redis_client.set = AsyncMock(side_effect=RuntimeError("r"))
        result = await qc._finalize_job("t1", "k", "v")
        assert result is False

    async def test_finalize_malformed_staging_meta_swallowed(self):
        qc = self._make()
        qc.redis_client.set = AsyncMock(return_value=True)
        qc.redis_client.get = AsyncMock(return_value="not-json")
        pipe = AsyncMock()
        pipe.delete = MagicMock()
        pipe.srem = MagicMock()
        pipe.lrem = MagicMock()
        pipe.execute = AsyncMock()
        qc.redis_client.pipeline = MagicMock(return_value=pipe)
        result = await qc._finalize_job("t1", "k", "v")
        assert result is True

    async def test_finalize_valid_staging_meta_calls_lrem(self):
        qc = self._make()
        qc.redis_client.set = AsyncMock(return_value=True)
        qc.redis_client.get = AsyncMock(return_value=json.dumps(
            {"payload": "{}", "queue": "stg_main"}
        ))
        pipe = AsyncMock()
        pipe.delete = MagicMock()
        pipe.srem = MagicMock()
        pipe.lrem = MagicMock()
        pipe.execute = AsyncMock()
        qc.redis_client.pipeline = MagicMock(return_value=pipe)
        result = await qc._finalize_job("t1", "k", "v")
        assert result is True
        pipe.lrem.assert_called_once_with("stg_main", 1, "{}")

    async def test_finalize_no_redis_returns_false(self):
        qc = self._make()
        qc.redis_client = None
        result = await qc._finalize_job("t1", "k", "v")
        assert result is False

    async def test_publish_completed_event_with_subscription(self):
        qc = self._make()
        qc.redis_client.xadd = AsyncMock()
        with patch("queue_consumer.settings") as s:
            s.STATS_STREAM_KEY = "stats:events"
            await qc._publish_completed_event("t1", bot_user_id=42, subscription_id=7)
        qc.redis_client.xadd.assert_awaited_once()

    async def test_publish_completed_event_exception_swallowed(self):
        qc = self._make()
        qc.redis_client.xadd = AsyncMock(side_effect=RuntimeError("r"))
        with patch("queue_consumer.settings") as s:
            s.STATS_STREAM_KEY = "stats:events"
            await qc._publish_completed_event("t1", bot_user_id=1)

    async def test_publish_failed_event_with_all_fields(self):
        qc = self._make()
        qc.redis_client.xadd = AsyncMock()
        with patch("queue_consumer.settings") as s:
            s.STATS_STREAM_KEY = "stats:events"
            await qc._publish_failed_event("t1", "err", subscription_id=5, bot_user_id=9)
        qc.redis_client.xadd.assert_awaited_once()

    async def test_recover_staging_jobs_exception_swallowed(self):
        qc = self._make()
        qc.redis_client.lmove = AsyncMock(side_effect=RuntimeError("r"))
        n = await qc.recover_staging_jobs()
        assert n == 0

    async def test_recover_staging_jobs_no_redis_returns_zero(self):
        qc = self._make()
        qc.redis_client = None
        n = await qc.recover_staging_jobs()
        assert n == 0

    async def test_recover_staging_jobs_moves_items_from_all_queues(self):
        qc = self._make()
        # 1 express, 1 main, 1 subscription, тогда delete staging:jobs
        lmove_results = ["a", None, "b", None, "c", None]
        qc.redis_client.lmove = AsyncMock(side_effect=lmove_results)
        qc.redis_client.delete = AsyncMock()
        n = await qc.recover_staging_jobs()
        assert n == 3

    async def test_get_pending_jobs_no_redis(self):
        qc = self._make()
        qc.redis_client = None
        result = await qc.get_pending_jobs()
        assert result == {"jobs": [], "total_count": 0}

    async def test_get_pending_jobs_exception(self):
        qc = self._make()
        qc.redis_client.llen = AsyncMock(side_effect=RuntimeError("r"))
        result = await qc.get_pending_jobs()
        assert result == {"jobs": [], "total_count": 0}

    async def test_get_pending_jobs_malformed_express_item_skipped(self):
        qc = self._make()
        qc.redis_client.llen = AsyncMock(side_effect=[1, 0, 0])
        qc.redis_client.lrange = AsyncMock(return_value=["not-json"])
        result = await qc.get_pending_jobs()
        assert result["jobs"] == []
        assert result["total_count"] == 1

    async def test_get_queue_stats_exception(self):
        qc = self._make()
        qc.redis_client.llen = AsyncMock(side_effect=RuntimeError("r"))
        result = await qc.get_queue_stats()
        assert result is None

    async def test_get_queue_stats_no_redis(self):
        qc = self._make()
        qc.redis_client = None
        result = await qc.get_queue_stats()
        assert result is None

    async def test_disconnect_exception_swallowed(self):
        qc = self._make()
        qc.redis_client.close = AsyncMock(side_effect=RuntimeError("r"))
        await qc.disconnect()

    async def test_disconnect_no_client(self):
        qc = self._make()
        qc.redis_client = None
        await qc.disconnect()

    async def test_push_job_no_redis_raises(self):
        qc = self._make()
        qc.redis_client = None
        with pytest.raises(RuntimeError):
            await qc.push_job(make_job())

    async def test_push_job_redis_exception_returns_false(self):
        qc = self._make()
        qc.redis_client.rpush = AsyncMock(side_effect=RuntimeError("r"))
        result = await qc.push_job(make_job())
        assert result is False

    async def test_track_staging_job_exception_swallowed(self):
        qc = self._make()
        qc.redis_client.sadd = AsyncMock(side_effect=RuntimeError("r"))
        await qc._track_staging_job("t1")

    async def test_untrack_staging_job_exception_swallowed(self):
        qc = self._make()
        qc.redis_client.srem = AsyncMock(side_effect=RuntimeError("r"))
        await qc._untrack_staging_job("t1")

    async def test_store_staging_payload_exception_swallowed(self):
        qc = self._make()
        qc.redis_client.setex = AsyncMock(side_effect=RuntimeError("r"))
        await qc._store_staging_payload("t1", "{}", "q")

    async def test_remove_from_staging_exception_swallowed(self):
        qc = self._make()
        qc.redis_client.get = AsyncMock(side_effect=RuntimeError("r"))
        await qc._remove_from_staging("t1")

    async def test_remove_from_staging_no_redis(self):
        qc = self._make()
        qc.redis_client = None
        await qc._remove_from_staging("t1")

    async def test_move_to_dlq_publishes_failed_event_when_task_id_parseable(self):
        qc = self._make()
        qc.redis_client.rpush = AsyncMock()
        qc.redis_client.xadd = AsyncMock()
        with patch("queue_consumer.settings") as s:
            s.STATS_STREAM_KEY = "stats:events"
            await qc._move_to_dlq(json.dumps({"task_id": "t1"}), "bad")
        qc.redis_client.rpush.assert_awaited()
        qc.redis_client.xadd.assert_awaited()

    async def test_move_to_dlq_unparseable_swallows_xadd(self):
        qc = self._make()
        qc.redis_client.rpush = AsyncMock()
        await qc._move_to_dlq("not-json", "bad")
        qc.redis_client.rpush.assert_awaited()

    async def test_move_to_dlq_rpush_exception_swallowed(self):
        qc = self._make()
        qc.redis_client.rpush = AsyncMock(side_effect=RuntimeError("r"))
        await qc._move_to_dlq(json.dumps({"task_id": "t1"}), "bad")

    async def test_move_to_dlq_no_redis(self):
        qc = self._make()
        qc.redis_client = None
        await qc._move_to_dlq("{}", "bad")
