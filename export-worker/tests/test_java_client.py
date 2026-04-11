"""
Tests for java_client.py — Java API client integration.

Tests critical business logic:
- Result JSON building with correct structure
- HTTP retry logic with backoff
- Error handling (400, 401, 500)
- User notification flow
"""

import pytest
import json
from unittest.mock import AsyncMock, patch, MagicMock
import httpx

from models import ExportedMessage, ExportResponse
from java_client import JavaBotClient


@pytest.mark.asyncio
class TestJavaClientBuildResultJson:
    """Test result.json building."""

    async def test_build_result_json_structure(self):
        """Test that result.json has correct structure."""
        messages = [
            ExportedMessage(
                id=1,
                type="message",
                date="2025-06-24T15:29:46",
                text="Hello",
                from_user="John"
            ),
            ExportedMessage(
                id=2,
                type="message",
                date="2025-06-24T15:30:00",
                text="World"
            )
        ]

        with patch('java_client.settings'):
            client = JavaBotClient()
            result = client._build_result_json(messages)

        # Verify structure
        assert isinstance(result, dict)
        assert result['type'] == 'personal_chat'
        assert result['name'] == 'Telegram Export'
        assert 'messages' in result
        assert len(result['messages']) == 2

        # Verify message content
        assert result['messages'][0]['id'] == 1
        assert result['messages'][0]['text'] == 'Hello'
        assert result['messages'][0]['from_user'] == 'John'

    async def test_build_result_json_excludes_none(self):
        """Test that None fields are excluded from JSON."""
        messages = [
            ExportedMessage(
                id=1,
                type="message",
                date="2025-06-24T15:29:46",
                text="Hello",
                from_user=None,  # Should be excluded
                edited=None      # Should be excluded
            )
        ]

        with patch('java_client.settings'):
            client = JavaBotClient()
            result = client._build_result_json(messages)

        # Verify None fields are excluded
        msg = result['messages'][0]
        assert 'from_user' not in msg
        assert 'edited' not in msg
        assert msg['text'] == 'Hello'

    async def test_build_result_json_empty_messages(self):
        """Test building result.json with empty messages list."""
        messages = []

        with patch('java_client.settings'):
            client = JavaBotClient()
            result = client._build_result_json(messages)

        assert result['type'] == 'personal_chat'
        assert result['messages'] == []


