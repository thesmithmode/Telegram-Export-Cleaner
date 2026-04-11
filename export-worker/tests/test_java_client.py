"""
Tests for java_client.py — Java API client integration.

Covers the end-to-end send_response flow and its internal helpers:
- _stream_to_temp_json: streams messages to disk as result.json (O(1) RAM)
- _upload_file_to_java: HTTP upload with retry
- send_response: orchestration (failed / completed / delivery failure)
- _transform_entities: UTF-16 safe entity extraction
- _build_filename: filename generation from chat title + date range
- _split_text_by_size: text splitting on line boundaries
- _send_file_to_user: Telegram file delivery (single + split for >45MB)
- update_queue_position: Telegram queue status message
- ProgressTracker: throttled progress reporting with seed + ETA
"""

import os
import json
import tempfile
import pytest
from unittest.mock import AsyncMock, patch, MagicMock

from models import ExportedMessage
from java_client import JavaBotClient, ProgressTracker


# ---------- helpers -----------------------------------------------------


def _patch_settings(**overrides):
    """Return a `patch('java_client.settings')` context manager pre-configured
    with safe concrete primitives.

    JavaBotClient.__init__ reads settings.JAVA_API_BASE_URL and
    settings.TELEGRAM_BOT_TOKEN at construction time; leaving them as raw
    MagicMocks causes base_url/bot_token to leak into httpx/f-strings and
    breaks downstream logic. Always patch with real strings.
    """
    patcher = patch("java_client.settings")
    mock = patcher.start()
    mock.JAVA_API_BASE_URL = overrides.get("JAVA_API_BASE_URL", "http://localhost:8080")
    mock.TELEGRAM_BOT_TOKEN = overrides.get("TELEGRAM_BOT_TOKEN", "test_bot_token")
    mock.JAVA_API_KEY = overrides.get("JAVA_API_KEY", None)
    mock.RETRY_BASE_DELAY = overrides.get("RETRY_BASE_DELAY", 0.0)
    mock.RETRY_MAX_DELAY = overrides.get("RETRY_MAX_DELAY", 60.0)
    return patcher, mock


def _make_client(**overrides):
    """Build a JavaBotClient with mocked settings + mocked httpx client.

    Returns (client, patcher). Caller MUST call patcher.stop() in finally to
    restore settings. Using start/stop rather than `with` lets tests keep the
    patch active across nested try/finally blocks (e.g. for temp file cleanup).
    """
    patcher, _ = _patch_settings(**overrides)
    client = JavaBotClient(max_retries=overrides.get("max_retries", 3))
    client._http_client = AsyncMock()
    return client, patcher


def _make_temp_json(content: bytes = b'{"test": "data"}') -> str:
    """Create a temp file on disk and return its path."""
    fd, path = tempfile.mkstemp(suffix=".json")
    with os.fdopen(fd, "wb") as f:
        f.write(content)
    return path


# ---------- _stream_to_temp_json ----------------------------------------


@pytest.mark.asyncio
class TestStreamToTempJson:
    """Test streaming messages to a temp file as valid result.json."""

    async def test_writes_valid_json_with_messages(self):
        client, p = _make_client()
        try:
            messages = [
                ExportedMessage(id=1, type="message", date="2025-01-01T10:00:00", text="Hello"),
                ExportedMessage(id=2, type="message", date="2025-01-01T10:01:00", text="World"),
            ]
            path = await client._stream_to_temp_json(messages, count=2)
            try:
                with open(path, "rb") as f:
                    data = json.loads(f.read())
                assert data["type"] == "personal_chat"
                assert data["message_count"] == 2
                assert len(data["messages"]) == 2
                assert data["messages"][0]["id"] == 1
                assert data["messages"][0]["text"] == "Hello"
                assert data["messages"][1]["text"] == "World"
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_empty_messages_valid_json(self):
        client, p = _make_client()
        try:
            path = await client._stream_to_temp_json([], count=0)
            try:
                with open(path, "rb") as f:
                    data = json.loads(f.read())
                assert data["message_count"] == 0
                assert data["messages"] == []
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_excludes_none_fields(self):
        client, p = _make_client()
        try:
            messages = [
                ExportedMessage(
                    id=1,
                    type="message",
                    date="2025-01-01T10:00:00",
                    text="Only text",
                    from_user=None,
                )
            ]
            path = await client._stream_to_temp_json(messages, count=1)
            try:
                with open(path, "rb") as f:
                    data = json.loads(f.read())
                msg = data["messages"][0]
                assert "from_user" not in msg
                assert msg["text"] == "Only text"
            finally:
                os.unlink(path)
        finally:
            p.stop()


