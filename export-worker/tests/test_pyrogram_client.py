"""
Tests for Pyrogram TelegramClient.

Covers connection, authentication, message export, and error handling.
"""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from datetime import datetime, timezone
from pyrogram.errors import Unauthorized, BadRequest, PeerIdInvalid

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
        client.is_connected = True  # верификация требует активного соединения
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

    @pytest.mark.asyncio
    async def test_verify_and_get_info_channel_without_is_bot(self):
        """Test that Chat objects without is_bot (channels/groups) don't raise AttributeError."""
        client = TelegramClient()
        client.is_connected = True  # верификация требует активного соединения
        mock_pyrogram = AsyncMock()

        # Simulate real Pyrogram Chat object for a channel — no is_bot/is_self/is_contact
        class FakeChatChannel:
            id = 1001234567890
            title = "Some Channel"
            username = "somechannel"
            type = "channel"
            members_count = 5000
            description = "Test channel"
            # intentionally missing: is_bot, is_self, is_contact

        mock_pyrogram.get_chat = AsyncMock(return_value=FakeChatChannel())
        client.client = mock_pyrogram

        accessible, info, error_reason = await client.verify_and_get_info("somechannel")

        assert accessible is True
        assert info is not None
        assert info["title"] == "Some Channel"
        assert info["is_bot"] is False
        assert info["is_self"] is False
        assert info["is_contact"] is False
        assert error_reason is None

    @pytest.mark.asyncio
    async def test_verify_and_get_info_public_channel_by_numeric_id_via_raw_mtproto(self):
        """Числовой ID публичного канала из picker: get_dialogs() не помогает,
        fallback через raw MTProto channels.GetChannels(access_hash=0) должен разрезолвить."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()

        # Первый get_chat — PeerIdInvalid (нет в кэше)
        class FakePublicChannel:
            id = -1002087367320
            title = "Public Channel"
            username = "publicchannel"
            type = "channel"
            members_count = 1000
            description = ""

        get_chat_results = [
            PeerIdInvalid(),  # первый вызов — нет в кэше
            PeerIdInvalid(),  # после get_dialogs() — всё ещё не в кэше
            FakePublicChannel(),                            # после invoke()
        ]

        async def get_chat_side_effect(*args, **kwargs):
            result = get_chat_results.pop(0)
            if isinstance(result, Exception):
                raise result
            return result

        mock_pyrogram.get_chat = get_chat_side_effect
        async def _empty_dialog_gen():
            return
            yield  # noqa: make it an async generator
        mock_pyrogram.get_dialogs = _empty_dialog_gen
        mock_pyrogram.invoke = AsyncMock(return_value=MagicMock())
        client.client = mock_pyrogram
        client.is_connected = True

        accessible, info, error_reason = await client.verify_and_get_info(-1002087367320)

        assert accessible is True
        assert info is not None
        assert info["title"] == "Public Channel"
        assert error_reason is None
        mock_pyrogram.invoke.assert_called_once()


    @pytest.mark.asyncio
    async def test_verify_and_get_info_value_error_peer_id_triggers_fallback(self):
        """ValueError 'Peer id invalid' от Pyrogram (peer не в локальном кэше сессии)
        должен активировать тот же fallback, что и PeerIdInvalid из BadRequest:
        синхронизация диалогов → raw MTProto channels.GetChannels(access_hash=0)."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()

        class FakePublicChannel:
            id = -1002477958568
            title = "Test Supergroup"
            username = "testgroup"
            type = "supergroup"
            members_count = 500
            description = ""

        get_chat_results = [
            ValueError("Peer id invalid: -1002477958568"),  # нет в кэше
            ValueError("Peer id invalid: -1002477958568"),  # после get_dialogs()
            FakePublicChannel(),                             # после invoke()
        ]

        async def get_chat_side_effect(*args, **kwargs):
            result = get_chat_results.pop(0)
            if isinstance(result, Exception):
                raise result
            return result

        mock_pyrogram.get_chat = get_chat_side_effect

        async def _empty_dialog_gen():
            return
            yield  # noqa: make it an async generator

        mock_pyrogram.get_dialogs = _empty_dialog_gen
        mock_pyrogram.invoke = AsyncMock(return_value=MagicMock())
        client.client = mock_pyrogram
        client.is_connected = True

        accessible, info, error_reason = await client.verify_and_get_info(-1002477958568)

        assert accessible is True
        assert info is not None
        assert info["title"] == "Test Supergroup"
        assert error_reason is None
        mock_pyrogram.invoke.assert_called_once()

    @pytest.mark.asyncio
    async def test_verify_and_get_info_value_error_non_peer_propagates(self):
        """ValueError с другим сообщением (не 'Peer id invalid') должен возвращать UNKNOWN."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.get_chat = AsyncMock(
            side_effect=ValueError("some other error")
        )
        client.client = mock_pyrogram
        client.is_connected = True

        accessible, info, error_reason = await client.verify_and_get_info(-1002477958568)

        assert accessible is False
        assert error_reason == "UNKNOWN"


class TestTelegramClientHistoryExport:
    """Test message history export."""

    def _make_msg(self, msg_id: int, text: str = ""):
        """Create a minimal valid Pyrogram-like message mock."""
        return MagicMock(
            id=msg_id,
            text=text or f"Message {msg_id}",
            date=datetime(2025, 1, 1, 0, 0, msg_id % 60),
            from_user=None,
            entities=None,
            media=None,
            forward_from=None,
            forward_sender_name=None,
            forward_date=None,
            edit_date=None,
            reply_to_message_id=None,
        )

    @pytest.mark.asyncio
    async def test_get_chat_history_success(self):
        """Test successful message export."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()

        raw = [self._make_msg(2), self._make_msg(1)]

        async def mock_get_chat_history(*args, **kwargs):
            for msg in raw:
                yield msg

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        messages = []
        async for msg in client.get_chat_history(123, limit=0, offset_id=0):
            messages.append(msg)

        assert len(messages) == 2
        assert messages[0].id == 2
        assert messages[1].id == 1

    @pytest.mark.asyncio
    async def test_get_chat_history_with_limit(self):
        """Test message export with limit."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()

        async def mock_get_chat_history(*args, **kwargs):
            assert kwargs.get("limit") == 100
            for i in range(100, 0, -1):
                yield self._make_msg(i)

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        count = 0
        async for msg in client.get_chat_history(123, limit=100):
            count += 1

        assert count == 100

    @pytest.mark.asyncio
    async def test_get_chat_history_error(self):
        """Test error handling during export."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()

        async def mock_get_chat_history_error(*args, **kwargs):
            yield self._make_msg(1)
            raise Exception("Export error")

        mock_pyrogram.get_chat_history = mock_get_chat_history_error
        client.client = mock_pyrogram

        messages = []
        with pytest.raises(Exception, match="Export error"):
            async for msg in client.get_chat_history(123):
                messages.append(msg)

        # Should have exported one message before error
        assert len(messages) == 1