@pytest.mark.asyncio
class TestJavaClientUpload:
    """Test Java API upload logic."""

    async def test_upload_to_java_success(self):
        """Test successful upload to Java API."""
        result_json = {"test": "data"}
        result_bytes = json.dumps(result_json).encode('utf-8')

        with patch('java_client.settings') as mock_settings:
            mock_settings.RETRY_BASE_DELAY = 1.0
            mock_settings.RETRY_MAX_DELAY = 60.0

            with patch('java_client.httpx.AsyncClient') as mock_client_class:
                mock_response = AsyncMock()
                mock_response.status_code = 200
                mock_response.text = "cleaned markdown text"

                mock_client = AsyncMock()
                mock_client.post = AsyncMock(return_value=mock_response)
                mock_client.__aenter__ = AsyncMock(return_value=mock_client)
                mock_client.__aexit__ = AsyncMock(return_value=None)
                mock_client_class.return_value = mock_client

                client = JavaBotClient(base_url="http://localhost:8080")
                result = await client._upload_to_java(result_bytes)

                assert result == "cleaned markdown text"
                mock_client.post.assert_called_once()

    async def test_upload_to_java_400_no_retry(self):
        """Test that 400 error doesn't retry."""
        result_json = {"test": "data"}
        result_bytes = json.dumps(result_json).encode('utf-8')

        with patch('java_client.settings') as mock_settings:
            mock_settings.RETRY_BASE_DELAY = 1.0
            mock_settings.RETRY_MAX_DELAY = 60.0

            with patch('java_client.httpx.AsyncClient') as mock_client_class:
                mock_response = AsyncMock()
                mock_response.status_code = 400
                mock_response.text = "Bad request"

                mock_client = AsyncMock()
                mock_client.post = AsyncMock(return_value=mock_response)
                mock_client.__aenter__ = AsyncMock(return_value=mock_client)
                mock_client.__aexit__ = AsyncMock(return_value=None)
                mock_client_class.return_value = mock_client

                client = JavaBotClient(max_retries=3)
                result = await client._upload_to_java(result_bytes)

                # Should return None immediately
                assert result is None
                # POST called only once (no retries)
                mock_client.post.assert_called_once()

    async def test_upload_to_java_500_retries(self):
        """Test that 500 error retries and eventually fails."""
        result_json = {"test": "data"}
        result_bytes = json.dumps(result_json).encode('utf-8')

        with patch('java_client.settings') as mock_settings:
            mock_settings.RETRY_BASE_DELAY = 0.01  # Short delay for testing
            mock_settings.RETRY_MAX_DELAY = 60.0

            with patch('java_client.httpx.AsyncClient') as mock_client_class:
                mock_response = AsyncMock()
                mock_response.status_code = 500
                mock_response.text = "Server error"

                mock_client = AsyncMock()
                mock_client.post = AsyncMock(return_value=mock_response)
                mock_client.__aenter__ = AsyncMock(return_value=mock_client)
                mock_client.__aexit__ = AsyncMock(return_value=None)
                mock_client_class.return_value = mock_client

                with patch('java_client.asyncio.sleep', new_callable=AsyncMock):
                    client = JavaBotClient(max_retries=2)
                    result = await client._upload_to_java(result_bytes)

                    # Should return None after retries exhausted
                    assert result is None
                    # POST called 3 times (initial + 2 retries)
                    assert mock_client.post.call_count == 3

    async def test_upload_to_java_retries_then_success(self):
        """Test retry succeeds on subsequent attempt."""
        result_json = {"test": "data"}
        result_bytes = json.dumps(result_json).encode('utf-8')

        with patch('java_client.settings') as mock_settings:
            mock_settings.RETRY_BASE_DELAY = 0.01
            mock_settings.RETRY_MAX_DELAY = 60.0

            with patch('java_client.httpx.AsyncClient') as mock_client_class:
                # First two calls fail, third succeeds
                mock_responses = [
                    AsyncMock(status_code=500, text="Error"),
                    AsyncMock(status_code=500, text="Error"),
                    AsyncMock(status_code=200, text="Success!"),
                ]

                mock_client = AsyncMock()
                mock_client.post = AsyncMock(side_effect=mock_responses)
                mock_client.__aenter__ = AsyncMock(return_value=mock_client)
                mock_client.__aexit__ = AsyncMock(return_value=None)
                mock_client_class.return_value = mock_client

                with patch('java_client.asyncio.sleep', new_callable=AsyncMock):
                    client = JavaBotClient(max_retries=3)
                    result = await client._upload_to_java(result_bytes)

                    # Should succeed
                    assert result == "Success!"
                    # Called 3 times
                    assert mock_client.post.call_count == 3