# ---------- _upload_file_to_java ----------------------------------------


@pytest.mark.asyncio
class TestUploadFileToJava:
    """Test the /api/convert upload with retry logic.

    `_upload_file_to_java` reads a file from disk and POSTs it to Java via
    httpx multipart. 400 → immediate None (no retry). 5xx → retry until
    max_retries exhausted. On success returns response.text.
    """

    async def test_success_returns_cleaned_text(self):
        client, p = _make_client()
        try:
            path = _make_temp_json()
            try:
                client._http_client.post = AsyncMock(
                    return_value=MagicMock(status_code=200, text="cleaned markdown")
                )
                result = await client._upload_file_to_java(path)
                assert result == "cleaned markdown"
                client._http_client.post.assert_called_once()
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_400_returns_none_without_retry(self):
        client, p = _make_client(max_retries=3)
        try:
            path = _make_temp_json()
            try:
                client._http_client.post = AsyncMock(
                    return_value=MagicMock(status_code=400, text="Bad request")
                )
                result = await client._upload_file_to_java(path)
                assert result is None
                assert client._http_client.post.call_count == 1
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_500_retries_until_exhausted(self):
        client, p = _make_client(max_retries=2)
        try:
            path = _make_temp_json()
            try:
                client._http_client.post = AsyncMock(
                    return_value=MagicMock(status_code=500, text="Server error")
                )
                with patch("java_client.asyncio.sleep", new_callable=AsyncMock):
                    result = await client._upload_file_to_java(path)
                assert result is None
                # initial + 2 retries = 3 attempts total
                assert client._http_client.post.call_count == 3
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_500_then_success_on_retry(self):
        client, p = _make_client(max_retries=3)
        try:
            path = _make_temp_json()
            try:
                resp_err = MagicMock(status_code=500, text="err")
                resp_ok = MagicMock(status_code=200, text="Success!")
                client._http_client.post = AsyncMock(
                    side_effect=[resp_err, resp_err, resp_ok]
                )
                with patch("java_client.asyncio.sleep", new_callable=AsyncMock):
                    result = await client._upload_file_to_java(path)
                assert result == "Success!"
                assert client._http_client.post.call_count == 3
            finally:
                os.unlink(path)
        finally:
            p.stop()


# ---------- send_response orchestration ---------------------------------


@pytest.mark.asyncio
class TestSendResponse:
    """Test send_response high-level orchestration."""

    async def test_failed_status_notifies_user_and_returns_true(self):
        client, p = _make_client()
        try:
            with patch.object(
                client, "_notify_user_failure", new_callable=AsyncMock
            ) as mock_notify:
                result = await client.send_response(
                    task_id="t1",
                    status="failed",
                    messages=[],
                    error="Export failed",
                    error_code="CHAT_PRIVATE",
                    user_chat_id=123,
                )
            assert result is True
            mock_notify.assert_called_once()
        finally:
            p.stop()

    async def test_completed_calls_upload_and_delivers(self):
        client, p = _make_client()
        try:
            messages = [
                ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="Test")
            ]
            with patch.object(
                client, "_upload_file_to_java", new_callable=AsyncMock
            ) as mock_upload, patch.object(
                client, "_send_file_to_user", new_callable=AsyncMock
            ) as mock_send:
                mock_upload.return_value = "cleaned text"
                mock_send.return_value = True

                result = await client.send_response(
                    task_id="t1",
                    status="completed",
                    messages=messages,
                    actual_count=1,
                    user_chat_id=123,
                    chat_title="Test Chat",
                )

            assert result is True
            mock_upload.assert_called_once()
            mock_send.assert_called_once()
        finally:
            p.stop()

    async def test_delivery_failure_returns_false(self):
        client, p = _make_client()
        try:
            messages = [
                ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="Test")
            ]
            with patch.object(
                client, "_upload_file_to_java", new_callable=AsyncMock
            ) as mock_upload, patch.object(
                client, "_send_file_to_user", new_callable=AsyncMock
            ) as mock_send, patch.object(
                client, "_notify_user_failure", new_callable=AsyncMock
            ) as mock_notify:
                mock_upload.return_value = "cleaned text"
                mock_send.return_value = False  # delivery failed

                result = await client.send_response(
                    task_id="t1",
                    status="completed",
                    messages=messages,
                    actual_count=1,
                    user_chat_id=123,
                )

            assert result is False
            mock_notify.assert_called_once()
        finally:
            p.stop()

    async def test_upload_returns_none_triggers_failure_notify(self):
        """When Java API is down, _upload_file_to_java returns None → user notified."""
        client, p = _make_client()
        try:
            messages = [
                ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="Test")
            ]
            with patch.object(
                client, "_upload_file_to_java", new_callable=AsyncMock
            ) as mock_upload, patch.object(
                client, "_notify_user_failure", new_callable=AsyncMock
            ) as mock_notify:
                mock_upload.return_value = None  # simulate Java API outage

                result = await client.send_response(
                    task_id="t1",
                    status="completed",
                    messages=messages,
                    actual_count=1,
                    user_chat_id=123,
                )

            assert result is False
            mock_notify.assert_called_once()
        finally:
            p.stop()


