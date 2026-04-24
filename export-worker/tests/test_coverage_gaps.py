"""
Целенаправленные тесты для непокрытых участков production-кода:
- main._compute_cached_ranges (pure function)
- main._notify_queue_position / _update_all_queue_positions
- main.log_memory_usage exception path
- main.process_job error_reason → user-facing error mapping
- main.process_job ExportCancelled / unexpected exception handlers
- queue_consumer DLQ paths (JSON parse / validation errors)
- queue_consumer._reconnect exponential backoff
- pyrogram_client.verify_and_get_info error branches
- pyrogram_client.cancellable_floodwait_sleep callback error tolerance

Эти сценарии покрывают реальное поведение (не моки моков):
user-visible error codes, durability recovery, rate-limit backoff.
"""
import asyncio
import json

import pytest
from unittest.mock import AsyncMock, MagicMock, patch

import redis.asyncio as aioredis
from pyrogram.errors import (
    BadRequest, ChannelPrivate, ChatAdminRequired, PeerFlood, PeerIdInvalid,
    Unauthorized, UserDeactivated, AuthKeyUnregistered, SessionExpired,
)

from main import ExportWorker
from java_client import ProgressTracker
from models import ExportRequest
from queue_consumer import QueueConsumer
from pyrogram_client import (
    TelegramClient, cancellable_floodwait_sleep, ensure_utc, ExportCancelled,
)


# ─────────────────────────────────────────────────────────────────────────────
# main._compute_cached_ranges — pure function, inverts "missing" → "cached"
# ─────────────────────────────────────────────────────────────────────────────

class TestComputeCachedRanges:

    def test_no_missing_returns_full_range(self):
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-10", missing=[]
        )
        assert result == [("2025-01-01", "2025-01-10")]

    def test_gap_in_middle_splits_cached(self):
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-10",
            missing=[("2025-01-04", "2025-01-06")],
        )
        assert result == [
            ("2025-01-01", "2025-01-03"),
            ("2025-01-07", "2025-01-10"),
        ]

    def test_gap_at_start_no_left_cached(self):
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-10",
            missing=[("2025-01-01", "2025-01-03")],
        )
        assert result == [("2025-01-04", "2025-01-10")]

    def test_gap_at_end_no_right_cached(self):
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-10",
            missing=[("2025-01-08", "2025-01-10")],
        )
        assert result == [("2025-01-01", "2025-01-07")]

    def test_entire_range_missing(self):
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-10",
            missing=[("2025-01-01", "2025-01-10")],
        )
        assert result == []

    def test_multiple_gaps_handled_in_order(self):
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-20",
            missing=[
                ("2025-01-05", "2025-01-06"),
                ("2025-01-12", "2025-01-14"),
            ],
        )
        assert result == [
            ("2025-01-01", "2025-01-04"),
            ("2025-01-07", "2025-01-11"),
            ("2025-01-15", "2025-01-20"),
        ]

    def test_unsorted_missing_still_sorted_internally(self):
        # Sorted() call внутри функции должен обработать неупорядоченный вход
        result = ExportWorker._compute_cached_ranges(
            "2025-01-01", "2025-01-20",
            missing=[
                ("2025-01-12", "2025-01-14"),
                ("2025-01-05", "2025-01-06"),
            ],
        )
        assert result == [
            ("2025-01-01", "2025-01-04"),
            ("2025-01-07", "2025-01-11"),
            ("2025-01-15", "2025-01-20"),
        ]


# ─────────────────────────────────────────────────────────────────────────────
# main._notify_queue_position — чтение queue_msg и отправка в Java
# ─────────────────────────────────────────────────────────────────────────────