@pytest.mark.asyncio
class TestJavaClientSendResponse:
    """Test send_response method."""

    async def test_send_response_no_messages_notifies_user(self):
        """При пустом результате пользователь должен получить уведомление."""
        with patch('java_client.settings'):
            with patch.object(JavaBotClient, '_notify_user_empty', new_callable=AsyncMock) as mock_notify:
                client = JavaBotClient()
                client.bot_token = "test_token"
                result = await client.send_response(
                    task_id="test_1",
                    status="completed",
                    messages=[],
                    user_chat_id=123
                )

                assert result is True
                mock_notify.assert_called_once_with(123, "test_1")

    async def test_send_response_no_messages_no_bot_token_no_notify(self):
        """Без bot_token уведомление не отправляется (нет куда)."""
        with patch('java_client.settings'):
            with patch.object(JavaBotClient, '_notify_user_empty', new_callable=AsyncMock) as mock_notify:
                client = JavaBotClient()
                client.bot_token = None
                result = await client.send_response(
                    task_id="test_1",
                    status="completed",
                    messages=[],
                    user_chat_id=123
                )

                assert result is True
                mock_notify.assert_not_called()

    async def test_send_response_failed_status_returns_true(self):
        """Test that failed status returns True."""
        with patch('java_client.settings'):
            with patch.object(JavaBotClient, '_notify_user_failure', new_callable=AsyncMock):
                client = JavaBotClient()
                result = await client.send_response(
                    task_id="test_1",
                    status="failed",
                    messages=[],
                    error="Export failed",
                    error_code="CHAT_PRIVATE",
                    user_chat_id=123
                )

                # Should return True (job finished cleanly with error)
                assert result is True

    async def test_send_response_calls_upload_and_send(self):
        """Test successful flow calls upload and file delivery."""
        messages = [
            ExportedMessage(
                id=1,
                type="message",
                date="2025-06-24T15:29:46",
                text="Test"
            )
        ]

        with patch('java_client.settings'):
            with patch.object(JavaBotClient, '_upload_to_java', new_callable=AsyncMock) as mock_upload:
                with patch.object(JavaBotClient, '_send_file_to_user', new_callable=AsyncMock) as mock_send:
                    mock_upload.return_value = "cleaned text"

                    client = JavaBotClient()
                    result = await client.send_response(
                        task_id="test_1",
                        status="completed",
                        messages=messages,
                        user_chat_id=123
                    )

                    # Both methods should be called
                    assert result is True
                    mock_upload.assert_called_once()
                    mock_send.assert_called_once()


@pytest.mark.asyncio
class TestJavaClientNotifications:
    """Test user notification methods."""

    async def test_send_file_to_user_success(self):
        """Test successful file delivery to user."""
        with patch('java_client.settings') as mock_settings:
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"

            with patch('java_client.httpx.AsyncClient') as mock_client_class:
                mock_response = AsyncMock()
                mock_response.status_code = 200

                mock_client = AsyncMock()
                mock_client.post = AsyncMock(return_value=mock_response)
                mock_client.__aenter__ = AsyncMock(return_value=mock_client)
                mock_client.__aexit__ = AsyncMock(return_value=None)
                mock_client_class.return_value = mock_client

                client = JavaBotClient()
                result = await client._send_file_to_user(
                    user_chat_id=123,
                    task_id="test_1",
                    cleaned_text="export content"
                )

                assert result is True
                mock_client.post.assert_called_once()

    async def test_notify_user_failure(self):
        """Test failure notification is sent."""
        with patch('java_client.settings') as mock_settings:
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"

            with patch('java_client.httpx.AsyncClient') as mock_client_class:
                mock_response = AsyncMock()
                mock_response.status_code = 200

                mock_client = AsyncMock()
                mock_client.post = AsyncMock(return_value=mock_response)
                mock_client.__aenter__ = AsyncMock(return_value=mock_client)
                mock_client.__aexit__ = AsyncMock(return_value=None)
                mock_client_class.return_value = mock_client

                client = JavaBotClient()
                await client._notify_user_failure(
                    user_chat_id=123,
                    task_id="test_1",
                    error="Chat not accessible"
                )

                # sendMessage should be called
                mock_client.post.assert_called_once()
                # Verify message contains error info
                call_args = mock_client.post.call_args
                assert "test_1" in str(call_args)