class TestTelegramClientIncrementalExport:
    """Test incremental export via min_id."""

    @pytest.mark.asyncio
    async def test_get_chat_history_stops_at_min_id(self):
        """min_id causes iteration to stop when message.id <= min_id."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()

        # Pyrogram yields newest→oldest: ids 10, 7, 5, 3
        raw_messages = [MagicMock(id=mid) for mid in (10, 7, 5, 3)]

        async def mock_get_chat_history(*args, **kwargs):
            for msg in raw_messages:
                yield msg

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        # MessageConverter ожидает реальный Pyrogram message; мокаем его так,
        # чтобы получать ExportedMessage с id из MagicMock'а. Без мока конвертер
        # ловит AttributeError на MagicMock'е, попадает в except в реализации
        # get_chat_history (line ~265) и молча скипает каждое сообщение.
        def fake_convert(m):
            return ExportedMessage(id=m.id, date="2025-01-01T00:00:00", text="x")

        with patch(
            "pyrogram_client.MessageConverter.convert_message",
            side_effect=fake_convert,
        ):
            # With min_id=5: should yield ids 10 and 7, stop at 5
            collected_ids = []
            async for msg in client.get_chat_history(123, min_id=5):
                collected_ids.append(msg.id)

        assert collected_ids == [10, 7]

    @pytest.mark.asyncio
    async def test_get_chat_history_min_id_zero_returns_all(self):
        """min_id=0 (default) returns all messages without stopping early."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()

        raw_messages = [MagicMock(id=mid) for mid in (3, 1)]

        async def mock_get_chat_history(*args, **kwargs):
            for msg in raw_messages:
                yield msg

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        def fake_convert(m):
            return ExportedMessage(id=m.id, date="2025-01-01T00:00:00", text="x")

        with patch(
            "pyrogram_client.MessageConverter.convert_message",
            side_effect=fake_convert,
        ):
            collected_ids = []
            async for msg in client.get_chat_history(123):  # min_id defaults to 0
                collected_ids.append(msg.id)

        assert collected_ids == [3, 1]


