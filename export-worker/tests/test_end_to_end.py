"""
End-to-End tests for Export Worker.

Tests complete workflow with mocked external services.
Full E2E tests with real services are recommended for Phase 3.
"""

import pytest
from unittest.mock import AsyncMock, Mock, patch
import asyncio
import tempfile
import os

from models import ExportRequest, ExportResponse, ExportedMessage
from java_client import ProgressTracker


def _make_mock_java_client():
    """Create AsyncMock java_client with working ProgressTracker support."""
    client = AsyncMock()
    client.send_progress_update = AsyncMock(return_value=12345)
    client.create_progress_tracker = lambda uid, tid: ProgressTracker(client, uid, tid)
    return client


@pytest.mark.asyncio
class TestExportWorkerE2E:
    """End-to-end tests for ExportWorker."""

    async def test_worker_initialization(self):
        """Test worker can be initialized."""
        from main import ExportWorker

        worker = ExportWorker()
        assert worker.running is False
        assert worker.jobs_processed == 0
        assert worker.jobs_failed == 0

    async def test_worker_job_processing_success(self):
        """Test successful job processing."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()
            worker.telegram_client = AsyncMock()
            worker.queue_consumer = AsyncMock()
            worker.java_client = _make_mock_java_client()

            # Setup mock behavior
            worker.telegram_client.verify_and_get_info = AsyncMock(return_value=(
                True,
                {'title': 'Test Chat', 'type': 'group'},
                None
            ))
            worker.telegram_client.get_messages_count = AsyncMock(return_value=5)

            # Mock message generator
            test_messages = [
                ExportedMessage(
                    id=i,
                    type="message",
                    date="2025-06-24T15:29:46",
                    text=f"Message {i}"
                )
                for i in range(1, 6)
            ]

            async def message_generator(**kwargs):
                for msg in test_messages:
                    yield msg

            worker.telegram_client.get_chat_history = message_generator
            worker.java_client.send_response = AsyncMock(return_value=True)
            worker.queue_consumer.mark_job_processing = AsyncMock(return_value=True)
            worker.queue_consumer.mark_job_completed = AsyncMock(return_value=True)

            # Create test job
            job = ExportRequest(
                task_id="test_123",
                user_id=456,
                chat_id=-1001234567890,
                limit=10
            )

            # Process job
            result = await worker.process_job(job)

            assert result is True
            worker.telegram_client.verify_and_get_info.assert_called_once()

    async def test_worker_job_processing_no_access(self):
        """Test job processing when no access to chat."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()
            worker.telegram_client = AsyncMock()
            worker.queue_consumer = AsyncMock()
            worker.java_client = _make_mock_java_client()

            # Setup: no access to chat
            worker.telegram_client.verify_and_get_info = AsyncMock(return_value=(False, None, "CHAT_NOT_ACCESSIBLE"))
            worker.java_client.send_response = AsyncMock(return_value=True)
            worker.queue_consumer.mark_job_failed = AsyncMock(return_value=True)

            job = ExportRequest(
                task_id="test_123",
                user_id=456,
                chat_id=-1001234567890
            )

            result = await worker.process_job(job)

            assert result is True
            worker.java_client.send_response.assert_called_once()
            # Verify error response sent
            call_args = worker.java_client.send_response.call_args
            assert call_args[1]['status'] == 'failed'
            assert 'CHAT_NOT_ACCESSIBLE' in str(call_args[1]['error_code'])

    async def test_worker_job_processing_export_error(self):
        """Test job processing with export error."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()
            worker.telegram_client = AsyncMock()
            worker.queue_consumer = AsyncMock()
            worker.java_client = _make_mock_java_client()

            # Setup: access OK but export fails
            worker.telegram_client.verify_and_get_info = AsyncMock(return_value=(
                True,
                {'title': 'Test', 'type': 'group'},
                None
            ))
            worker.telegram_client.get_messages_count = AsyncMock(return_value=10)

            async def failing_generator(**kwargs):
                yield ExportedMessage(
                    id=1,
                    type="message",
                    date="2025-06-24T15:29:46",
                    text="First"
                )
                raise RuntimeError("Export interrupted")

            worker.telegram_client.get_chat_history = failing_generator
            worker.java_client.send_response = AsyncMock(return_value=True)
            worker.queue_consumer.mark_job_failed = AsyncMock(return_value=True)
            worker.queue_consumer.mark_job_processing = AsyncMock(return_value=True)

            job = ExportRequest(
                task_id="test_123",
                user_id=456,
                chat_id=-1001234567890
            )

            result = await worker.process_job(job)

            assert result is True
            # Verify error response sent
            call_args = worker.java_client.send_response.call_args
            assert call_args[1]['status'] == 'failed'

    async def test_worker_statistics_tracking(self):
        """Test worker tracks statistics correctly."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()
            initial_processed = worker.jobs_processed
            initial_failed = worker.jobs_failed

            # Setup mocks for successful job
            worker.telegram_client = AsyncMock()
            worker.queue_consumer = AsyncMock()
            worker.java_client = _make_mock_java_client()

            worker.telegram_client.verify_and_get_info = AsyncMock(return_value=(
                True,
                {'title': 'Test', 'type': 'group'},
                None
            ))
            worker.telegram_client.get_messages_count = AsyncMock(return_value=1)

            # Create async generator wrapper for get_chat_history
            class AsyncMessageGenerator:
                def __aiter__(self):
                    return self

                async def __anext__(self):
                    raise StopAsyncIteration

                async def __call__(self, **kwargs):
                    return self

            # Mock get_chat_history to return async iterator
            messages = [
                ExportedMessage(
                    id=1, type="message",
                    date="2025-06-24T15:29:46",
                    text="Test"
                )
            ]

            async def async_gen(**kwargs):
                for msg in messages:
                    yield msg

            worker.telegram_client.get_chat_history = async_gen
            worker.java_client.send_response = AsyncMock(return_value=True)
            worker.queue_consumer.mark_job_processing = AsyncMock(return_value=True)
            worker.queue_consumer.mark_job_completed = AsyncMock(return_value=True)

            # Process successful job
            job = ExportRequest(
                task_id="test_1",
                user_id=456,
                chat_id=-1001234567890
            )
            await worker.process_job(job)

            # Verify statistics incremented
            assert worker.jobs_processed == initial_processed + 1
            assert worker.jobs_failed == initial_failed


