"""
Tests for Pyrogram TelegramClient.

Covers connection, authentication, message export, and error handling.
"""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from datetime import datetime
from pyrogram.errors import Unauthorized, BadRequest

from pyrogram_client import TelegramClient
from models import ExportedMessage


class TestTelegramClientConnection:
    """Test connection management."""

    @pytest.mark.asyncio
    async def test_connect_success(self):
        """Test successful connection."""
        client = TelegramClient()

        # Mock Pyrogram client
        mock_pyrogram = AsyncMock()
        mock_pyrogram.start = AsyncMock()
        mock_pyrogram.get_me = AsyncMock(
            return_value=MagicMock(first_name="TestUser", username="testuser")
        )
        client.client = mock_pyrogram

        result = await client.connect()

        assert result is True
        assert client.is_connected is True
        mock_pyrogram.start.assert_called_once()
        mock_pyrogram.get_me.assert_called_once()

    @pytest.mark.asyncio
    async def test_connect_already_connected(self):
        """Test that connect returns True if already connected."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()
        client.client = mock_pyrogram

        result = await client.connect()

        assert result is True
        mock_pyrogram.start.assert_not_called()

    @pytest.mark.asyncio
    async def test_connect_unauthorized(self):
        """Test connection failure due to authorization."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.start = AsyncMock(side_effect=Unauthorized("Auth failed"))
        client.client = mock_pyrogram

        result = await client.connect()

        assert result is False
        assert client.is_connected is False

    @pytest.mark.asyncio
    async def test_connect_other_error(self):
        """Test connection failure due to other errors."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.start = AsyncMock(side_effect=Exception("Connection error"))
        client.client = mock_pyrogram

        result = await client.connect()

        assert result is False
        assert client.is_connected is False

    @pytest.mark.asyncio
    async def test_disconnect(self):
        """Test disconnect."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()
        mock_pyrogram.is_connected = True
        mock_pyrogram.stop = AsyncMock()
        client.client = mock_pyrogram

        await client.disconnect()

        assert client.is_connected is False
        mock_pyrogram.stop.assert_called_once()

    @pytest.mark.asyncio
    async def test_disconnect_not_connected(self):
        """Test disconnect when not connected."""
        client = TelegramClient()
        client.is_connected = False
        mock_pyrogram = AsyncMock()
        client.client = mock_pyrogram

        await client.disconnect()

        mock_pyrogram.stop.assert_not_called()


class TestTelegramClientVerifyAccess:
    """Test access verification."""

    @pytest.mark.asyncio
    async def test_verify_and_get_info_success(self):
        """Test successful chat verification."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.get_chat = AsyncMock(
            return_value=MagicMock(
                title="Test Chat",
                type="private",
                members_count=100,
            )
        )
        client.client = mock_pyrogram

        accessible, info, error_reason = await client.verify_and_get_info(123)

        assert accessible is True
        assert info is not None
        assert info["title"] == "Test Chat"
        assert info["type"] == "private"
        assert error_reason is None

    @pytest.mark.asyncio
    async def test_verify_and_get_info_chat_not_found(self):
        """Test chat not found."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.get_chat = AsyncMock(side_effect=BadRequest("Chat not found"))
        client.client = mock_pyrogram

        accessible, info, error_reason = await client.verify_and_get_info(999)

        assert accessible is False
        assert info is None
        assert error_reason is not None

    @pytest.mark.asyncio
    async def test_verify_and_get_info_no_access(self):
        """Test no access to chat."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.get_chat = AsyncMock(
            side_effect=Exception("ChannelPrivate")
        )
        client.client = mock_pyrogram

        accessible, info, error_reason = await client.verify_and_get_info(456)

        assert accessible is False
        assert error_reason == "UNKNOWN"


class TestTelegramClientHistoryExport:
    """Test message history export."""

    @pytest.mark.asyncio
    async def test_get_chat_history_success(self):
        """Test successful message export."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()

        # Create mock messages
        mock_messages = [
            MagicMock(
                message_id=1,
                text="Message 1",
                date=datetime.now(),
                from_user=MagicMock(id=123),
                entities=[],
            ),
            MagicMock(
                message_id=2,
                text="Message 2",
                date=datetime.now(),
                from_user=MagicMock(id=123),
                entities=[],
            ),
        ]

        async def mock_get_chat_history(*args, **kwargs):
            for msg in mock_messages:
                yield msg

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        # Mock MessageConverter
        with patch('pyrogram_client.MessageConverter') as mock_converter:
            mock_converter.to_exported_message = MagicMock(
                side_effect=lambda msg: ExportedMessage(
                    message_id=msg.message_id,
                    text=msg.text,
                    date=msg.date,
                    from_user_id=msg.from_user.id if msg.from_user else None,
                    entities=[],
                )
            )

            messages = []
            async for msg in client.get_chat_history(123, limit=0, offset_id=0):
                messages.append(msg)

            assert len(messages) == 2
            assert messages[0].message_id == 1
            assert messages[1].message_id == 2

    @pytest.mark.asyncio
    async def test_get_chat_history_with_limit(self):
        """Test message export with limit."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()

        messages_yielded = []

        async def mock_get_chat_history(*args, **kwargs):
            assert kwargs.get("limit") == 100
            for i in range(100):
                yield MagicMock(
                    message_id=i,
                    text=f"Message {i}",
                    date=datetime.now(),
                    from_user=MagicMock(id=123),
                    entities=[],
                )

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        with patch('pyrogram_client.MessageConverter') as mock_converter:
            mock_converter.to_exported_message = MagicMock(
                side_effect=lambda msg: ExportedMessage(
                    message_id=msg.message_id,
                    text=msg.text,
                    date=msg.date,
                    from_user_id=msg.from_user.id if msg.from_user else None,
                    entities=[],
                )
            )

            count = 0
            async for msg in client.get_chat_history(123, limit=100):
                count += 1

            assert count == 100

    @pytest.mark.asyncio
    async def test_get_chat_history_error(self):
        """Test error handling during export."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()

        async def mock_get_chat_history_error(*args, **kwargs):
            yield MagicMock(
                message_id=1,
                text="First message",
                date=datetime.now(),
                from_user=MagicMock(id=123),
                entities=[],
            )
            raise Exception("Export error")

        mock_pyrogram.get_chat_history = mock_get_chat_history_error
        client.client = mock_pyrogram

        with patch('pyrogram_client.MessageConverter') as mock_converter:
            mock_converter.to_exported_message = MagicMock(
                return_value=ExportedMessage(
                    message_id=1,
                    text="First message",
                    date=datetime.now(),
                    from_user_id=123,
                    entities=[],
                )
            )

            messages = []
            with pytest.raises(Exception, match="Export error"):
                async for msg in client.get_chat_history(123):
                    messages.append(msg)

            # Should have exported one message before error
            assert len(messages) == 1


class TestTelegramClientContextManager:
    """Test context manager protocol."""

    @pytest.mark.asyncio
    async def test_async_context_manager(self):
        """Test async context manager protocol."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.start = AsyncMock()
        mock_pyrogram.stop = AsyncMock()
        mock_pyrogram.get_me = AsyncMock(
            return_value=MagicMock(first_name="Test", username="test")
        )
        client.client = mock_pyrogram

        async with client as connected_client:
            assert connected_client is client
            assert client.is_connected is True

        assert client.is_connected is False
        mock_pyrogram.stop.assert_called_once()