class TestTelegramClientDateFiltering:
    """Test date filtering in get_chat_history."""

    @pytest.mark.asyncio
    async def test_from_date_stops_iteration_early(self):
        """from_date should stop iteration (not just skip) since messages are newest→oldest."""
        from pyrogram_client import MessageConverter

        client = TelegramClient()
        client.is_connected = True

        # Create async generator directly (avoid function reference issues)
        async def mock_generator(chat_id=None, limit=None, offset_id=None):
            yield MagicMock(id=3, date=datetime(2025, 1, 3), entities=None, media=None,
                          from_user=None, forward_from=None, forward_sender_name=None,
                          forward_date=None, edit_date=None, reply_to_message_id=None,
                          caption=None, caption_entities=None, text="m3")
            yield MagicMock(id=2, date=datetime(2025, 1, 2), entities=None, media=None,
                          from_user=None, forward_from=None, forward_sender_name=None,
                          forward_date=None, edit_date=None, reply_to_message_id=None,
                          caption=None, caption_entities=None, text="m2")
            yield MagicMock(id=1, date=datetime(2025, 1, 1), entities=None, media=None,
                          from_user=None, forward_from=None, forward_sender_name=None,
                          forward_date=None, edit_date=None, reply_to_message_id=None,
                          caption=None, caption_entities=None, text="m1")

        client.client = AsyncMock()
        client.client.get_chat_history = mock_generator

        collected = []
        async for msg in client.get_chat_history(123, from_date=datetime(2025, 1, 2)):
            collected.append(msg.id)

        assert collected == [3, 2]

    @pytest.mark.asyncio
    async def test_to_date_skips_newer_messages(self):
        """to_date should skip newer messages but continue iterating."""
        client = TelegramClient()
        client.is_connected = True

        async def mock_generator(chat_id=None, limit=None, offset_id=None):
            yield MagicMock(id=3, date=datetime(2025, 1, 3), entities=None, media=None,
                          from_user=None, forward_from=None, forward_sender_name=None,
                          forward_date=None, edit_date=None, reply_to_message_id=None,
                          caption=None, caption_entities=None, text="m3")
            yield MagicMock(id=2, date=datetime(2025, 1, 2), entities=None, media=None,
                          from_user=None, forward_from=None, forward_sender_name=None,
                          forward_date=None, edit_date=None, reply_to_message_id=None,
                          caption=None, caption_entities=None, text="m2")
            yield MagicMock(id=1, date=datetime(2025, 1, 1), entities=None, media=None,
                          from_user=None, forward_from=None, forward_sender_name=None,
                          forward_date=None, edit_date=None, reply_to_message_id=None,
                          caption=None, caption_entities=None, text="m1")

        client.client = AsyncMock()
        client.client.get_chat_history = mock_generator

        collected = []
        async for msg in client.get_chat_history(123, to_date=datetime(2025, 1, 2)):
            collected.append(msg.id)

        assert collected == [2, 1]

    @pytest.mark.asyncio
    async def test_mixed_naive_aware_datetimes_are_handled(self):
        """Should not crash when message.date tz-awareness differs from filter tz-awareness."""
        client = TelegramClient()
        client.is_connected = True

        async def mock_generator(chat_id=None, limit=None, offset_id=None):
            # aware datetime
            yield MagicMock(id=3, date=datetime(2025, 1, 3, tzinfo=timezone.utc), entities=None, media=None,
                          from_user=None, forward_from=None, forward_sender_name=None,
                          forward_date=None, edit_date=None, reply_to_message_id=None,
                          caption=None, caption_entities=None, text="m3")
            # naive datetime
            yield MagicMock(id=2, date=datetime(2025, 1, 2), entities=None, media=None,
                          from_user=None, forward_from=None, forward_sender_name=None,
                          forward_date=None, edit_date=None, reply_to_message_id=None,
                          caption=None, caption_entities=None, text="m2")
            # aware datetime (older than from_date)
            yield MagicMock(id=1, date=datetime(2025, 1, 1, tzinfo=timezone.utc), entities=None, media=None,
                          from_user=None, forward_from=None, forward_sender_name=None,
                          forward_date=None, edit_date=None, reply_to_message_id=None,
                          caption=None, caption_entities=None, text="m1")

        client.client = AsyncMock()
        client.client.get_chat_history = mock_generator

        collected = []
        async for msg in client.get_chat_history(123, from_date=datetime(2025, 1, 2)):
            collected.append(msg.id)

        assert collected == [3, 2]


