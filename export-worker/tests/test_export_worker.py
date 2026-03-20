"""
Tests for ExportWorker main class.

Covers job processing, error handling, and component lifecycle.
"""

import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch, call
from datetime import datetime

from main import ExportWorker
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
            return_value=(True, {"title": "Test Chat", "type": "private"})
        )

        # Mock message generator
        messages = [
            ExportedMessage(
                message_id=1,
                text="Hello",
                date=datetime.now(),
                from_user_id=123,
                entities=[],
            ),
            ExportedMessage(
                message_id=2,
                text="World",
                date=datetime.now(),
                from_user_id=123,
                entities=[],
            ),
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
            return_value=(False, None)
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
        assert "not accessible" in kwargs["error"].lower()

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
            return_value=(True, {"title": "Test", "type": "private"})
        )

        # Mock error during export
        async def mock_history_error(*args, **kwargs):
            yield ExportedMessage(
                message_id=1,
                text="First",
                date=datetime.now(),
                from_user_id=123,
                entities=[],
            )
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
            return_value=(True, {"title": "Test", "type": "private"})
        )

        # Mock successful export
        async def mock_history(*args, **kwargs):
            yield ExportedMessage(
                message_id=1,
                text="Message",
                date=datetime.now(),
                from_user_id=123,
                entities=[],
            )

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


class TestExportWorkerMemoryLogging:
    """Test memory and resource logging."""

    def test_log_memory_usage(self):
        """Test memory logging doesn't crash."""
        worker = ExportWorker()
        # Should not raise even without psutil
        worker.log_memory_usage("TEST")