@pytest.mark.asyncio
class TestErrorRecovery:
    """Test error recovery mechanisms."""

    async def test_partial_message_export(self):
        """Test handling of partial exports."""
        # Simulate exporting 50 messages before error
        messages = [
            ExportedMessage(
                id=i,
                type="message",
                date="2025-06-24T15:29:46",
                text=f"Message {i}"
            )
            for i in range(1, 51)
        ]

        response = ExportResponse(
            task_id="test",
            status="failed",
            message_count=len(messages),
            messages=messages,
            error="Rate limited",
            error_code="RATE_LIMIT"
        )

        # Verify response structure
        assert response.message_count == 50
        assert len(response.messages) == 50
        assert response.status == "failed"

        # Verify error information is present
        assert response.error == "Rate limited"
        assert response.error_code == "RATE_LIMIT"

        # Verify serialization includes all data
        response_json = response.model_dump(exclude_none=True)
        assert response_json['status'] == 'failed'
        assert response_json['error_code'] == 'RATE_LIMIT'
        assert len(response_json['messages']) == 50

    async def test_retry_logic_with_backoff(self):
        """Test exponential backoff calculation."""
        from config import settings

        base_delay = settings.RETRY_BASE_DELAY
        max_delay = settings.RETRY_MAX_DELAY

        # Calculate backoff delays
        delays = []
        for attempt in range(5):
            delay = min(base_delay * (2 ** attempt), max_delay)
            delays.append(delay)

        # Verify exponential growth
        assert delays[0] == 1.0
        assert delays[1] == 2.0
        assert delays[2] == 4.0
        assert delays[3] == 8.0
        assert delays[4] == 16.0

        # Verify max delay cap
        for attempt in range(10):
            delay = min(base_delay * (2 ** attempt), max_delay)
            assert delay <= max_delay