# ---------- _transform_entities (static) --------------------------------


class TestEntityTransformation:
    """Test text entity transformation from Bot API to Desktop export format.

    _transform_entities is UTF-16 safe: it encodes text as UTF-16-LE and slices
    by code unit offsets. Only fields {type, text, href, user_id} are preserved;
    extra fields (e.g. 'language' on pre blocks) are intentionally dropped.
    """

    def test_transform_single_bold_entity(self):
        text = "Hello world"
        entities = [{"type": "bold", "offset": 0, "length": 5}]
        result = JavaBotClient._transform_entities(text, entities)
        assert len(result) == 1
        assert result[0]["type"] == "bold"
        assert result[0]["text"] == "Hello"
        # offset/length replaced by extracted text, not duplicated
        assert "offset" not in result[0]
        assert "length" not in result[0]

    def test_transform_multiple_entities(self):
        text = "Hello world"
        entities = [
            {"type": "bold", "offset": 0, "length": 5},
            {"type": "italic", "offset": 6, "length": 5},
        ]
        result = JavaBotClient._transform_entities(text, entities)
        assert [e["type"] for e in result] == ["bold", "italic"]
        assert [e["text"] for e in result] == ["Hello", "world"]

    def test_transform_text_url_renames_url_to_href(self):
        text = "Click here"
        entities = [
            {"type": "text_url", "offset": 0, "length": 10, "url": "https://example.com"}
        ]
        result = JavaBotClient._transform_entities(text, entities)
        assert result[0]["type"] == "text_url"
        assert result[0]["text"] == "Click here"
        assert result[0]["href"] == "https://example.com"
        assert "url" not in result[0]  # renamed, not duplicated

    def test_transform_text_mention_preserves_user_id(self):
        text = "Mention @john"
        entities = [{"type": "text_mention", "offset": 8, "length": 5, "user_id": 12345}]
        result = JavaBotClient._transform_entities(text, entities)
        assert result[0]["type"] == "text_mention"
        assert result[0]["text"] == "@john"
        assert result[0]["user_id"] == 12345

    def test_transform_empty_entities_list(self):
        assert JavaBotClient._transform_entities("Hello", []) == []

    def test_transform_none_entities(self):
        assert JavaBotClient._transform_entities("Hello", None) is None

    def test_transform_empty_text(self):
        entities = [{"type": "bold", "offset": 0, "length": 5}]
        # empty text short-circuits — original entities returned as-is
        assert JavaBotClient._transform_entities("", entities) == entities

    def test_transform_malformed_offset_length_degrades_gracefully(self):
        """Out-of-bounds offsets produce empty extracted text, not a crash."""
        text = "Hello"
        entities = [{"type": "bold", "offset": 10, "length": 5}]
        result = JavaBotClient._transform_entities(text, entities)
        assert result[0]["type"] == "bold"
        assert result[0]["text"] == ""

    def test_transform_drops_unknown_fields(self):
        """Current contract: non-{type,text,href,user_id} fields are dropped.

        If _transform_entities is extended to preserve 'language' on pre/code
        blocks, update this test. Until then, keep it — MarkdownParser on the
        Java side doesn't consume 'language' anyway.
        """
        text = "code block"
        entities = [{"type": "pre", "offset": 0, "length": 10, "language": "python"}]
        result = JavaBotClient._transform_entities(text, entities)
        assert result[0]["type"] == "pre"
        assert result[0]["text"] == "code block"
        assert "language" not in result[0]


# ---------- _build_filename ---------------------------------------------


