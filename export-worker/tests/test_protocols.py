"""
Tests for protocols (abstract interfaces).
"""

import pytest
from unittest.mock import MagicMock

from protocols import (
    JobQueueConsumer,
    TelegramClientProtocol,
    ResultDeliveryClient,
)


class TestProtocols:
    """Test that existing classes implement protocols."""

    def test_queue_consumer_implements_protocol(self):
        """Verify QueueConsumer implements JobQueueConsumer protocol."""
        from queue_consumer import QueueConsumer
        from pyrogram_client import TelegramClient
        from java_client import JavaBotClient

        # These classes should be compatible with protocols
        # (structural subtyping via @runtime_checkable)
        assert hasattr(QueueConsumer, 'get_job')
        assert hasattr(QueueConsumer, 'mark_job_processing')
        assert hasattr(QueueConsumer, 'mark_job_completed')
        assert hasattr(QueueConsumer, 'mark_job_failed')
        assert hasattr(QueueConsumer, 'disconnect')

    def test_telegram_client_implements_protocol(self):
        """Verify TelegramClient implements TelegramClientProtocol."""
        from pyrogram_client import TelegramClient

        assert hasattr(TelegramClient, 'get_chat_history')
        assert hasattr(TelegramClient, 'verify_and_get_info')
        assert hasattr(TelegramClient, 'disconnect')

    def test_java_bot_client_implements_protocol(self):
        """Verify JavaBotClient implements ResultDeliveryClient."""
        from java_client import JavaBotClient

        assert hasattr(JavaBotClient, 'send_response')
        assert hasattr(JavaBotClient, 'aclose')

    def test_mock_implements_protocol(self):
        """Verify mocks can satisfy protocol requirements."""
        # Create mock that satisfies protocol
        mock_consumer = MagicMock()
        mock_consumer.get_job = MagicMock(return_value=None)
        mock_consumer.mark_job_processing = MagicMock(return_value=True)
        mock_consumer.mark_job_completed = MagicMock(return_value=True)
        mock_consumer.mark_job_failed = MagicMock(return_value=True)
        mock_consumer.disconnect = MagicMock(return_value=None)

        # Verify mock satisfies protocol
        assert isinstance(mock_consumer, JobQueueConsumer)