class TestNotifyQueuePosition:

    @pytest.fixture
    def worker(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.java_client = AsyncMock()
        w.java_client.update_queue_position = AsyncMock()
        return w

    @pytest.mark.asyncio
    async def test_no_control_redis_returns_silently(self):
        w = ExportWorker()
        w.control_redis = None
        w.java_client = AsyncMock()
        # Не должен упасть
        await w._notify_queue_position("task1", 3, 10)
        w.java_client.update_queue_position.assert_not_called()

    @pytest.mark.asyncio
    async def test_no_java_client_returns_silently(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.java_client = None
        await w._notify_queue_position("task1", 3, 10)

    @pytest.mark.asyncio
    async def test_missing_queue_msg_skips(self, worker):
        worker.control_redis.get = AsyncMock(return_value=None)
        await worker._notify_queue_position("task1", 3, 10)
        worker.java_client.update_queue_position.assert_not_called()

    @pytest.mark.asyncio
    async def test_happy_path_sends_update(self, worker):
        worker.control_redis.get = AsyncMock(return_value="123:456")
        await worker._notify_queue_position("task1", 3, 10)
        worker.java_client.update_queue_position.assert_called_once_with(
            123, 456, 3, 10
        )

    @pytest.mark.asyncio
    async def test_malformed_queue_msg_no_colon_skips(self, worker):
        # Строка без ':' → split вернёт [raw], unpacking сломается → ValueError
        worker.control_redis.get = AsyncMock(return_value="notanint")
        await worker._notify_queue_position("task1", 3, 10)
        worker.java_client.update_queue_position.assert_not_called()

    @pytest.mark.asyncio
    async def test_malformed_queue_msg_non_numeric_skips(self, worker):
        worker.control_redis.get = AsyncMock(return_value="abc:def")
        await worker._notify_queue_position("task1", 3, 10)
        worker.java_client.update_queue_position.assert_not_called()


# ─────────────────────────────────────────────────────────────────────────────
# main._update_all_queue_positions
# ─────────────────────────────────────────────────────────────────────────────

class TestUpdateAllQueuePositions:

    @pytest.mark.asyncio
    async def test_no_redis_returns_silently(self):
        w = ExportWorker()
        w.control_redis = None
        w.java_client = AsyncMock()
        w.queue_consumer = AsyncMock()
        await w._update_all_queue_positions("task_current")

    @pytest.mark.asyncio
    async def test_empty_pending_only_updates_current(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value="111:222")
        w.java_client = AsyncMock()
        w.java_client.update_queue_position = AsyncMock()
        w.queue_consumer = AsyncMock()
        w.queue_consumer.get_pending_jobs = AsyncMock(
            return_value={"jobs": [], "total_count": 0}
        )

        await w._update_all_queue_positions("task_current")
        # Единственный вызов — для current task (position=0)
        w.java_client.update_queue_position.assert_called_once_with(111, 222, 0, 0)

    @pytest.mark.asyncio
    async def test_pending_jobs_notified_with_positions(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        # Для каждого task_id возвращаем уникальный user:msg
        async def _get(key: str):
            mapping = {
                "queue_msg:task_current": "100:200",
                "queue_msg:task_a": "101:201",
                "queue_msg:task_b": "102:202",
            }
            return mapping.get(key)
        w.control_redis.get = AsyncMock(side_effect=_get)
        w.java_client = AsyncMock()
        w.java_client.update_queue_position = AsyncMock()
        w.queue_consumer = AsyncMock()

        job_a = ExportRequest(task_id="task_a", user_id=1, chat_id=1, user_chat_id=1, limit=0)
        job_b = ExportRequest(task_id="task_b", user_id=2, chat_id=2, user_chat_id=2, limit=0)
        w.queue_consumer.get_pending_jobs = AsyncMock(
            return_value={"jobs": [job_a, job_b], "total_count": 2}
        )

        await w._update_all_queue_positions("task_current")
        calls = w.java_client.update_queue_position.call_args_list
        # Должно быть 3 вызова (current + 2 pending)
        assert len(calls) == 3

    @pytest.mark.asyncio
    async def test_exception_in_get_pending_is_swallowed(self):
        w = ExportWorker()
        w.control_redis = AsyncMock()
        w.java_client = AsyncMock()
        w.queue_consumer = AsyncMock()
        w.queue_consumer.get_pending_jobs = AsyncMock(
            side_effect=RuntimeError("Redis down")
        )
        # Не должен пробросить — функция best-effort
        await w._update_all_queue_positions("task_current")


# ─────────────────────────────────────────────────────────────────────────────
# main.log_memory_usage — best-effort psutil wrapper
# ─────────────────────────────────────────────────────────────────────────────

class TestLogMemoryUsageExceptions:

    def test_psutil_exception_does_not_raise(self):
        w = ExportWorker()
        with patch("main.psutil.virtual_memory", side_effect=OSError("proc read fail")):
            # Должно быть поймано и залогировано через logger.warning
            w.log_memory_usage("TEST_STAGE")


# ─────────────────────────────────────────────────────────────────────────────
# main.process_job error_reason → user-facing error mapping
# Проверяем что каждый reason из verify_and_get_info корректно преобразуется
# в text + error_code.
# ─────────────────────────────────────────────────────────────────────────────

class TestProcessJobErrorMapping:

    @pytest.fixture
    def worker(self):
        w = ExportWorker()
        w.queue_consumer = AsyncMock()
        w.telegram_client = AsyncMock()
        w.java_client = AsyncMock()
        w.java_client.send_response = AsyncMock(return_value=True)
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=None)
        w.control_redis.set = AsyncMock()
        w.control_redis.delete = AsyncMock()
        w.message_cache = None
        return w

    @pytest.mark.parametrize("reason,expected_fragment,expected_code", [
        ("CHANNEL_PRIVATE", "приватный", "CHANNEL_PRIVATE"),
        ("USERNAME_NOT_FOUND", "не найден", "USERNAME_NOT_FOUND"),
        ("ADMIN_REQUIRED", "администратор", "ADMIN_REQUIRED"),
        ("CHAT_NOT_ACCESSIBLE", "Нет доступа", "CHAT_NOT_ACCESSIBLE"),
        ("SESSION_INVALID", "Сессия", "SESSION_INVALID"),
        ("FLOOD_RESTRICTED", "flood", "FLOOD_RESTRICTED"),
        ("UNKNOWN", "Не удалось получить доступ", "UNKNOWN"),
    ])
    @pytest.mark.asyncio
    async def test_error_reason_maps_to_user_text(
        self, worker, reason, expected_fragment, expected_code
    ):
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(False, None, reason)
        )
        job = ExportRequest(
            task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0
        )
        worker.queue_consumer.mark_job_processing = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_failed = AsyncMock(return_value=True)

        result = await worker.process_job(job)
        assert result is True

        # send_response должен быть вызван с нужными error/error_code
        payload = worker.java_client.send_response.call_args[0][0]
        assert payload.status == "failed"
        assert expected_fragment.lower() in payload.error.lower()
        assert payload.error_code == expected_code

    @pytest.mark.asyncio
    async def test_unknown_reason_falls_back_to_generic_text(self, worker):
        # Если verify_and_get_info вернул reason, которого нет в mapping
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(False, None, "SOMETHING_NEW_FROM_PYROGRAM")
        )
        job = ExportRequest(
            task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0
        )
        worker.queue_consumer.mark_job_processing = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_failed = AsyncMock(return_value=True)

        result = await worker.process_job(job)
        assert result is True
        payload = worker.java_client.send_response.call_args[0][0]
        assert "No access" in payload.error
        # error_code = reason когда он есть, даже если нет в mapping
        assert payload.error_code == "SOMETHING_NEW_FROM_PYROGRAM"