class TestSplitTextBySize:
    """Test file splitting for large exports."""

    def test_small_text_single_part(self):
        """Text under limit stays as single part."""
        text = "Hello\nWorld\n"
        parts = JavaBotClient._split_text_by_size(text, max_bytes=1000)
        assert len(parts) == 1
        assert parts[0] == "Hello\nWorld\n"

    def test_split_on_line_boundaries(self):
        """Split respects line boundaries."""
        # Each line ~10 bytes
        lines = [f"Line {i:04d}" for i in range(100)]
        text = "\n".join(lines)
        # Max 50 bytes per part → ~5 lines per part
        parts = JavaBotClient._split_text_by_size(text, max_bytes=50)
        assert len(parts) > 1
        # Reassembled text matches original
        reassembled = "\n".join(parts)
        assert reassembled == text

    def test_empty_text(self):
        """Empty text returns single empty part."""
        parts = JavaBotClient._split_text_by_size("", max_bytes=100)
        assert len(parts) == 1

    def test_single_long_line_exceeds_limit(self):
        """A single line longer than limit still gets included."""
        text = "A" * 200
        parts = JavaBotClient._split_text_by_size(text, max_bytes=100)
        # Single line can't be split, so it goes into one part
        assert len(parts) == 1
        assert parts[0] == text

    def test_exact_boundary(self):
        """Lines exactly at boundary don't create empty parts."""
        text = "12345\n12345\n12345"  # 3 lines, ~6 bytes each
        parts = JavaBotClient._split_text_by_size(text, max_bytes=12)
        assert all(len(p) > 0 for p in parts)
        assert "\n".join(parts) == text

    def test_utf8_multibyte_characters(self):
        """Split correctly handles UTF-8 multibyte (Russian text)."""
        # "Привет" = 12 bytes in UTF-8
        lines = ["Привет"] * 10
        text = "\n".join(lines)
        parts = JavaBotClient._split_text_by_size(text, max_bytes=30)
        assert len(parts) > 1
        reassembled = "\n".join(parts)
        assert reassembled == text


@pytest.mark.asyncio
class TestSendFileToUserSplit:
    """Test file delivery with splitting."""

    async def test_send_small_file_no_split(self):
        """Small file sent as single document."""
        with patch('java_client.settings') as mock_settings:
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"
            with patch('java_client.httpx.AsyncClient') as mock_client_class:
                mock_response = AsyncMock()
                mock_response.status_code = 200
                mock_client = AsyncMock()
                mock_client.post = AsyncMock(return_value=mock_response)
                mock_client_class.return_value = mock_client

                client = JavaBotClient()
                result = await client._send_file_to_user(123, "task_1", "small text")

                assert result is True
                assert mock_client.post.call_count == 1

    async def test_send_large_file_splits(self):
        """File > 45MB is split into multiple parts."""
        with patch('java_client.settings') as mock_settings:
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"
            with patch('java_client.httpx.AsyncClient') as mock_client_class:
                mock_response = AsyncMock()
                mock_response.status_code = 200
                mock_client = AsyncMock()
                mock_client.post = AsyncMock(return_value=mock_response)
                mock_client_class.return_value = mock_client

                client = JavaBotClient()
                # Create text > 45MB
                line = "A" * 1000 + "\n"
                big_text = line * 50000  # ~50MB
                result = await client._send_file_to_user(123, "task_1", big_text)

                assert result is True
                assert mock_client.post.call_count >= 2  # At least 2 parts

    async def test_send_response_returns_false_on_delivery_failure(self):
        """send_response returns False if file delivery fails."""
        messages = [
            ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="Test")
        ]
        with patch('java_client.settings'):
            with patch.object(JavaBotClient, '_upload_to_java', new_callable=AsyncMock) as mock_upload:
                with patch.object(JavaBotClient, '_send_file_to_user', new_callable=AsyncMock) as mock_send:
                    with patch.object(JavaBotClient, '_notify_user_failure', new_callable=AsyncMock) as mock_notify:
                        mock_upload.return_value = "cleaned text"
                        mock_send.return_value = False  # Delivery failed

                        client = JavaBotClient()
                        result = await client.send_response(
                            task_id="test_1", status="completed",
                            messages=messages, user_chat_id=123
                        )

                        assert result is False
                        mock_notify.assert_called_once()


