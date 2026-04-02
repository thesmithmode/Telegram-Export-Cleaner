"""
Tests for ExportWorker main class.

Covers job processing, error handling, and component lifecycle.
"""

import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch, call
from datetime import datetime

from main import ExportWorker
from java_client import ProgressTracker
from message_cache import MessageCache
from models import ExportRequest, ExportedMessage


def _make_mock_java_client():
    """Create AsyncMock java_client with working ProgressTracker support."""
    client = AsyncMock()
    client.send_progress_update = AsyncMock(return_value=12345)
    client.create_progress_tracker = lambda uid, tid: ProgressTracker(client, uid, tid)
    return client


class TestExportWorkerInitialization:
    """Test worker initialization."""

    @pytest.mark.asyncio
    async def test_initialize_success(self):
        """Test successful initialization of all components."""
        worker = ExportWorker()

        # Mock the component creation
        with patch('main.create_queue_consumer') as mock_queue_creator, \
             patch('main.create_telegram_client') as mock_telegram_creator, \
             patch('main.create_java_client') as mock_java_creator:

            mock_queue = AsyncMock()
            mock_queue.connect = AsyncMock(return_value=True)
            mock_queue_creator.return_value = mock_queue

            mock_telegram = AsyncMock()
            mock_telegram.connect = AsyncMock(return_value=True)
            mock_telegram_creator.return_value = mock_telegram

            mock_java = AsyncMock()
            mock_java_creator.return_value = mock_java

            result = await worker.initialize()

            assert result is True
            assert worker.queue_consumer is not None
            assert worker.telegram_client is not None
            assert worker.java_client is not None

    @pytest.mark.asyncio
    async def test_initialize_queue_failure(self):
        """Test initialization failure when queue connection fails."""
        worker = ExportWorker()

        with patch('main.create_queue_consumer') as mock_queue_creator:
            mock_queue = AsyncMock()
            mock_queue.connect = AsyncMock(return_value=False)
            mock_queue_creator.return_value = mock_queue

            result = await worker.initialize()

            assert result is False


class TestExportWorkerJobProcessing:
    """Test job processing logic."""

    @pytest.fixture
    def worker(self):
        """Create worker with mocked components."""
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        # Disabled cache — tests exercise direct Telegram fetch path
        worker.message_cache = MessageCache(
            redis_client=AsyncMock(),
            enabled=False,
        )
        return worker

    @pytest.mark.asyncio
    async def test_process_job_success(self, worker):
        """Test successful job processing."""
        job = ExportRequest(
            task_id="test_task_123",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        # Mock Telegram client
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test Chat", "type": "private"}, None)
        )

        # Mock message generator
        messages = [
            ExportedMessage(id=2, date="2025-01-01T00:00:01", text="World"),
            ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Hello"),
        ]

        async def mock_history(*args, **kwargs):
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history

        # Mock Java client
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert worker.jobs_processed == 1
        worker.java_client.send_response.assert_called_once()
        args, kwargs = worker.java_client.send_response.call_args
        assert kwargs["task_id"] == "test_task_123"
        assert kwargs["status"] == "completed"
        assert len(kwargs["messages"]) == 2

    @pytest.mark.asyncio
    async def test_process_job_chat_not_accessible(self, worker):
        """Test job processing when chat is not accessible."""
        job = ExportRequest(
            task_id="test_task_456",
            user_id=123,
            user_chat_id=123,
            chat_id=999,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(False, None, "CHAT_NOT_ACCESSIBLE")
        )
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_failed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert worker.jobs_failed == 0  # Not counted in jobs_failed, marked separately
        worker.java_client.send_response.assert_called_once()
        args, kwargs = worker.java_client.send_response.call_args
        assert kwargs["status"] == "failed"
        assert kwargs["error_code"] == "CHAT_NOT_ACCESSIBLE"

    @pytest.mark.asyncio
    async def test_process_job_export_error(self, worker):
        """Test job processing when export fails."""
        job = ExportRequest(
            task_id="test_task_789",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "private"}, None)
        )

        # Mock error during export
        async def mock_history_error(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="First")
            raise ValueError("Export error")

        worker.telegram_client.get_chat_history = mock_history_error
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_failed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert worker.jobs_failed == 1
        worker.java_client.send_response.assert_called_once()
        args, kwargs = worker.java_client.send_response.call_args
        assert kwargs["status"] == "failed"
        assert "Export failed" in kwargs["error"]

    @pytest.mark.asyncio
    async def test_process_job_response_failure(self, worker):
        """Test job processing when sending response to Java fails."""
        job = ExportRequest(
            task_id="test_task_101",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "private"}, None)
        )

        # Mock successful export
        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Message")

        worker.telegram_client.get_chat_history = mock_history

        # Mock Java client failure
        worker.java_client.send_response = AsyncMock(return_value=False)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_failed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert worker.jobs_failed == 1
        worker.queue_consumer.mark_job_failed.assert_called()