# ─────────────────────────────────────────────────────────────────────────────
# main.process_job — unexpected exception handler
# Проверяем fallback на notify_user_failure если send_response упал.
# ─────────────────────────────────────────────────────────────────────────────

class TestProcessJobUnexpectedError:

    @pytest.fixture
    def worker(self):
        w = ExportWorker()
        w.queue_consumer = AsyncMock()
        w.queue_consumer.mark_job_processing = AsyncMock(return_value=True)
        w.queue_consumer.mark_job_failed = AsyncMock(return_value=True)
        w.telegram_client = AsyncMock()
        w.java_client = AsyncMock()
        w.control_redis = AsyncMock()
        w.control_redis.get = AsyncMock(return_value=None)
        w.control_redis.set = AsyncMock()
        w.control_redis.delete = AsyncMock()
        w.message_cache = None
        return w

    @pytest.mark.asyncio
    async def test_send_response_succeeds_no_fallback(self, worker):
        worker.telegram_client.verify_and_get_info = AsyncMock(
            side_effect=RuntimeError("boom in verify")
        )
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.java_client.notify_user_failure = AsyncMock()

        job = ExportRequest(task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0)
        result = await worker.process_job(job)

        assert result is True
        worker.java_client.send_response.assert_called_once()
        worker.java_client.notify_user_failure.assert_not_called()

    @pytest.mark.asyncio
    async def test_send_response_fails_falls_back_to_notify(self, worker):
        worker.telegram_client.verify_and_get_info = AsyncMock(
            side_effect=RuntimeError("boom in verify")
        )
        worker.java_client.send_response = AsyncMock(
            side_effect=RuntimeError("send_response OOM")
        )
        worker.java_client.notify_user_failure = AsyncMock()

        job = ExportRequest(task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0)
        result = await worker.process_job(job)

        assert result is True
        worker.java_client.notify_user_failure.assert_called_once()

    @pytest.mark.asyncio
    async def test_both_notification_paths_fail_still_completes(self, worker):
        # Даже если ВСЁ упало — process_job возвращает True (не кидает наверх)
        worker.telegram_client.verify_and_get_info = AsyncMock(
            side_effect=RuntimeError("boom")
        )
        worker.java_client.send_response = AsyncMock(
            side_effect=RuntimeError("send fail")
        )
        worker.java_client.notify_user_failure = AsyncMock(
            side_effect=RuntimeError("notify fail")
        )
        worker.queue_consumer.mark_job_failed = AsyncMock(
            side_effect=RuntimeError("mark fail")
        )

        job = ExportRequest(task_id="t1", user_id=1, chat_id=1, user_chat_id=1, limit=0)
        result = await worker.process_job(job)
        # Даже катастрофический сценарий не роняет worker
        assert result is True


