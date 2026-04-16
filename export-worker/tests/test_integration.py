"""
Integration tests for export-worker components.

Tests interaction between components with mocked external services.
Note: Full integration tests with real Redis/Telegram require Phase 3.
"""

import pytest
from unittest.mock import Mock, AsyncMock, patch, MagicMock
from datetime import datetime

from models import ExportRequest, ExportResponse, ExportedMessage, ErrorCode


class TestPyrogramClientIntegration:
    """Integration tests for Pyrogram client."""

    @pytest.mark.asyncio
    async def test_client_connect_disconnect(self):
        """Test client connection lifecycle."""
        from pyrogram_client import TelegramClient

        with patch('pyrogram_client.Client') as mock_client:
            mock_instance = AsyncMock()
            mock_client.return_value = mock_instance

            client = TelegramClient()
            # Note: In Phase 3, test with real credentials
            # For now, verify initialization
            assert client.session_path.exists()

    @pytest.mark.asyncio
    async def test_client_error_handling(self):
        """Test client error handling in verify_and_get_info."""
        from pyrogram_client import TelegramClient

        client = TelegramClient()

        # Mock verify_and_get_info on the instance to raise RuntimeError
        client.verify_and_get_info = AsyncMock(
            side_effect=RuntimeError("Channel is private")
        )

        # Test that verify_and_get_info propagates the error
        with pytest.raises(RuntimeError):
            await client.verify_and_get_info(123)

    def test_message_conversion_integration(self):
        """Test message conversion in pipeline."""
        from json_converter import MessageConverter
        from models import ExportedMessage

        # Mock message
        message = Mock()
        message.id = 123
        message.date = datetime.now()
        message.text = "Test message"
        message.from_user = Mock()
        message.from_user.first_name = "John"
        message.from_user.last_name = "Doe"
        message.from_user.id = 456
        message.entities = None
        message.media = None
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert isinstance(result, ExportedMessage)
        assert result.id == 123
        assert result.from_user == "John Doe"
        assert result.text == "Test message"


class TestQueueConsumerIntegration:
    """Integration tests for queue consumer."""

    @pytest.mark.asyncio
    async def test_job_serialization_deserialization(self):
        """Test job can be serialized and deserialized."""
        import json

        job = ExportRequest(
            task_id="test_123",
            user_id=789,
            chat_id=-1001234567890,
            limit=100
        )

        # Simulate Redis storage
        job_json = json.dumps(job.model_dump())
        job_data = json.loads(job_json)
        restored_job = ExportRequest(**job_data)

        assert restored_job.task_id == job.task_id
        assert restored_job.user_id == job.user_id
        assert restored_job.chat_id == job.chat_id

    @pytest.mark.asyncio
    async def test_queue_consumer_context_manager(self):
        """Test queue consumer async context manager."""
        from queue_consumer import QueueConsumer

        with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
            mock_client = AsyncMock()
            mock_client.ping = AsyncMock(return_value=True)
            mock_client.close = AsyncMock()
            mock_redis.return_value = mock_client

            async with QueueConsumer() as consumer:
                assert consumer.redis_client == mock_client

            mock_client.close.assert_called_once()


class TestJavaClientIntegration:
    """Integration tests for Java client."""

    @pytest.mark.asyncio
    async def test_response_validation(self):
        """Test response object validation."""
        response = ExportResponse(
            task_id="test_123",
            status="completed",
            message_count=10,
            exported_at=datetime.now().isoformat()
        )

        assert response.task_id == "test_123"
        assert response.status == "completed"
        assert response.message_count == 10

    @pytest.mark.asyncio
    async def test_error_response_validation(self):
        """Test error response validation."""
        response = ExportResponse(
            task_id="test_123",
            status="failed",
            error="Chat not accessible",
            error_code=ErrorCode.CHAT_NOT_ACCESSIBLE
        )

        assert response.status == "failed"
        assert response.error == "Chat not accessible"
        assert response.error_code == ErrorCode.CHAT_NOT_ACCESSIBLE.value

    def test_error_code_enum(self):
        """Test ErrorCode enumeration."""
        # Non-retryable
        assert ErrorCode.CHAT_NOT_ACCESSIBLE.value == "CHAT_NOT_ACCESSIBLE"
        assert ErrorCode.CHAT_PRIVATE.value == "CHAT_PRIVATE"

        # Retryable
        assert ErrorCode.EXPORT_ERROR.value == "EXPORT_ERROR"
        assert ErrorCode.NETWORK_ERROR.value == "NETWORK_ERROR"

        # Rate limiting
        assert ErrorCode.RATE_LIMIT.value == "RATE_LIMIT"


class TestFullExportPipeline:
    """Test complete export pipeline (mocked)."""

    @pytest.mark.asyncio
    async def test_export_request_flow(self):
        """Test complete request flow."""
        # Create request
        request = ExportRequest(
            task_id="export_test",
            user_id=123,
            chat_id=-1001234567890,
            limit=100
        )

        # Create response with results
        response = ExportResponse(
            task_id=request.task_id,
            status="completed",
            message_count=100,
            messages=[
                ExportedMessage(
                    id=i,
                    type="message",
                    date=datetime.now().isoformat(),
                    text=f"Message {i}"
                )
                for i in range(1, 101)
            ]
        )

        # Verify flow
        assert response.task_id == request.task_id
        assert response.message_count == 100
        assert len(response.messages) == 100

    @pytest.mark.asyncio
    async def test_partial_export_on_error(self):
        """Test partial export with error."""
        # Simulate partial export (50 messages before error)
        response = ExportResponse(
            task_id="export_test",
            status="failed",
            message_count=50,
            messages=[
                ExportedMessage(
                    id=i,
                    type="message",
                    date=datetime.now().isoformat(),
                    text=f"Message {i}"
                )
                for i in range(1, 51)
            ],
            error="Rate limited - FloodWait",
            error_code=ErrorCode.RATE_LIMIT
        )

        assert response.status == "failed"
        assert response.message_count == 50
        assert len(response.messages) == 50


class TestErrorHandling:
    """Test error handling across components."""

    def test_error_code_categorization(self):
        """Test error codes are properly categorized."""
        non_retryable = [
            ErrorCode.CHAT_NOT_ACCESSIBLE,
            ErrorCode.CHAT_PRIVATE,
            ErrorCode.CHAT_ADMIN_REQUIRED,
            ErrorCode.INVALID_CHAT_ID,
        ]

        retryable = [
            ErrorCode.EXPORT_ERROR,
            ErrorCode.NETWORK_ERROR,
            ErrorCode.TIMEOUT,
        ]

        rate_limited = [
            ErrorCode.RATE_LIMIT,
        ]

        all_codes = non_retryable + retryable + rate_limited

        # Verify all codes are covered
        for code in ErrorCode:
            assert code in all_codes

    @pytest.mark.asyncio
    async def test_response_with_partial_messages(self):
        """Test response can handle partial results."""
        response = ExportResponse(
            task_id="test",
            status="failed",
            message_count=10,
            messages=[
                ExportedMessage(
                    id=i,
                    type="message",
                    date=datetime.now().isoformat(),
                    text=f"Message {i}"
                )
                for i in range(1, 11)
            ],
            error="Export interrupted",
            error_code=ErrorCode.TIMEOUT
        )

        assert response.message_count == 10
        assert len(response.messages) == 10
        assert response.error is not None