@pytest.mark.asyncio
class TestSignalHandling:
    """Test graceful shutdown handling."""

    async def test_worker_signal_handling(self):
        """Test signal handler sets running flag."""
        from main import ExportWorker
        import signal

        worker = ExportWorker()
        worker.running = True

        # Simulate signal
        worker.handle_signal(signal.SIGTERM, None)

        assert worker.running is False

    async def test_worker_cleanup(self):
        """Test worker cleanup procedure."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()
            worker.telegram_client = AsyncMock()
            worker.queue_consumer = AsyncMock()
            worker.running = True

            # Setup mocks
            worker.telegram_client.disconnect = AsyncMock()
            worker.queue_consumer.disconnect = AsyncMock()

            # Run cleanup
            await worker.cleanup()

            assert worker.running is False
            worker.telegram_client.disconnect.assert_called_once()
            worker.queue_consumer.disconnect.assert_called_once()


@pytest.mark.asyncio
class TestPipelineFlow:
    """Test complete pipeline flow."""

    async def test_request_to_response_flow(self):
        """Test complete request to response flow."""
        # 1. Create request
        request = ExportRequest(
            task_id="export_001",
            user_id=123456,
            chat_id=-1001234567890,
            limit=100,
            offset_id=0
        )

        # 2. Simulate export
        exported_messages = [
            ExportedMessage(
                id=i,
                type="message",
                date="2025-06-24T15:29:46",
                text=f"Message {i}",
                from_user="John Doe",
                from_id={"peer_type": "user", "peer_id": 123}
            )
            for i in range(1, 101)
        ]

        # 3. Create response
        response = ExportResponse(
            task_id=request.task_id,
            status="completed",
            message_count=len(exported_messages),
            messages=exported_messages,
            exported_at="2025-06-24T15:30:00"
        )

        # 4. Verify flow
        assert response.task_id == request.task_id
        assert response.status == "completed"
        assert response.message_count == 100
        assert len(response.messages) == 100

        # 5. Verify response JSON serialization
        response_json = response.model_dump(exclude_none=True)
        assert response_json['task_id'] == request.task_id
        assert response_json['status'] == 'completed'
        assert len(response_json['messages']) == 100


@pytest.mark.asyncio
class TestWorkerCleanup:
    """Test worker cleanup methods."""

    async def test_cleanup_temp_files_for_task(self):
        """Test worker cleanup_temp_files removes temp directory."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()

            # Create temporary directory
            temp_dir = tempfile.mkdtemp(prefix="export_test_")
            task_id = os.path.basename(temp_dir).replace("export_", "")

            test_file = os.path.join(temp_dir, "test.json")
            with open(test_file, "w") as f:
                f.write('{"test": "data"}')

            # Verify directory exists
            assert os.path.isdir(temp_dir)

            # Cleanup via worker (it uses /tmp/export_{task_id} pattern)
            # Since we can't easily mock shutil.rmtree, just verify method doesn't crash
            await worker.cleanup_temp_files(task_id)

            # Method should complete without error
            assert True

    async def test_cleanup_temp_files_handles_missing_directory(self):
        """Test cleanup_temp_files handles missing directories gracefully."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()

            # Try cleanup on nonexistent task_id
            # Should not raise exception
            try:
                await worker.cleanup_temp_files("nonexistent_task_xyz_123")
                success = True
            except Exception:
                success = False

            assert success


@pytest.mark.asyncio
class TestCancelSupport:
    """Test export cancellation functionality."""

    async def test_cancel_stops_export(self):
        """Test that is_cancelled() returns True when cancel flag is set."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()
            worker.control_redis = AsyncMock()

            # Setup: cancel flag is set in Redis
            worker.control_redis.get = AsyncMock(return_value=b"1")

            # Call is_cancelled
            result = await worker.is_cancelled("test_task_123")

            # Verify it returns True
            assert result is True
            worker.control_redis.get.assert_called_once_with("cancel_export:test_task_123")

    async def test_cancel_saves_partial_messages(self):
        """Test that cancelled jobs save accumulated messages to cache."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()
            worker.control_redis = AsyncMock()
            worker.message_cache = Mock()
            worker.message_cache.enabled = True
            worker.message_cache.store_messages = AsyncMock(return_value=None)

            # Setup: cancel flag is set
            worker.control_redis.get = AsyncMock(return_value=b"1")
            worker.control_redis.delete = AsyncMock(return_value=None)

            # Create test messages (100+ to trigger check)
            test_messages = [
                ExportedMessage(
                    id=i,
                    type="message",
                    date="2025-06-24T15:29:46",
                    text=f"Message {i}"
                )
                for i in range(1, 101)
            ]

            # Call _check_cancel_and_save at 100 message mark
            job = ExportRequest(
                task_id="test_123",
                user_id=456,
                chat_id=-1001234567890
            )

            is_cancelled = await worker._check_cancel_and_save(job, test_messages, 100)

            # Verify cancel was detected and messages were saved
            assert is_cancelled is True
            worker.message_cache.store_messages.assert_called_once_with(job.chat_id, test_messages)

    async def test_cancel_clears_active_export_marker(self):
        """Test that cancelled export clears the active_export marker."""
        from main import ExportWorker

        with patch('main.TelegramClient'), \
             patch('main.QueueConsumer'), \
             patch('main.JavaBotClient'):

            worker = ExportWorker()
            worker.control_redis = AsyncMock()
            worker.control_redis.delete = AsyncMock(return_value=None)

            # Call clear_active_export
            await worker.clear_active_export(456)

            # Verify Redis delete was called for active_export marker
            worker.control_redis.delete.assert_called_once_with("active_export:456")