class TestBuildFilename:
    """Test filename generation from chat title and date range.

    Current contract:
      - BOTH f_date AND t_date set → "{base}_{f[:10]}_{t[:10]}.txt"
      - Otherwise → "{base}_all.txt"
      - Single-from / single-to do NOT produce special names (documented).
    """

    def test_full_export_with_title(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            assert client._build_filename("Pavel Durov", None, None) == "Pavel_Durov_all.txt"
        finally:
            p.stop()

    def test_date_range_export(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            result = client._build_filename(
                "Pavel Durov", "2025-01-01T00:00:00", "2025-12-31T23:59:59"
            )
            assert result == "Pavel_Durov_2025-01-01_2025-12-31.txt"
        finally:
            p.stop()

    def test_no_title_fallback(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            assert client._build_filename(None, None, None) == "export_all.txt"
        finally:
            p.stop()

    def test_only_from_date_falls_back_to_all(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            result = client._build_filename("Chat", "2025-06-01T00:00:00", None)
            assert result == "Chat_all.txt"
        finally:
            p.stop()

    def test_only_to_date_falls_back_to_all(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            result = client._build_filename("Chat", None, "2025-06-01T00:00:00")
            assert result == "Chat_all.txt"
        finally:
            p.stop()

    def test_sanitize_special_chars(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            result = client._build_filename("Chat/Name: (test)", None, None)
            assert "/" not in result
            assert ":" not in result
        finally:
            p.stop()

    def test_sanitize_long_title(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            result = client._build_filename("A" * 200, None, None)
            # base capped at 80 chars + "_all.txt"
            assert len(result) <= 90
        finally:
            p.stop()


# ---------- _split_text_by_size -----------------------------------------


class TestSplitTextBySize:
    """Test text splitting by byte budget, respecting line boundaries."""

    def test_small_text_single_part(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            parts = client._split_text_by_size("Hello\nWorld\n", 1000)
            assert len(parts) == 1
            assert parts[0] == "Hello\nWorld\n"
        finally:
            p.stop()

    def test_split_on_line_boundaries(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            lines = [f"Line {i:04d}" for i in range(100)]
            text = "\n".join(lines)
            parts = client._split_text_by_size(text, 50)
            assert len(parts) > 1
            assert "\n".join(parts) == text
        finally:
            p.stop()

    def test_empty_text(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            parts = client._split_text_by_size("", 100)
            assert len(parts) == 1
        finally:
            p.stop()

    def test_single_long_line_exceeds_limit(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            text = "A" * 200
            parts = client._split_text_by_size(text, 100)
            # unsplittable single line stays whole
            assert len(parts) == 1
            assert parts[0] == text
        finally:
            p.stop()

    def test_exact_boundary_no_empty_parts(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            text = "12345\n12345\n12345"
            parts = client._split_text_by_size(text, 12)
            assert all(len(part) > 0 for part in parts)
            assert "\n".join(parts) == text
        finally:
            p.stop()

    def test_utf8_multibyte_characters(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            lines = ["Привет"] * 10
            text = "\n".join(lines)
            parts = client._split_text_by_size(text, 30)
            assert len(parts) > 1
            assert "\n".join(parts) == text
        finally:
            p.stop()


# ---------- _send_file_to_user ------------------------------------------


@pytest.mark.asyncio
class TestSendFileToUser:
    """Test Telegram file delivery, single and split-over-45MB paths."""

    async def test_small_file_sent_as_single_document(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))
            result = await client._send_file_to_user(123, "task_1", "small text", "file.txt")
            assert result is True
            assert client._http_client.post.call_count == 1
        finally:
            p.stop()

    async def test_large_file_splits_into_multiple_parts(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            # ~47MB text (just over 45MB threshold) with newlines so it's splittable
            line = "A" * 999 + "\n"  # 1000 bytes per line
            big_text = line * 50_000  # ~50MB, ~50k lines → splits into ≥ 2 parts
            result = await client._send_file_to_user(123, "task_1", big_text, "big.txt")

            assert result is True
            assert client._http_client.post.call_count >= 2
        finally:
            p.stop()

    async def test_delivery_failure_returns_false(self):
        client, p = _make_client()
        try:
            # Telegram returns non-200 → _send_single_file returns False
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=500))
            result = await client._send_file_to_user(123, "task_1", "text", "file.txt")
            assert result is False
        finally:
            p.stop()


# ---------- _notify_user_failure ----------------------------------------


@pytest.mark.asyncio
class TestNotifyUserFailure:
    async def test_sends_failure_message_with_task_id_and_reason(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            await client._notify_user_failure(123, "task_1", "Chat not accessible")

            client._http_client.post.assert_called_once()
            call_args = client._http_client.post.call_args
            data = call_args[1]["data"]
            assert data["chat_id"] == 123
            assert "task_1" in data["text"]
            assert "Chat not accessible" in data["text"]
        finally:
            p.stop()

    async def test_swallows_http_errors(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(side_effect=Exception("connection refused"))
            # Must not raise
            await client._notify_user_failure(123, "task_1", "err")
        finally:
            p.stop()


# ---------- update_queue_position ---------------------------------------


@pytest.mark.asyncio
class TestUpdateQueuePosition:
    async def test_sends_position_message(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

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
        finally:
            p.stop()

    async def test_sends_started_message_when_position_zero(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            await client.update_queue_position(
                user_chat_id=123, msg_id=456, position=0, total=0
            )

            data = client._http_client.post.call_args[1]["data"]
            # Реальный текст в коде: "⏳ Ваш экспорт начался..."
            assert "начался" in data["text"]
        finally:
            p.stop()

    async def test_handles_http_error_gracefully(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(side_effect=Exception("connection refused"))
            # Must not raise
            await client.update_queue_position(
                user_chat_id=123, msg_id=456, position=1, total=3
            )
        finally:
            p.stop()


# ---------- ProgressTracker ---------------------------------------------


@pytest.mark.asyncio
class TestProgressTracker:
    """Test ProgressTracker: clamping, seed, set_total, finalize."""

    async def test_send_progress_update_clamps_over_100_percent(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            await client.send_progress_update(
                user_chat_id=123,
                task_id="task_1",
                message_count=253432,
                total=244143,
                started=False,
            )

            text = client._http_client.post.call_args[1]["data"]["text"]
            assert "100%" in text
            assert "103%" not in text
        finally:
            p.stop()

    async def test_build_progress_bar_clamps_filled(self):
        # Static method — can be called without an instance.
        # Метод async просто чтобы попасть под класс-уровневую @pytest.mark.asyncio;
        # сам _build_progress_bar — sync.
        assert JavaBotClient._build_progress_bar(50) == "▓▓▓▓▓░░░░░"
        assert JavaBotClient._build_progress_bar(100) == "▓▓▓▓▓▓▓▓▓▓"
        assert JavaBotClient._build_progress_bar(110) == "▓▓▓▓▓▓▓▓▓▓"

    async def test_finalize_uses_max_of_total_and_count(self):
        """finalize() should use max(total, count) so percentage = 100%.

        finalize() calls send_progress_update positionally:
            (user_chat_id, task_id, count, final_total, progress_message_id=...)
        So we read call_args.args[2] / args[3], not kwargs.
        """
        mock_java_client = AsyncMock()
        mock_java_client.send_progress_update = AsyncMock(return_value=None)

        tracker = ProgressTracker(
            client=mock_java_client,
            user_chat_id=123,
            task_id="task_1",
        )

        # Simulate Telegram undercount: estimated total=100, actual=110
        await tracker.start(total=100)
        await tracker.finalize(count=110)

        last_call = mock_java_client.send_progress_update.call_args_list[-1]
        assert last_call.args[2] == 110
        assert last_call.args[3] == 110  # max(100, 110) = 110

    async def test_send_progress_update_with_zero_total_shows_progress_bar(self):
        """total=0 renders as a 0% bar, not the generic spinner.

        REGRESSION: without this, Telegram's pending total=0 left users staring
        at '⏳ Экспорт начался...' indefinitely.
        """
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            await client.send_progress_update(
                user_chat_id=123,
                task_id="task_1",
                message_count=0,
                total=0,
                started=True,
            )

            data = client._http_client.post.call_args[1]["data"]
            assert "0%" in data["text"]
            assert "(0 из 0)" in data["text"]
        finally:
            p.stop()

    async def test_set_total_updates_existing_progress_message(self):
        """set_total() must edit the existing message, not send a new one."""
        mock_java_client = AsyncMock()
        mock_java_client.send_progress_update = AsyncMock(side_effect=[777, 777])

        tracker = ProgressTracker(
            client=mock_java_client,
            user_chat_id=123,
            task_id="task_1",
        )

        await tracker.start()
        await tracker.set_total(42)

        assert mock_java_client.send_progress_update.call_count == 2
        # set_total passes message_count / total / progress_message_id as kwargs
        second_call = mock_java_client.send_progress_update.call_args_list[1]
        assert second_call.kwargs["message_count"] == 0
        assert second_call.kwargs["total"] == 42
        assert second_call.kwargs["progress_message_id"] == 777