class TestChatIdNormalization:
    """Test that chat_id is normalized to canonical numeric ID after verify_and_get_info."""

    @pytest.fixture
    def worker(self):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=1)
        worker.message_cache = MessageCache(
            redis_client=AsyncMock(),
            enabled=False,
        )
        worker.control_redis = AsyncMock()
        worker.control_redis.get = AsyncMock(return_value=None)
        return worker

    @pytest.mark.asyncio
    async def test_chat_id_normalized_from_username_to_numeric(self, worker):
        """Если пользователь передаёт username, chat_id нормализуется до числового ID
        из chat_info['id'] — чтобы кэш по числовому ID тоже попадал в цель."""
        canonical_id = -1002477958568
        job = ExportRequest(
            task_id="norm_task_1", user_id=1, user_chat_id=1,
            chat_id="strbypass", limit=0, offset_id=0,
        )

        # verify_and_get_info возвращает канонический числовой ID
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": canonical_id, "title": "Test", "type": "supergroup"}, None)
        )

        messages = [ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Hi")]

        async def mock_history(*args, **kwargs):
            # Проверяем: get_chat_history вызывается с нормализованным числовым ID
            assert kwargs.get("chat_id") == canonical_id or args[0] == canonical_id
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        result = await worker.process_job(job)
        assert result is True

    @pytest.mark.asyncio
    async def test_chat_id_no_change_when_already_canonical(self, worker):
        """Если chat_id уже совпадает с chat_info['id'], нормализация не меняет ничего."""
        job = ExportRequest(
            task_id="norm_task_2", user_id=1, user_chat_id=1,
            chat_id=-1002477958568, limit=0, offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": -1002477958568, "title": "Test", "type": "supergroup"}, None)
        )

        messages = [ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Hi")]

        async def mock_history(*args, **kwargs):
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        result = await worker.process_job(job)
        assert result is True


class TestExportWorkerProgressReporting:
    """Test progress reporting to user."""

    @pytest.fixture
    def worker(self):
        """Create worker with mocked components."""
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        worker.message_cache = MessageCache(
            redis_client=AsyncMock(),
            enabled=False,
        )
        return worker

    @pytest.mark.asyncio
    async def test_fetch_all_sends_started_with_total(self, worker):
        """_fetch_all_messages sends started notification with total count."""
        job = ExportRequest(
            task_id="progress_1", user_id=1, user_chat_id=1,
            chat_id=456, limit=0, offset_id=0,
        )

        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="A")

        worker.telegram_client.get_chat_history = mock_history
        worker.telegram_client.get_messages_count = AsyncMock(return_value=5000)
        worker.java_client.send_response = AsyncMock(return_value=True)

        await worker.process_job(job)

        # Check started notification
        calls = worker.java_client.send_progress_update.call_args_list
        assert len(calls) >= 1
        first_call = calls[0]
        assert first_call.kwargs["started"] is True
        assert first_call.kwargs["total"] == 5000

    @pytest.mark.asyncio
    async def test_fetch_all_sends_10pct_milestones(self, worker):
        """_fetch_all_messages sends progress at every 10% milestone."""
        job = ExportRequest(
            task_id="progress_2", user_id=1, user_chat_id=1,
            chat_id=456, limit=0, offset_id=0,
        )

        # 100 messages, total=100 → should fire at 10%, 20%, ... 90%
        messages = [
            ExportedMessage(id=i, date="2025-01-01T00:00:00", text=f"msg{i}")
            for i in range(1, 101)
        ]

        async def mock_history(*args, **kwargs):
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        worker.java_client.send_response = AsyncMock(return_value=True)

        await worker.process_job(job)

        # started + 9 milestones (10%..90%)
        progress_calls = worker.java_client.send_progress_update.call_args_list
        assert len(progress_calls) == 10  # 1 started + 9 milestones

    @pytest.mark.asyncio
    async def test_process_job_exception_notifies_user(self, worker):
        """Exception in process_job sends failure notification to user."""
        job = ExportRequest(
            task_id="error_1", user_id=1, user_chat_id=42,
            chat_id=456, limit=0, offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "private"}, None)
        )
        # Make send_response raise to trigger except block
        worker.java_client.send_response = AsyncMock(side_effect=RuntimeError("OOM"))

        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="msg")

        worker.telegram_client.get_chat_history = mock_history

        result = await worker.process_job(job)

        assert result is True
        # User should be notified of error
        worker.java_client._notify_user_failure.assert_called_once()
        call_args = worker.java_client._notify_user_failure.call_args
        assert call_args[0][0] == 42  # user_chat_id


