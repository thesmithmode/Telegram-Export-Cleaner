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