class TestBuildFilename:
    """Test filename generation from chat title and dates."""

    def test_full_export_with_title(self):
        """Chat title with no dates → title_all.txt."""
        result = JavaBotClient._build_filename("Pavel Durov")
        assert result == "Pavel_Durov_all.txt"

    def test_date_range_export(self):
        """Chat title with date range."""
        result = JavaBotClient._build_filename(
            "Pavel Durov", "2025-01-01T00:00:00", "2025-12-31T23:59:59"
        )
        assert result == "Pavel_Durov_2025-01-01_2025-12-31.txt"

    def test_no_title_fallback(self):
        """No title → export_all.txt."""
        result = JavaBotClient._build_filename(None)
        assert result == "export_all.txt"

    def test_from_date_only(self):
        """Only from_date."""
        result = JavaBotClient._build_filename("Chat", "2025-06-01T00:00:00")
        assert result == "Chat_from_2025-06-01.txt"

    def test_to_date_only(self):
        """Only to_date."""
        result = JavaBotClient._build_filename("Chat", None, "2025-06-01T00:00:00")
        assert result == "Chat_to_2025-06-01.txt"

    def test_sanitize_special_chars(self):
        """Special characters removed from filename."""
        result = JavaBotClient._build_filename("Chat/Name: (test)")
        assert "/" not in result
        assert ":" not in result

    def test_sanitize_long_title(self):
        """Long title truncated."""
        long_title = "A" * 200
        result = JavaBotClient._build_filename(long_title)
        # Base name limited to 80 chars + _all.txt
        assert len(result) <= 90


class TestEntityTransformation:
    """Test text entity transformation from Bot API to Desktop export format."""

    def test_transform_single_bold_entity(self):
        """Test transforming single bold entity."""
        text = "Hello world"
        entities = [{"type": "bold", "offset": 0, "length": 5}]

        result = JavaBotClient._transform_entities(text, entities)

        assert len(result) == 1
        assert result[0]["type"] == "bold"
        assert result[0]["text"] == "Hello"
        assert "offset" not in result[0]
        assert "length" not in result[0]

    def test_transform_multiple_entities(self):
        """Test transforming multiple entities."""
        text = "Hello world"
        entities = [
            {"type": "bold", "offset": 0, "length": 5},
            {"type": "italic", "offset": 6, "length": 5}
        ]

        result = JavaBotClient._transform_entities(text, entities)

        assert len(result) == 2
        assert result[0]["type"] == "bold"
        assert result[0]["text"] == "Hello"
        assert result[1]["type"] == "italic"
        assert result[1]["text"] == "world"

    def test_transform_entity_with_url(self):
        """Test transforming text_url entity with href."""
        text = "Click here"
        entities = [
            {"type": "text_url", "offset": 0, "length": 10, "url": "https://example.com"}
        ]

        result = JavaBotClient._transform_entities(text, entities)

        assert result[0]["type"] == "text_url"
        assert result[0]["text"] == "Click here"
        assert result[0]["href"] == "https://example.com"
        assert "url" not in result[0]  # Renamed to href

    def test_transform_entity_with_user_id(self):
        """Test transforming text_mention entity with user_id."""
        text = "Mention @john"
        entities = [
            {"type": "text_mention", "offset": 8, "length": 5, "user_id": 12345}
        ]

        result = JavaBotClient._transform_entities(text, entities)

        assert result[0]["type"] == "text_mention"
        assert result[0]["text"] == "@john"
        assert result[0]["user_id"] == 12345

    def test_transform_empty_entities_list(self):
        """Test with empty entities list."""
        text = "Hello world"
        entities = []

        result = JavaBotClient._transform_entities(text, entities)

        assert result == []

    def test_transform_none_entities(self):
        """Test with None entities."""
        text = "Hello world"

        result = JavaBotClient._transform_entities(text, None)

        assert result is None

    def test_transform_empty_text(self):
        """Test with empty text."""
        entities = [{"type": "bold", "offset": 0, "length": 5}]

        result = JavaBotClient._transform_entities("", entities)

        assert result == entities  # Returns original on empty text

    def test_transform_malformed_offset_length(self):
        """Test with invalid offset/length (graceful degradation)."""
        text = "Hello"
        entities = [{"type": "bold", "offset": 10, "length": 5}]  # Out of bounds

        result = JavaBotClient._transform_entities(text, entities)

        # Should still work, extracting empty string
        assert result[0]["type"] == "bold"
        assert result[0]["text"] == ""  # Empty slice

    def test_transform_entities_preserves_other_fields(self):
        """Test that other entity fields are preserved."""
        text = "code block"
        entities = [
            {
                "type": "pre",
                "offset": 0,
                "length": 10,
                "language": "python"
            }
        ]

        result = JavaBotClient._transform_entities(text, entities)

        assert result[0]["type"] == "pre"
        assert result[0]["text"] == "code block"
        # Language is preserved (though not used by MarkdownParser currently)
        assert result[0]["language"] == "python"

    def test_build_result_json_transforms_entities(self):
        """Test that _build_result_json calls entity transformation."""
        messages = [
            ExportedMessage(
                id=1,
                type="message",
                date="2025-06-24T15:29:46",
                text="Hello world",
                text_entities=[
                    {"type": "bold", "offset": 0, "length": 5},
                    {"type": "italic", "offset": 6, "length": 5}
                ]
            )
        ]

        with patch('java_client.settings'):
            client = JavaBotClient()
            result = client._build_result_json(messages)

        msg = result["messages"][0]
        assert msg["text"] == "Hello world"
        assert len(msg["text_entities"]) == 2
        assert msg["text_entities"][0]["type"] == "bold"
        assert msg["text_entities"][0]["text"] == "Hello"
        assert "offset" not in msg["text_entities"][0]
        assert msg["text_entities"][1]["type"] == "italic"
        assert msg["text_entities"][1]["text"] == "world"


