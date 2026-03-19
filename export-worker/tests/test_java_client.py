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

    async def test_send_response_no_messages_returns_true(self):
        """Test that empty messages returns True immediately."""
        with patch('java_client.settings'):
            with patch.object(JavaBotClient, '_upload_to_java'):
                client = JavaBotClient()
                result = await client.send_response(
                    task_id="test_1",
                    status="completed",
                    messages=[],  # Empty
                    user_chat_id=123
                )

                # Should return True (job finished cleanly)
                assert result is True

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
