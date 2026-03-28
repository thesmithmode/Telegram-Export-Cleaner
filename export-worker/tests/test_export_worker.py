"""
Tests for ExportWorker main class.

Covers job processing, error handling, and component lifecycle.
"""

import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch, call
from datetime import datetime

from main import ExportWorker
from message_cache import MessageCache
from models import ExportRequest, ExportedMessage


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
        worker.java_client = AsyncMock()
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


class TestExportWorkerCleanup:
    """Test cleanup logic."""

    @pytest.mark.asyncio
    async def test_cleanup_disconnects_all(self):
        """Test that cleanup disconnects all components."""
        worker = ExportWorker()
        worker.running = True
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = AsyncMock()

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
        worker.java_client = AsyncMock()
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


class TestExportWorkerMemoryLogging:
    """Test memory and resource logging."""

    def test_log_memory_usage(self):
        """Test memory logging doesn't crash."""
        worker = ExportWorker()
        # Should not raise even without psutil
        worker.log_memory_usage("TEST")