@pytest.mark.asyncio
class TestUpdateQueuePosition:
    """Tests for update_queue_position."""

    async def test_sends_position_message(self):
        """Should edit message with queue position text."""
        with patch('java_client.settings') as mock_settings:
            mock_settings.JAVA_API_BASE_URL = "http://localhost:8080"
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"

            client = JavaBotClient()
            mock_response = MagicMock()
            mock_response.status_code = 200
            client._http_client = AsyncMock()
            client._http_client.post = AsyncMock(return_value=mock_response)

            await client.update_queue_position(
                user_chat_id=123, msg_id=456, position=2, total=5
            )

            client._http_client.post.assert_called_once()
            call_args = client._http_client.post.call_args
            assert "editMessageText" in call_args[0][0]
            data = call_args[1]["data"]
            assert data["chat_id"] == 123
            assert data["message_id"] == 456
            assert "2" in data["text"]
            assert "5" in data["text"]

    async def test_sends_started_message_when_position_zero(self):
        """Should send 'started' message when position is 0."""
        with patch('java_client.settings') as mock_settings:
            mock_settings.JAVA_API_BASE_URL = "http://localhost:8080"
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"

            client = JavaBotClient()
            mock_response = MagicMock()
            mock_response.status_code = 200
            client._http_client = AsyncMock()
            client._http_client.post = AsyncMock(return_value=mock_response)

            await client.update_queue_position(
                user_chat_id=123, msg_id=456, position=0, total=0
            )

            data = client._http_client.post.call_args[1]["data"]
            assert "начата" in data["text"]

    async def test_handles_http_error_gracefully(self):
        """Should not raise on HTTP error."""
        with patch('java_client.settings') as mock_settings:
            mock_settings.JAVA_API_BASE_URL = "http://localhost:8080"
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"

            client = JavaBotClient()
            client._http_client = AsyncMock()
            client._http_client.post = AsyncMock(side_effect=Exception("connection refused"))

            # Should not raise
            await client.update_queue_position(
                user_chat_id=123, msg_id=456, position=1, total=3
            )


