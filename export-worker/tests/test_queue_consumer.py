"""
Unit tests for queue_consumer.py

Tests:
- QueueConsumer initialization
- Job serialization/deserialization
- Model validation

Note: Integration tests with actual Redis should be in separate file
"""

import json
import pytest
from unittest.mock import Mock, AsyncMock, patch

from models import ExportRequest
from queue_consumer import QueueConsumer


class TestQueueConsumer:
    """Tests for QueueConsumer"""

    def test_init(self):
        """Should initialize consumer"""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()

            assert consumer.queue_name == "telegram_export"
            assert consumer.redis_client is None

    def test_redis_url_without_password(self):
        """Should generate correct Redis URL without password"""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()

            assert consumer.redis_url == "redis://redis:6379/0"

    def test_redis_url_with_password(self):
        """Should generate correct Redis URL with password"""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = "secret123"
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()

            assert consumer.redis_url == "redis://:secret123@redis:6379/0"

    def test_job_serialization(self):
        """Should correctly serialize job to JSON"""
        job = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            limit=1000
        )

        job_json = json.dumps(job.model_dump())
        job_data = json.loads(job_json)

        # Verify round-trip
        restored = ExportRequest(**job_data)
        assert restored.task_id == job.task_id
        assert restored.chat_id == job.chat_id

    def test_job_deserialization_invalid_json(self):
        """Should handle invalid JSON gracefully"""
        invalid_json = "not valid json {"

        try:
            data = json.loads(invalid_json)
            pytest.fail("Should have raised JSONDecodeError")
        except json.JSONDecodeError:
            # Expected
            pass

    def test_job_deserialization_missing_field(self):
        """Should fail validation if required field missing"""
        incomplete_job = {
            "task_id": "export_12345",
            # Missing user_id and chat_id
        }

        try:
            job = ExportRequest(**incomplete_job)
            pytest.fail("Should have raised validation error")
        except Exception:
            # Expected
            pass

    def test_job_with_optional_fields(self):
        """Should handle optional fields correctly"""
        job_data = {
            "task_id": "export_12345",
            "user_id": 123456789,
            "chat_id": -1001234567890,
            "from_date": "2025-01-01T00:00:00",
            "to_date": "2025-12-31T23:59:59"
        }

        job = ExportRequest(**job_data)

        assert job.from_date == "2025-01-01T00:00:00"
        assert job.to_date == "2025-12-31T23:59:59"


@pytest.mark.asyncio
class TestQueueConsumerJobManagement:
    """Test job management methods (push, mark processing, etc)."""

    async def test_push_job_serializes_and_rpush(self):
        """Test push_job serializes and pushes to Redis."""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.rpush = AsyncMock(return_value=1)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                job = ExportRequest(
                    task_id="test_123",
                    user_id=456,
                    chat_id=-1001234567890,
                    limit=100
                )

                result = await consumer.push_job(job)

                assert result is True
                mock_client.rpush.assert_called_once()
                # Verify job was serialized to JSON
                call_args = mock_client.rpush.call_args
                assert "telegram_export" in call_args[0]
                # Check JSON contains task_id
                json_data = json.loads(call_args[0][1])
                assert json_data['task_id'] == "test_123"

    async def test_mark_job_processing_sets_key_with_ttl(self):
        """Test mark_job_processing sets Redis key with TTL."""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"
            mock_settings.JOB_TIMEOUT = 3600

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.setex = AsyncMock(return_value=True)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                result = await consumer.mark_job_processing("test_123")

                assert result is True
                mock_client.setex.assert_called_once()
                # Verify key format and TTL
                call_args = mock_client.setex.call_args
                assert "job:processing:test_123" in call_args[0]
                assert call_args[0][1] == 3600  # TTL

    async def test_mark_job_completed_deletes_and_sets(self):
        """Test mark_job_completed removes processing key and sets completed."""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.delete = AsyncMock(return_value=1)
                mock_client.setex = AsyncMock(return_value=True)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                result = await consumer.mark_job_completed("test_123")

                assert result is True
                # Verify delete was called first
                mock_client.delete.assert_called_once_with("job:processing:test_123")
                # Verify setex was called for completed key
                mock_client.setex.assert_called_once()
                call_args = mock_client.setex.call_args
                assert "job:completed:test_123" in call_args[0]

    async def test_mark_job_failed_stores_error_json(self):
        """Test mark_job_failed stores error information."""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.delete = AsyncMock(return_value=1)
                mock_client.setex = AsyncMock(return_value=True)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                result = await consumer.mark_job_failed("test_123", "Network timeout")

                assert result is True
                mock_client.delete.assert_called_once()
                mock_client.setex.assert_called_once()
                # Verify error is stored as JSON
                call_args = mock_client.setex.call_args
                json_value = json.loads(call_args[0][2])
                assert json_value['error'] == "Network timeout"
                assert 'timestamp' in json_value

    async def test_get_queue_stats_returns_count(self):
        """Test get_queue_stats returns queue information."""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.llen = AsyncMock(return_value=42)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                result = await consumer.get_queue_stats()

                assert result is not None
                assert result['queue_name'] == "telegram_export"
                assert result['pending_jobs'] == 42
                assert 'timestamp' in result
                mock_client.llen.assert_called_once_with("telegram_export")