class TestTelegramClientFloodWait:
    """Test FloodWait retry logic."""

    def _make_msg(self, msg_id: int):
        return MagicMock(
            id=msg_id,
            text=f"Message {msg_id}",
            date=datetime(2025, 1, 1, 0, 0, msg_id % 60),
            from_user=None,
            entities=None,
            media=None,
            forward_from=None,
            forward_sender_name=None,
            forward_date=None,
            edit_date=None,
            reply_to_message_id=None,
        )

    @pytest.mark.asyncio
    async def test_flood_wait_retries_and_resumes(self):
        """FloodWait triggers retry from last_offset_id, deduplicates messages."""
        from pyrogram.errors import FloodWait

        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()

        call_count = 0

        async def mock_get_chat_history(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                yield self._make_msg(2)
                raise FloodWait(value=1)
            else:
                # Second call: Pyrogram returns from beginning again
                yield self._make_msg(2)  # duplicate — should be skipped
                yield self._make_msg(1)

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        with patch("pyrogram_client.asyncio.sleep", new_callable=AsyncMock):
            messages = []
            async for msg in client.get_chat_history(123):
                messages.append(msg.id)

        assert messages == [2, 1], "Should yield msg 2 once (deduplicated) and msg 1"
        assert call_count == 2, "Should retry exactly once after FloodWait"

    @pytest.mark.asyncio
    async def test_flood_wait_max_retries_exceeded_raises(self):
        """FloodWait raises after MAX_RETRIES exceeded."""
        from pyrogram.errors import FloodWait
        from config import settings

        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()

        async def mock_get_chat_history(*args, **kwargs):
            raise FloodWait(value=1)
            yield  # make it an async generator

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        with patch("pyrogram_client.asyncio.sleep", new_callable=AsyncMock):
            with pytest.raises(FloodWait):
                async for _ in client.get_chat_history(123):
                    pass


    @pytest.mark.asyncio
    async def test_on_floodwait_callback_called_with_wait_time(self):
        """on_floodwait callback вызывается с временем ожидания при FloodWait."""
        from pyrogram.errors import FloodWait

        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()

        call_count = 0

        async def mock_get_chat_history(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                yield self._make_msg(1)
                raise FloodWait(value=30)
            else:
                yield self._make_msg(1)  # duplicate

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        floodwait_calls = []

        async def on_floodwait(wait_seconds):
            floodwait_calls.append(wait_seconds)

        with patch("pyrogram_client.asyncio.sleep", new_callable=AsyncMock):
            messages = []
            async for msg in client.get_chat_history(123, on_floodwait=on_floodwait):
                messages.append(msg.id)

        assert len(floodwait_calls) == 1, "on_floodwait должен быть вызван ровно один раз"
        assert floodwait_calls[0] >= 30, "wait_time должен быть >= значению FloodWait"

    @pytest.mark.asyncio
    async def test_no_on_floodwait_does_not_crash(self):
        """Если on_floodwait=None — FloodWait обрабатывается без ошибок."""
        from pyrogram.errors import FloodWait

        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()

        call_count = 0

        async def mock_get_chat_history(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise FloodWait(value=1)
                yield  # make it async generator
            else:
                yield self._make_msg(1)

        mock_pyrogram.get_chat_history = mock_get_chat_history
        client.client = mock_pyrogram

        with patch("pyrogram_client.asyncio.sleep", new_callable=AsyncMock):
            messages = []
            async for msg in client.get_chat_history(123, on_floodwait=None):
                messages.append(msg.id)

        assert messages == [1]


class TestTelegramClientMessageCount:
    """Test message count methods."""

    @pytest.mark.asyncio
    async def test_get_chat_messages_count_success(self):
        """Test total count for full chat."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()
        mock_pyrogram.get_chat_history_count = AsyncMock(return_value=5000)
        client.client = mock_pyrogram

        result = await client.get_chat_messages_count(123)

        assert result == 5000

    @pytest.mark.asyncio
    async def test_get_chat_messages_count_zero_returns_none(self):
        """Test that zero count returns None."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.get_chat_history_count = AsyncMock(return_value=0)
        client.client = mock_pyrogram

        result = await client.get_chat_messages_count(123)

        assert result is None

    @pytest.mark.asyncio
    async def test_get_chat_messages_count_error_returns_none(self):
        """Test that API error returns None."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.get_chat_history_count = AsyncMock(side_effect=Exception("API error"))
        client.client = mock_pyrogram

        result = await client.get_chat_messages_count(123)

        assert result is None

    @pytest.mark.asyncio
    async def test_get_date_range_count_success(self):
        """
        Два вызова GetHistory (before to_date и before from_date), разность — счётчик диапазона.
        """
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()
        mock_pyrogram.resolve_peer = AsyncMock(return_value=MagicMock())

        # First call (offset_date = to_date+1): 1500 total messages before end date
        result_to = MagicMock()
        result_to.count = 1500
        # Second call (offset_date = from_date): 1456 total messages before start date
        result_from = MagicMock()
        result_from.count = 1456
        mock_pyrogram.invoke = AsyncMock(side_effect=[result_to, result_from])
        client.client = mock_pyrogram

        result = await client.get_date_range_count(
            123, datetime(2025, 1, 1), datetime(2025, 1, 31)
        )

        # 1500 - 1456 = 44 messages in the date range
        assert result == 44
        assert mock_pyrogram.invoke.call_count == 2

    @pytest.mark.asyncio
    async def test_get_date_range_count_no_count_attr_returns_none(self):
        """Если GetHistory не вернул count — возвращаем None."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()
        mock_pyrogram.resolve_peer = AsyncMock(return_value=MagicMock())

        result_no_count = MagicMock(spec=[])  # no count attr
        mock_pyrogram.invoke = AsyncMock(return_value=result_no_count)
        client.client = mock_pyrogram

        result = await client.get_date_range_count(
            123, datetime(2025, 1, 1), datetime(2025, 1, 31)
        )

        assert result is None

    @pytest.mark.asyncio
    async def test_get_date_range_count_error_returns_none(self):
        """Test that error returns None."""
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()
        mock_pyrogram.resolve_peer = AsyncMock(side_effect=Exception("API error"))
        client.client = mock_pyrogram

        result = await client.get_date_range_count(
            123, datetime(2025, 1, 1), datetime(2025, 1, 31)
        )

        assert result is None

    @pytest.mark.asyncio
    async def test_get_messages_count_without_dates_uses_fast_path(self):
        """Without dates, uses get_chat_history_count (fast path)."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_pyrogram.get_chat_history_count = AsyncMock(return_value=10000)
        client.client = mock_pyrogram

        result = await client.get_messages_count(123)

        assert result == 10000
        mock_pyrogram.get_chat_history_count.assert_called_once()

    @pytest.mark.asyncio
    async def test_get_messages_count_with_dates_uses_raw_api(self):
        """With dates, uses raw MTProto GetHistory.

        get_date_range_count делает ДВА вызова GetHistory (count_to и count_from),
        чтобы вычислить количество в диапазоне как (count_to - count_from).
        Тест моделирует это: count_to=500 (всего до to_date),
        count_from=0 (нет сообщений до from_date) → result = 500.
        """
        client = TelegramClient()
        client.is_connected = True
        mock_pyrogram = AsyncMock()
        mock_pyrogram.resolve_peer = AsyncMock(return_value=MagicMock())

        result_to = MagicMock()
        result_to.count = 500
        result_from = MagicMock()
        result_from.count = 0
        mock_pyrogram.invoke = AsyncMock(side_effect=[result_to, result_from])
        client.client = mock_pyrogram

        result = await client.get_messages_count(
            123, datetime(2025, 1, 1), datetime(2025, 1, 31)
        )

        assert result == 500
        # Two GetHistory calls: один для верхней границы, один для нижней
        assert mock_pyrogram.invoke.call_count == 2
        mock_pyrogram.get_chat_history_count.assert_not_called()


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


@pytest.mark.asyncio
class TestResolveNumericChatIdWithCanonicalMapping:
    """Test that _resolve_numeric_chat_id saves canonical mapping after successful resolve."""

    async def test_fallback_1_saves_canonical_mapping_with_username(self):
        """After dialog sync resolve (fallback 1), save canonical:{id}→username to Redis."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_redis = AsyncMock()

        # Mock get_dialogs (fallback 1) — должен быть async generator function,
        # потому что реализация делает `async for _ in self.client.get_dialogs():`.
        # AsyncMock возвращает coroutine, что НЕ работает с async for и даёт
        # RuntimeWarning (coroutine never awaited).
        async def fake_get_dialogs(*args, **kwargs):
            return
            yield  # makes this an async generator function

        mock_pyrogram.get_dialogs = fake_get_dialogs

        # Mock get_chat — returns a chat with username
        mock_chat = MagicMock()
        mock_chat.id = -1001234567890
        mock_chat.username = "testchannel"
        mock_pyrogram.get_chat = AsyncMock(return_value=mock_chat)

        client.client = mock_pyrogram
        client.redis_client = mock_redis

        # Call _resolve_numeric_chat_id
        is_accessible, chat_info, error = await client._resolve_numeric_chat_id(-1001234567890)

        # Verify resolve succeeded
        assert is_accessible is True
        assert error is None
        assert chat_info is not None

        # Verify canonical mapping was saved
        mock_redis.set.assert_called_once()
        call_args = mock_redis.set.call_args
        assert call_args[0][0] == "canonical:-1001234567890"
        assert call_args[0][1] == "testchannel"
        assert call_args[1]["ex"] == 86400 * 30

    async def test_fallback_2_saves_canonical_mapping_with_username(self):
        """After raw MTProto resolve (fallback 2), save canonical:{id}→username to Redis."""
        from pyrogram import types
        from pyrogram.errors import ChannelPrivate
        from unittest.mock import AsyncMock
        import pyrogram.raw.types as raw_types
        import pyrogram.raw.functions as functions

        client = TelegramClient()
        mock_pyrogram = AsyncMock()
        mock_redis = AsyncMock()

        # Mock get_dialogs to fail (fallback 1 fails). Реализация делает
        # `async for _ in self.client.get_dialogs():` — нужно async generator
        # function, который бросает при первой итерации, а не AsyncMock
        # (последний вернул бы coroutine, не итерируемую через async for).
        async def failing_get_dialogs(*args, **kwargs):
            raise Exception("Not in dialogs")
            yield  # makes this an async generator function

        mock_pyrogram.get_dialogs = failing_get_dialogs

        # Mock invoke for raw MTProto (fallback 2)
        mock_pyrogram.invoke = AsyncMock()

        # Mock get_chat — returns a chat with username
        mock_chat = MagicMock()
        mock_chat.id = -1001234567899
        mock_chat.username = "publicchannel"
        mock_pyrogram.get_chat = AsyncMock(return_value=mock_chat)

        client.client = mock_pyrogram
        client.redis_client = mock_redis

        # Call _resolve_numeric_chat_id with a channel ID (< -1000000000000)
        is_accessible, chat_info, error = await client._resolve_numeric_chat_id(-1001234567899)

        # Verify resolve succeeded
        assert is_accessible is True
        assert error is None

        # Verify canonical mapping was saved
        mock_redis.set.assert_called_once()
        call_args = mock_redis.set.call_args
        assert call_args[0][0] == "canonical:-1001234567899"
        assert call_args[0][1] == "publicchannel"

    async def test_fallback_1_no_redis_client_does_not_crash(self):
        """If redis_client is None, should not crash (graceful degradation)."""
        client = TelegramClient()
        mock_pyrogram = AsyncMock()

        # Mock get_dialogs (fallback 1) — должен быть async generator function,
        # потому что реализация делает `async for _ in self.client.get_dialogs():`.
        # AsyncMock возвращает coroutine, что НЕ работает с async for и даёт
        # RuntimeWarning (coroutine never awaited).
        async def fake_get_dialogs(*args, **kwargs):
            return
            yield  # makes this an async generator function

        mock_pyrogram.get_dialogs = fake_get_dialogs

        # Mock get_chat
        mock_chat = MagicMock()
        mock_chat.id = -1001234567890
        mock_chat.username = "testchannel"
        mock_pyrogram.get_chat = AsyncMock(return_value=mock_chat)

        client.client = mock_pyrogram
        client.redis_client = None  # No Redis

        # Should not crash
        is_accessible, chat_info, error = await client._resolve_numeric_chat_id(-1001234567890)

        assert is_accessible is True
        assert error is None
