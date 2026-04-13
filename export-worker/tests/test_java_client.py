
import os
import json
import tempfile
import pytest
from unittest.mock import AsyncMock, patch, MagicMock

from models import ExportedMessage, SendResponsePayload
from java_client import JavaBotClient, ProgressTracker

# ---------- helpers -----------------------------------------------------

def _patch_settings(**overrides):
    patcher = patch("java_client.settings")
    mock = patcher.start()
    mock.JAVA_API_BASE_URL = overrides.get("JAVA_API_BASE_URL", "http://localhost:8080")
    mock.TELEGRAM_BOT_TOKEN = overrides.get("TELEGRAM_BOT_TOKEN", "test_bot_token")
    mock.RETRY_BASE_DELAY = overrides.get("RETRY_BASE_DELAY", 0.0)
    mock.RETRY_MAX_DELAY = overrides.get("RETRY_MAX_DELAY", 60.0)
    return patcher, mock

def _make_client(**overrides):
    patcher, _ = _patch_settings(**overrides)
    client = JavaBotClient(max_retries=overrides.get("max_retries", 3))
    client._http_client = AsyncMock()
    return client, patcher

def _make_temp_json(content: bytes = b'{"test": "data"}') -> str:
    fd, path = tempfile.mkstemp(suffix=".json")
    with os.fdopen(fd, "wb") as f:
        f.write(content)
    return path

# ---------- _stream_to_temp_json ----------------------------------------

@pytest.mark.asyncio
class TestStreamToTempJson:

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

    async def test_failed_status_notifies_user_and_returns_true(self):
        client, p = _make_client()
        try:
            with patch.object(
                client, "notify_user_failure", new_callable=AsyncMock
            ) as mock_notify:
                result = await client.send_response(
                    SendResponsePayload(
                        task_id="t1",
                        status="failed",
                        messages=[],
                        error="Export failed",
                        error_code="CHAT_PRIVATE",
                        user_chat_id=123,
                    )
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
                    SendResponsePayload(
                        task_id="t1",
                        status="completed",
                        messages=messages,
                        actual_count=1,
                        user_chat_id=123,
                        chat_title="Test Chat",
                    )
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
                client, "notify_user_failure", new_callable=AsyncMock
            ) as mock_notify:
                mock_upload.return_value = "cleaned text"
                mock_send.return_value = False  # delivery failed

                result = await client.send_response(
                    SendResponsePayload(
                        task_id="t1",
                        status="completed",
                        messages=messages,
                        actual_count=1,
                        user_chat_id=123,
                    )
                )

            assert result is False
            mock_notify.assert_called_once()
        finally:
            p.stop()

    async def test_upload_returns_none_triggers_failure_notify(self):
        client, p = _make_client()
        try:
            messages = [
                ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="Test")
            ]
            with patch.object(
                client, "_upload_file_to_java", new_callable=AsyncMock
            ) as mock_upload, patch.object(
                client, "notify_user_failure", new_callable=AsyncMock
            ) as mock_notify:
                mock_upload.return_value = None  # simulate Java API outage

                result = await client.send_response(
                    SendResponsePayload(
                        task_id="t1",
                        status="completed",
                        messages=messages,
                        actual_count=1,
                        user_chat_id=123,
                    )
                )

            assert result is False
            mock_notify.assert_called_once()
        finally:
            p.stop()

# ---------- _transform_entities (static) --------------------------------

class TestEntityTransformation:

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
        text = "Hello"
        entities = [{"type": "bold", "offset": 10, "length": 5}]
        result = JavaBotClient._transform_entities(text, entities)
        assert result[0]["type"] == "bold"
        assert result[0]["text"] == ""

    def test_transform_drops_unknown_fields(self):
        text = "code block"
        entities = [{"type": "pre", "offset": 0, "length": 10, "language": "python"}]
        result = JavaBotClient._transform_entities(text, entities)
        assert result[0]["type"] == "pre"
        assert result[0]["text"] == "code block"
        assert "language" not in result[0]

# ---------- _build_filename ---------------------------------------------

class TestBuildFilename:

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

            await client.notify_user_failure(123, "task_1", "Chat not accessible")

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
            await client.notify_user_failure(123, "task_1", "err")
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

    async def test_send_progress_update_includes_topic_name(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(
                return_value=MagicMock(status_code=200, json=lambda: {"result": {"message_id": 1}})
            )

            await client.send_progress_update(
                user_chat_id=123,
                task_id="task_1",
                message_count=50,
                total=100,
                topic_name="Обход блокировок",
            )

            text = client._http_client.post.call_args[1]["data"]["text"]
            assert "Топик: Обход блокировок" in text
            assert "50%" in text
        finally:
            p.stop()

    async def test_send_progress_update_no_topic_name_no_suffix(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(
                return_value=MagicMock(status_code=200, json=lambda: {"result": {"message_id": 1}})
            )

            await client.send_progress_update(
                user_chat_id=123,
                task_id="task_1",
                message_count=50,
                total=100,
            )

            text = client._http_client.post.call_args[1]["data"]["text"]
            assert "Топик:" not in text
        finally:
            p.stop()

    async def test_progress_tracker_with_topic_name_passes_to_all_updates(self):
        mock_java_client = AsyncMock()
        mock_java_client.send_progress_update = AsyncMock(return_value=12345)

        tracker = ProgressTracker(
            client=mock_java_client,
            user_chat_id=123,
            task_id="task_1",
            topic_name="Тестовый топик",
        )

        await tracker.start(total=100)
        await tracker.set_total(200)
        await tracker.finalize(count=200)

        for call in mock_java_client.send_progress_update.call_args_list:
            assert call.kwargs.get("topic_name") == "Тестовый топик"

    async def test_progress_tracker_without_topic_name(self):
        mock_java_client = AsyncMock()
        mock_java_client.send_progress_update = AsyncMock(return_value=12345)

        tracker = ProgressTracker(
            client=mock_java_client,
            user_chat_id=123,
            task_id="task_1",
        )

        await tracker.start(total=100)
        await tracker.finalize(count=100)

        for call in mock_java_client.send_progress_update.call_args_list:
            assert call.kwargs.get("topic_name") is None

    async def test_create_progress_tracker_with_topic_name(self):
        client, p = _make_client()
        try:
            tracker = client.create_progress_tracker(123, "task_1", topic_name="Мой топик")
            assert tracker._topic_name == "Мой топик"
        finally:
            p.stop()