@pytest.mark.asyncio
class TestQueueConsumerAsync:
    """Async tests for QueueConsumer (mocked Redis)"""

    async def test_context_manager(self):
        """Should work as async context manager"""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.ping = AsyncMock(return_value=True)
                mock_client.close = AsyncMock()
                mock_redis.return_value = mock_client

                async with QueueConsumer() as consumer:
                    assert consumer.redis_client == mock_client

    async def test_connect_success(self):
        """Should connect successfully"""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.ping = AsyncMock(return_value=True)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                result = await consumer.connect()

                assert result is True
                assert consumer.is_connected is True

    async def test_disconnect(self):
        """Should disconnect properly"""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.ping = AsyncMock(return_value=True)
                mock_client.close = AsyncMock()
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                await consumer.connect()
                await consumer.disconnect()

                mock_client.close.assert_called_once()


class TestExportRequestIntegration:
    """Integration tests with ExportRequest validation"""

    def test_complete_job_message(self):
        """Should validate complete job message"""
        job_msg = {
            "task_id": "export_12345",
            "user_id": 123456789,
            "chat_id": -1001234567890,
            "limit": 1000,
            "offset_id": 0,
            "from_date": "2025-06-01T00:00:00",
            "to_date": "2025-06-30T23:59:59"
        }

        # Should not raise
        job = ExportRequest(**job_msg)

        assert job.task_id == "export_12345"
        assert job.limit == 1000

    def test_minimal_job_message(self):
        """Should validate minimal job message"""
        job_msg = {
            "task_id": "export_12345",
            "user_id": 123456789,
            "chat_id": -1001234567890
        }

        # Should not raise
        job = ExportRequest(**job_msg)

        assert job.task_id == "export_12345"
        assert job.limit == 0  # Default

    def test_job_with_zero_limit(self):
        """Should handle zero limit (means unlimited)"""
        job = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            limit=0  # Unlimited
        )

        assert job.limit == 0

    def test_job_with_negative_chat_id(self):
        """Should accept negative chat ID (for groups)"""
        job = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890  # Group/channel
        )

        assert job.chat_id == -1001234567890

    def test_job_type_coercion(self):
        """Should coerce string integers to int"""
        job_msg = {
            "task_id": "export_12345",
            "user_id": "123456789",  # String
            "chat_id": "-1001234567890",  # String
            "limit": "1000"  # String
        }

        job = ExportRequest(**job_msg)

        assert isinstance(job.user_id, int)
        assert isinstance(job.chat_id, int)
        assert isinstance(job.limit, int)