class TestExportWorkerCleanup:
    """Test cleanup logic."""

    @pytest.mark.asyncio
    async def test_cleanup_disconnects_all(self):
        """Test that cleanup disconnects all components."""
        worker = ExportWorker()
        worker.running = True
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()

        await worker.cleanup()

        assert worker.running is False
        worker.telegram_client.disconnect.assert_called_once()
        worker.queue_consumer.disconnect.assert_called_once()
        worker.java_client.aclose.assert_called_once()

    @pytest.mark.asyncio
    async def test_cleanup_handles_missing_components(self):
        """Test cleanup when components are None."""
        worker = ExportWorker()
        worker.running = True
        worker.queue_consumer = None
        worker.telegram_client = None
        worker.java_client = None

        # Should not raise
        await worker.cleanup()

        assert worker.running is False


class TestExportWorkerWithCache:
    """Test job processing with cache enabled."""

    @pytest.fixture
    async def redis_client(self):
        import fakeredis.aioredis
        client = fakeredis.aioredis.FakeRedis(decode_responses=False)
        yield client
        await client.aclose()

    @pytest.fixture
    async def worker(self, redis_client):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        worker.message_cache = MessageCache(
            redis_client=redis_client,
            ttl_seconds=3600,
            max_memory_mb=120,
            max_messages_per_chat=1000,
            enabled=True,
        )
        return worker

    @pytest.mark.asyncio
    async def test_first_export_populates_cache(self, worker):
        """First export of a chat should populate cache."""
        job = ExportRequest(
            task_id="cache_test_1",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "private"}, None)
        )

        messages = [
            ExportedMessage(id=3, date="2025-01-01T00:00:02", text="C"),
            ExportedMessage(id=2, date="2025-01-01T00:00:01", text="B"),
            ExportedMessage(id=1, date="2025-01-01T00:00:00", text="A"),
        ]

        async def mock_history(*args, **kwargs):
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)

        await worker.process_job(job)

        # Cache should now have these messages
        cached = await worker.message_cache.get_cached_ranges(456)
        assert cached == [[1, 3]]

    @pytest.mark.asyncio
    async def test_second_export_uses_cache(self, worker):
        """Second export should fetch only new messages, reuse cached."""
        job = ExportRequest(
            task_id="cache_test_2",
            user_id=999,
            user_chat_id=999,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "private"}, None)
        )

        # Pre-populate cache with messages 1-3
        await worker.message_cache.store_messages(456, [
            ExportedMessage(id=1, date="2025-01-01T00:00:00", text="A"),
            ExportedMessage(id=2, date="2025-01-01T00:00:01", text="B"),
            ExportedMessage(id=3, date="2025-01-01T00:00:02", text="C"),
        ])

        # Telegram only returns NEW messages (id=4,5)
        new_msgs = [
            ExportedMessage(id=5, date="2025-01-01T00:00:04", text="E"),
            ExportedMessage(id=4, date="2025-01-01T00:00:03", text="D"),
        ]

        call_count = 0

        async def mock_history(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                # First call: fetch newer than cache max (min_id=3)
                for msg in new_msgs:
                    yield msg
            # No gap-filling calls expected (range [1-3] is continuous)

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)

        await worker.process_job(job)

        # Verify: Java received ALL 5 messages (3 cached + 2 new)
        args, kwargs = worker.java_client.send_response.call_args
        assert kwargs["status"] == "completed"
        result_ids = sorted(m.id for m in kwargs["messages"])
        assert result_ids == [1, 2, 3, 4, 5]