# ─────────────────────────────────────────────────────────────────────────────
# queue_consumer DLQ paths
# ─────────────────────────────────────────────────────────────────────────────

class TestQueueConsumerDLQ:

    @pytest.fixture
    def consumer(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        return c

    @pytest.mark.asyncio
    async def test_invalid_json_moved_to_dlq(self, consumer):
        # blmove возвращает broken JSON
        consumer.redis_client.blmove = AsyncMock(
            side_effect=[None, "{not-json"]
        )
        consumer.redis_client.lrem = AsyncMock()
        consumer.redis_client.rpush = AsyncMock()

        result = await consumer.get_job()
        assert result is None
        # DLQ: rpush на <queue>_dead
        dlq_call = consumer.redis_client.rpush.call_args
        assert dlq_call[0][0].endswith("_dead")
        # LREM удаляет конкретный payload из staging (не LPOP!)
        consumer.redis_client.lrem.assert_called_once()

    @pytest.mark.asyncio
    async def test_invalid_schema_moved_to_dlq(self, consumer):
        # JSON валидный, но поля не соответствуют ExportRequest
        bad_json = json.dumps({"random_field": "no task_id here"})
        consumer.redis_client.blmove = AsyncMock(
            side_effect=[None, bad_json]
        )
        consumer.redis_client.lrem = AsyncMock()
        consumer.redis_client.rpush = AsyncMock()

        result = await consumer.get_job()
        assert result is None
        dlq_call = consumer.redis_client.rpush.call_args
        assert dlq_call[0][0].endswith("_dead")

    @pytest.mark.asyncio
    async def test_move_to_dlq_swallows_errors(self, consumer):
        # Если сам DLQ упал — не должно пробросить
        consumer.redis_client.rpush = AsyncMock(side_effect=RuntimeError("boom"))
        await consumer._move_to_dlq('{"x": 1}', "test reason")


# ─────────────────────────────────────────────────────────────────────────────
# queue_consumer._reconnect — exponential backoff
# ─────────────────────────────────────────────────────────────────────────────

class TestQueueConsumerReconnect:

    @pytest.mark.asyncio
    async def test_reconnect_succeeds_on_first_attempt(self):
        c = QueueConsumer()
        c.redis_client = AsyncMock()
        c.redis_client.close = AsyncMock()

        with patch.object(c, "connect", AsyncMock(return_value=True)):
            with patch("queue_consumer.asyncio.sleep", AsyncMock()) as sleep_mock:
                result = await c._reconnect(max_retries=3)
                assert result is True
                # backoff sleep 1s на первой попытке
                sleep_mock.assert_called_once_with(1)

    @pytest.mark.asyncio
    async def test_reconnect_fails_all_attempts(self):
        c = QueueConsumer()
        c.redis_client = None  # Уже отвалился

        with patch.object(c, "connect", AsyncMock(return_value=False)):
            with patch("queue_consumer.asyncio.sleep", AsyncMock()) as sleep_mock:
                result = await c._reconnect(max_retries=3)
                assert result is False
                # Backoff: 1s, 2s, 4s
                waits = [call.args[0] for call in sleep_mock.call_args_list]
                assert waits == [1, 2, 4]

    @pytest.mark.asyncio
    async def test_reconnect_succeeds_on_second_attempt(self):
        c = QueueConsumer()
        c.redis_client = None

        connect_mock = AsyncMock(side_effect=[False, True])
        with patch.object(c, "connect", connect_mock):
            with patch("queue_consumer.asyncio.sleep", AsyncMock()):
                result = await c._reconnect(max_retries=3)
                assert result is True
                assert connect_mock.call_count == 2


# ─────────────────────────────────────────────────────────────────────────────
# pyrogram_client.verify_and_get_info — error branches mapping
# Каждая Pyrogram-ошибка → конкретный error_reason
# ─────────────────────────────────────────────────────────────────────────────

def _make_telegram_client():
    """Клиент с mocked Pyrogram Client (не подключается к сети)."""
    tc = TelegramClient.__new__(TelegramClient)
    tc.client = AsyncMock()
    tc.is_connected = True
    tc.redis_client = None
    tc._topic_name_cache = {}
    tc._TOPIC_NAME_CACHE_MAX = 500
    return tc


class TestVerifyAndGetInfoMapping:

    @pytest.mark.asyncio
    async def test_not_connected_returns_unknown(self):
        tc = _make_telegram_client()
        tc.is_connected = False
        ok, info, reason = await tc.verify_and_get_info(123)
        assert ok is False
        assert reason == "UNKNOWN"

    @pytest.mark.asyncio
    async def test_channel_private_mapped(self):
        tc = _make_telegram_client()
        tc.client.get_chat = AsyncMock(
            side_effect=ChannelPrivate("CHANNEL_PRIVATE")
        )
        ok, info, reason = await tc.verify_and_get_info(123)
        assert ok is False
        assert reason == "CHANNEL_PRIVATE"

    @pytest.mark.asyncio
    async def test_chat_admin_required_mapped(self):
        tc = _make_telegram_client()
        tc.client.get_chat = AsyncMock(
            side_effect=ChatAdminRequired("CHAT_ADMIN_REQUIRED")
        )
        ok, info, reason = await tc.verify_and_get_info(123)
        assert ok is False
        assert reason == "ADMIN_REQUIRED"

    @pytest.mark.asyncio
    async def test_session_invalid_mapped_on_unauthorized(self):
        tc = _make_telegram_client()
        tc.client.get_chat = AsyncMock(
            side_effect=Unauthorized("AUTH_KEY_UNREGISTERED")
        )
        ok, info, reason = await tc.verify_and_get_info(123)
        assert reason == "SESSION_INVALID"

    @pytest.mark.asyncio
    async def test_session_invalid_mapped_on_session_expired(self):
        tc = _make_telegram_client()
        tc.client.get_chat = AsyncMock(
            side_effect=SessionExpired("SESSION_EXPIRED")
        )
        ok, info, reason = await tc.verify_and_get_info(123)
        assert reason == "SESSION_INVALID"

    @pytest.mark.asyncio
    async def test_flood_restricted_mapped(self):
        tc = _make_telegram_client()
        tc.client.get_chat = AsyncMock(
            side_effect=PeerFlood("PEER_FLOOD")
        )
        ok, info, reason = await tc.verify_and_get_info(123)
        assert reason == "FLOOD_RESTRICTED"

    @pytest.mark.asyncio
    async def test_username_not_found_on_bad_request(self):
        tc = _make_telegram_client()
        err = BadRequest("USERNAME_NOT_OCCUPIED")
        tc.client.get_chat = AsyncMock(side_effect=err)
        ok, info, reason = await tc.verify_and_get_info("@badusername")
        assert reason == "USERNAME_NOT_FOUND"

    @pytest.mark.asyncio
    async def test_generic_exception_mapped_to_unknown(self):
        tc = _make_telegram_client()
        tc.client.get_chat = AsyncMock(
            side_effect=RuntimeError("totally unexpected")
        )
        ok, info, reason = await tc.verify_and_get_info("@some")
        assert reason == "UNKNOWN"

    @pytest.mark.asyncio
    async def test_happy_path_returns_chat_info(self):
        tc = _make_telegram_client()
        chat = MagicMock()
        chat.id = 777
        chat.title = "Test Chat"
        chat.username = "testchat"
        chat.type = "supergroup"
        chat.is_bot = False
        chat.is_self = False
        chat.is_contact = False
        chat.members_count = 42
        chat.description = "desc"
        tc.client.get_chat = AsyncMock(return_value=chat)

        ok, info, reason = await tc.verify_and_get_info("@testchat")
        assert ok is True
        assert reason is None
        assert info["id"] == 777
        assert info["title"] == "Test Chat"
        assert info["username"] == "testchat"


# ─────────────────────────────────────────────────────────────────────────────
# pyrogram_client.cancellable_floodwait_sleep — callback error tolerance
# ─────────────────────────────────────────────────────────────────────────────

class TestCancellableFloodwaitSleep:

    @pytest.mark.asyncio
    async def test_callback_exception_does_not_break_sleep(self):
        broken_callback = AsyncMock(side_effect=RuntimeError("callback boom"))
        # Должен отработать несмотря на исключение в callback
        await cancellable_floodwait_sleep(
            wait_time=2.0,
            on_floodwait=broken_callback,
            tick_seconds=1.0,
            progress_interval=1.0,
        )

    @pytest.mark.asyncio
    async def test_cancellation_raises_export_cancelled(self):
        cancel_count = 0

        async def _is_cancelled():
            nonlocal cancel_count
            cancel_count += 1
            # Отменить после первой проверки
            return cancel_count >= 1

        with pytest.raises(ExportCancelled):
            await cancellable_floodwait_sleep(
                wait_time=10.0,
                is_cancelled_fn=_is_cancelled,
                tick_seconds=0.1,
            )

    @pytest.mark.asyncio
    async def test_zero_wait_time_returns_immediately(self):
        await cancellable_floodwait_sleep(wait_time=0.0)

    @pytest.mark.asyncio
    async def test_no_callbacks_plain_sleep(self):
        # Нет callbacks → просто sleep
        await cancellable_floodwait_sleep(
            wait_time=0.2,
            tick_seconds=0.1,
        )


# ─────────────────────────────────────────────────────────────────────────────
# pyrogram_client.ensure_utc — edge cases
# ─────────────────────────────────────────────────────────────────────────────

class TestEnsureUtc:

    def test_none_returns_none(self):
        assert ensure_utc(None) is None

    def test_naive_datetime_becomes_utc(self):
        from datetime import datetime, timezone
        naive = datetime(2025, 1, 1, 12, 0, 0)
        assert ensure_utc(naive).tzinfo == timezone.utc

    def test_aware_datetime_passes_through(self):
        from datetime import datetime, timezone, timedelta
        aware = datetime(2025, 1, 1, 12, 0, 0, tzinfo=timezone(timedelta(hours=3)))
        result = ensure_utc(aware)
        assert result.tzinfo == timezone(timedelta(hours=3))

    def test_java_iso_local_parsed_as_utc_without_shift(self):
        """Контракт с Java SubscriptionScheduler.enqueueOne: fromIso/toIso
        отправляются как ISO-local без offset, в UTC. Python fromisoformat
        → ensure_utc должен считать naive как UTC (без сдвига часов).

        Если Java снова отправит МСК (как было в проде до 2026-04-24),
        этот тест не завалится — он проверяет Python-сторону контракта.
        Контракт на Java-стороне покрыт SubscriptionSchedulerTest.enqueuedDateRangeIsUtc.
        """
        from datetime import datetime, timezone
        java_iso = "2026-04-24T05:00:00"
        parsed = datetime.fromisoformat(java_iso)
        assert parsed.tzinfo is None
        utc_dt = ensure_utc(parsed)
        assert utc_dt.tzinfo == timezone.utc
        assert utc_dt.hour == 5
        assert utc_dt.year == 2026 and utc_dt.month == 4 and utc_dt.day == 24