@pytest.mark.asyncio
class TestGetPendingJobs:
    """Tests for get_pending_jobs."""

    async def test_returns_parsed_jobs(self):
        """Should return list of ExportRequest from queue without removing them."""
        job1 = ExportRequest(task_id="t1", user_id=1, chat_id=100)
        job2 = ExportRequest(task_id="t2", user_id=2, chat_id=200)

        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()
            mock_client = AsyncMock()
            mock_client.lrange = AsyncMock(return_value=[
                json.dumps(job1.model_dump()),
                json.dumps(job2.model_dump()),
            ])
            consumer.redis_client = mock_client

            result = await consumer.get_pending_jobs()

            assert len(result) == 2
            assert result[0].task_id == "t1"
            assert result[1].task_id == "t2"
            mock_client.lrange.assert_called_once_with("telegram_export", 0, -1)

    async def test_returns_empty_on_no_client(self):
        """Should return empty list if not connected."""
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()
            # redis_client is None by default
            result = await consumer.get_pending_jobs()
            assert result == []

    async def test_skips_invalid_json(self):
        """Should skip items that fail to parse."""
        valid_job = ExportRequest(task_id="t1", user_id=1, chat_id=100)

        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()
            mock_client = AsyncMock()
            mock_client.lrange = AsyncMock(return_value=[
                "not valid json {",
                json.dumps(valid_job.model_dump()),
            ])
            consumer.redis_client = mock_client

            result = await consumer.get_pending_jobs()

            assert len(result) == 1
            assert result[0].task_id == "t1"


@pytest.mark.asyncio
class TestDeadLetterQueue:
    """Tests for Dead Letter Queue — невалидные задачи перекладываются в DLQ."""

    def _make_consumer(self, mock_client):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"
            consumer = QueueConsumer()
        consumer.redis_client = mock_client
        return consumer

    async def test_invalid_json_moves_to_dlq(self):
        """Невалидный JSON из очереди должен уходить в DLQ, а не теряться."""
        mock_client = AsyncMock()
        mock_client.blpop = AsyncMock(return_value=("telegram_export", "not valid json {"))
        mock_client.rpush = AsyncMock(return_value=1)

        consumer = self._make_consumer(mock_client)
        result = await consumer.get_job()

        assert result is None
        mock_client.rpush.assert_called_once()
        dlq_call = mock_client.rpush.call_args
        assert "telegram_export_dead" in dlq_call[0]
        dlq_entry = json.loads(dlq_call[0][1])
        assert "JSON parse error" in dlq_entry["reason"]
        assert dlq_entry["raw"] == "not valid json {"

    async def test_validation_error_moves_to_dlq(self):
        """ExportRequest с невалидными данными должен уходить в DLQ."""
        invalid_job = json.dumps({"task_id": "t1"})  # Missing user_id, chat_id
        mock_client = AsyncMock()
        mock_client.blpop = AsyncMock(return_value=("telegram_export", invalid_job))
        mock_client.rpush = AsyncMock(return_value=1)

        consumer = self._make_consumer(mock_client)
        result = await consumer.get_job()

        assert result is None
        mock_client.rpush.assert_called_once()
        dlq_call = mock_client.rpush.call_args
        assert "telegram_export_dead" in dlq_call[0]
        dlq_entry = json.loads(dlq_call[0][1])
        assert "Validation error" in dlq_entry["reason"]

    async def test_valid_job_does_not_go_to_dlq(self):
        """Валидная задача не должна попадать в DLQ."""
        valid_job = json.dumps({
            "task_id": "export_ok",
            "user_id": 123,
            "chat_id": -1001234567890
        })
        mock_client = AsyncMock()
        mock_client.blpop = AsyncMock(return_value=("telegram_export", valid_job))
        mock_client.rpush = AsyncMock()

        consumer = self._make_consumer(mock_client)
        result = await consumer.get_job()

        assert result is not None
        assert result.task_id == "export_ok"
        mock_client.rpush.assert_not_called()