class TestExportWorkerDateCache:
    """Test date-range export with cache (Vasya→Petya→Kolya scenario)."""

    @pytest.fixture
    async def redis_client(self):
        import fakeredis.aioredis
        client = fakeredis.aioredis.FakeRedis(decode_responses=False)
        yield client
        await client.aclose()

    @pytest.fixture
    async def worker(self, redis_client):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        worker.message_cache = MessageCache(
            redis_client=redis_client,
            ttl_seconds=3600,
            max_memory_mb=120,
            max_messages_per_chat=1000,
            enabled=True,
        )
        return worker

    @pytest.mark.asyncio
    async def test_vasya_petya_kolya_date_export(self, worker):
        """
        1. Vasya: exports chat for Jan 11-13 → fetched from Telegram, cached
        2. Petya: exports chat for Jan 1-8 → fetched from Telegram, cached
        3. Kolya: exports chat for Jan 1-15 → cache provides 1-8 and 11-13,
           only fetches 9-10 and 14-15 from Telegram
        """
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test Chat", "type": "supergroup"}, None)
        )
        worker.java_client.send_response = AsyncMock(return_value=True)

        # --- Vasya: Jan 11-13 ---
        vasya_msgs = [
            ExportedMessage(id=13, date="2025-01-13T10:00:00", text="msg_13"),
            ExportedMessage(id=12, date="2025-01-12T10:00:00", text="msg_12"),
            ExportedMessage(id=11, date="2025-01-11T10:00:00", text="msg_11"),
        ]

        async def vasya_history(*args, **kwargs):
            for m in vasya_msgs:
                yield m

        worker.telegram_client.get_chat_history = vasya_history

        vasya_job = ExportRequest(
            task_id="vasya_1", user_id=100, user_chat_id=100, chat_id="testchat",
            from_date="2025-01-11T00:00:00", to_date="2025-01-13T23:59:59",
        )
        await worker.process_job(vasya_job)

        # Verify cache has Jan 11-13
        date_ranges = await worker.message_cache.get_cached_date_ranges("testchat")
        assert date_ranges == [["2025-01-11", "2025-01-13"]]

        # --- Petya: Jan 1-8 ---
        petya_msgs = [
            ExportedMessage(id=i, date=f"2025-01-{i:02d}T10:00:00", text=f"msg_{i}")
            for i in range(8, 0, -1)  # newest first
        ]

        async def petya_history(*args, **kwargs):
            for m in petya_msgs:
                yield m

        worker.telegram_client.get_chat_history = petya_history

        petya_job = ExportRequest(
            task_id="petya_1", user_id=200, user_chat_id=200, chat_id="testchat",
            from_date="2025-01-01T00:00:00", to_date="2025-01-08T23:59:59",
        )
        await worker.process_job(petya_job)

        # Verify cache has Jan 1-8 and Jan 11-13
        date_ranges = await worker.message_cache.get_cached_date_ranges("testchat")
        assert date_ranges == [["2025-01-01", "2025-01-08"], ["2025-01-11", "2025-01-13"]]

        # --- Kolya: Jan 1-15 ---
        # Should only fetch Jan 9-10 and Jan 14-15 from Telegram
        fetch_calls = []

        async def kolya_history(*args, **kwargs):
            from_date = kwargs.get("from_date")
            to_date = kwargs.get("to_date")
            fetch_calls.append((
                from_date.strftime("%Y-%m-%d") if from_date else None,
                to_date.strftime("%Y-%m-%d") if to_date else None,
            ))
            # Return messages for the requested gap
            if from_date and from_date.day == 9:
                # Gap Jan 9-10
                yield ExportedMessage(id=10, date="2025-01-10T10:00:00", text="msg_10")
                yield ExportedMessage(id=9, date="2025-01-09T10:00:00", text="msg_9")
            elif from_date and from_date.day == 14:
                # Gap Jan 14-15
                yield ExportedMessage(id=15, date="2025-01-15T10:00:00", text="msg_15")
                yield ExportedMessage(id=14, date="2025-01-14T10:00:00", text="msg_14")

        worker.telegram_client.get_chat_history = kolya_history

        kolya_job = ExportRequest(
            task_id="kolya_1", user_id=300, user_chat_id=300, chat_id="testchat",
            from_date="2025-01-01T00:00:00", to_date="2025-01-15T23:59:59",
        )
        await worker.process_job(kolya_job)

        # Verify: only 2 fetch calls (for the 2 gaps)
        assert len(fetch_calls) == 2
        assert ("2025-01-09", "2025-01-10") in fetch_calls
        assert ("2025-01-14", "2025-01-15") in fetch_calls

        # Verify: Java received all 15 messages
        last_call = worker.java_client.send_response.call_args
        result_ids = sorted(m.id for m in last_call.kwargs["messages"])
        assert result_ids == list(range(1, 16))

        # Cache now has complete Jan 1-15
        date_ranges = await worker.message_cache.get_cached_date_ranges("testchat")
        assert date_ranges == [["2025-01-01", "2025-01-15"]]


class TestExportWorkerMemoryLogging:
    """Test memory and resource logging."""

    def test_log_memory_usage(self):
        """Test memory logging doesn't crash."""
        worker = ExportWorker()
        # Should not raise even without psutil
        worker.log_memory_usage("TEST")