@pytest.mark.asyncio
class TestProgressTracker:
    """Test ProgressTracker clamping to 100%."""

    async def test_send_progress_update_clamps_over_100_percent(self):
        """Progress percentage should never exceed 100%."""
        with patch('java_client.settings') as mock_settings:
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"

            client = JavaBotClient()
            client._http_client = AsyncMock()
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            # Simulate message_count > total (actual > estimated)
            result = await client.send_progress_update(
                user_chat_id=123,
                task_id="task_1",
                message_count=253432,
                total=244143,
                started=False
            )

            # Check that the text contains exactly 100%, not 103%
            call_args = client._http_client.post.call_args
            post_data = call_args[1]["data"]
            assert "100%" in post_data["text"]
            assert "103%" not in post_data["text"]

    def test_build_progress_bar_clamps_filled(self):
        """Progress bar should not exceed width even if percentage > 100."""
        client = JavaBotClient()

        # Test normal case
        bar = client._build_progress_bar(50)
        assert bar == "▓▓▓▓▓░░░░░"
        assert len(bar) == 10

        # Test 100%
        bar = client._build_progress_bar(100)
        assert bar == "▓▓▓▓▓▓▓▓▓▓"
        assert len(bar) == 10

        # Test >100% (should still be 10 filled blocks)
        bar = client._build_progress_bar(110)
        assert bar == "▓▓▓▓▓▓▓▓▓▓"
        assert len(bar) == 10

    async def test_finalize_uses_max_of_total_and_count(self):
        """finalize() should use max(total, count) to ensure percentage = 100%."""
        from java_client import ProgressTracker

        with patch('java_client.settings') as mock_settings:
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"

            mock_java_client = AsyncMock()
            mock_java_client.send_progress_update = AsyncMock(return_value=None)

            tracker = ProgressTracker(
                client=mock_java_client,
                user_chat_id=123,
                task_id="task_1"
            )

            # Simulate Telegram undercount: total=100, actual=110
            await tracker.start(total=100)
            await tracker.finalize(count=110)

            # Check finalize call arguments
            call_args = mock_java_client.send_progress_update.call_args_list[-1]
            assert call_args[1]["message_count"] == 110
            assert call_args[1]["total"] == 110  # max(100, 110) = 110

    async def test_send_progress_update_with_zero_total_shows_progress_bar(self):
        """total=0 should still render as a 0% progress bar, not a generic message."""
        with patch('java_client.settings') as mock_settings:
            mock_settings.TELEGRAM_BOT_TOKEN = "test_token"

            client = JavaBotClient()
            client._http_client = AsyncMock()
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            await client.send_progress_update(
                user_chat_id=123,
                task_id="task_1",
                message_count=0,
                total=0,
                started=True
            )

            call_args = client._http_client.post.call_args
            post_data = call_args[1]["data"]
            assert "0%" in post_data["text"]
            assert "(0 из 0)" in post_data["text"]

    async def test_set_total_updates_existing_progress_message(self):
        """set_total() should edit existing message instead of sending a new one."""
        from java_client import ProgressTracker

        mock_java_client = AsyncMock()
        mock_java_client.send_progress_update = AsyncMock(side_effect=[777, 777])

        tracker = ProgressTracker(
            client=mock_java_client,
            user_chat_id=123,
            task_id="task_1"
        )

        await tracker.start()
        await tracker.set_total(42)

        assert mock_java_client.send_progress_update.call_count == 2
        second_call = mock_java_client.send_progress_update.call_args_list[1]
        assert second_call[1]["message_count"] == 0
        assert second_call[1]["total"] == 42
        assert second_call[1]["progress_message_id"] == 777
