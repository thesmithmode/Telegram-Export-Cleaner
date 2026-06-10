
import os
import json
import tempfile
import pytest
from unittest.mock import AsyncMock, patch, MagicMock

import java_client
from models import ExportedMessage, SendResponsePayload
from java_client import JavaBotClient, ProgressTracker, _safe_err

# ---------- helpers -----------------------------------------------------

def _patch_settings(**overrides):
    patcher = patch("java_client.settings")
    mock = patcher.start()
    mock.JAVA_API_BASE_URL = overrides.get("JAVA_API_BASE_URL", "http://localhost:8080")
    mock.TELEGRAM_BOT_TOKEN = overrides.get("TELEGRAM_BOT_TOKEN", "test_bot_token")
    mock.RETRY_BASE_DELAY = overrides.get("RETRY_BASE_DELAY", 0.0)
    mock.RETRY_MAX_DELAY = overrides.get("RETRY_MAX_DELAY", 60.0)
    mock.JAVA_API_KEY = overrides.get("JAVA_API_KEY", "")
    mock.EXPORT_TEMP_DIR = overrides.get("EXPORT_TEMP_DIR", None)
    mock.EXPORT_MIN_FREE_DISK_MB = overrides.get("EXPORT_MIN_FREE_DISK_MB", 0)
    return patcher, mock

def _make_client(**overrides):
    patcher, _ = _patch_settings(**overrides)
    client = JavaBotClient(max_retries=overrides.get("max_retries", 3))
    client._http_client = AsyncMock()
    return client, patcher


class _StreamResponse:
    def __init__(self, status_code: int, body: str = "", chunks: list[bytes] | None = None):
        self.status_code = status_code
        self._chunks = chunks if chunks is not None else [body.encode("utf-8")]

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def aiter_bytes(self):
        for chunk in self._chunks:
            yield chunk


class _FailingStreamResponse(_StreamResponse):
    async def aiter_bytes(self):
        yield b"partial"
        raise OSError("disk/network stream failed")


class _StreamSideEffect:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.call_count = 0

    def __call__(self, *args, **kwargs):
        self.call_count += 1
        response = self.responses.pop(0) if self.responses else self.last_response
        self.last_response = response
        return response

def _make_temp_json(content: bytes = b'{"test": "data"}') -> str:
    fd, path = tempfile.mkstemp(suffix=".json")
    with os.fdopen(fd, "wb") as f:
        f.write(content)
    return path


def _make_temp_text(content: str) -> str:
    fd, path = tempfile.mkstemp(suffix=".txt")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
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

    @staticmethod
    def _read_and_unlink(path):
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        os.unlink(path)
        return text

    async def test_success_returns_cleaned_text_file(self):
        client, p = _make_client()
        try:
            path = _make_temp_json()
            try:
                stream = _StreamSideEffect(_StreamResponse(200, "cleaned markdown\n##OK##"))
                client._http_client.stream = stream
                result = await client._upload_file_to_java(path)
                assert self._read_and_unlink(result) == "cleaned markdown"
                assert stream.call_count == 1
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_stream_response_has_no_total_output_cap(self):
        assert not hasattr(java_client, "MAX_CONVERTED_OUTPUT_BYTES")

        client, p = _make_client(max_retries=0)
        try:
            path = _make_temp_json()
            try:
                client._http_client.stream = _StreamSideEffect(
                    _StreamResponse(200, chunks=[b"a" * (1024 * 1024), b"\n##OK##"])
                )
                result = await client._upload_file_to_java(path)
                assert os.path.getsize(result) == 1024 * 1024
                os.unlink(result)
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_sentinel_split_between_chunks_is_accepted(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            response = _StreamResponse(
                200,
                chunks=[b"hello", b"\n#", b"#OK", b"##"],
            )
            result = await client._stream_convert_response_to_file(response, task_id="split")
            with open(result, "rb") as f:
                assert f.read() == b"hello"
            os.unlink(result)
        finally:
            p.stop()

    async def test_missing_sentinel_removes_partial_output(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            result = await client._stream_convert_response_to_file(
                _StreamResponse(200, "partial without sentinel"),
                task_id="truncated",
            )
            assert result is None
            assert list(tmp_path.glob("tg_cleaned_*")) == []
        finally:
            p.stop()

    async def test_stream_exception_removes_partial_output(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            result = await client._stream_convert_response_to_file(
                _FailingStreamResponse(200),
                task_id="boom",
            )
            assert result is None
            assert list(tmp_path.glob("tg_cleaned_*")) == []
        finally:
            p.stop()

    async def test_low_disk_reserve_fails_and_removes_output(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path), EXPORT_MIN_FREE_DISK_MB=1)
        try:
            with patch("java_client.shutil.disk_usage", return_value=MagicMock(free=0)):
                result = await client._stream_convert_response_to_file(
                    _StreamResponse(200, "hello\n##OK##"),
                    task_id="low-disk",
                )
            assert result is None
            assert list(tmp_path.glob("tg_cleaned_*")) == []
        finally:
            p.stop()

    async def test_disk_reserve_check_is_not_per_chunk_for_small_chunks(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path), EXPORT_MIN_FREE_DISK_MB=1)
        try:
            chunks = [b"x" * 1024 for _ in range(100)] + [b"\n##OK##"]
            with patch.object(client, "_has_free_disk_for_write", return_value=True) as guard:
                result = await client._stream_convert_response_to_file(
                    _StreamResponse(200, chunks=chunks),
                    task_id="many-small-chunks",
                )
            try:
                assert result is not None
                assert guard.call_count == 1
                assert guard.call_args.args[1] == java_client._DISK_FREE_CHECK_INTERVAL_BYTES
            finally:
                if result:
                    os.unlink(result)
        finally:
            p.stop()

    async def test_initial_disk_reserve_includes_unchecked_write_window(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path), EXPORT_MIN_FREE_DISK_MB=1)
        try:
            with patch.object(client, "_has_free_disk_for_write", return_value=False) as guard:
                result = await client._stream_convert_response_to_file(
                    _StreamResponse(200, chunks=[b"tiny\n##OK##"]),
                    task_id="reserve-window",
                )
            assert result is None
            assert guard.call_count == 1
            assert guard.call_args.args[1] == java_client._DISK_FREE_CHECK_INTERVAL_BYTES
            assert list(tmp_path.glob("tg_cleaned_*")) == []
        finally:
            p.stop()

    async def test_temp_helper_uses_configured_directory(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path / "exports"))
        try:
            fd, path = client._mkstemp(suffix=".txt", prefix="tg_custom_")
            os.close(fd)
            try:
                assert os.path.dirname(path) == str(tmp_path / "exports")
                assert os.path.exists(path)
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_disk_usage_error_fails_closed(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path), EXPORT_MIN_FREE_DISK_MB=1)
        try:
            target = tmp_path / "x.txt"
            with patch("java_client.shutil.disk_usage", side_effect=OSError("stat failed")):
                assert client._has_free_disk_for_write(str(target), 1) is False
        finally:
            p.stop()

    async def test_disk_reserve_exhausted_during_stream_removes_output(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path), EXPORT_MIN_FREE_DISK_MB=1)
        try:
            with (
                patch("java_client._DISK_FREE_CHECK_INTERVAL_BYTES", 1),
                patch.object(client, "_has_free_disk_for_write", side_effect=[True, False]),
            ):
                result = await client._stream_convert_response_to_file(
                    _StreamResponse(200, chunks=[b"payload", b"\n##OK##"]),
                    task_id="mid-stream-low-disk",
                )
            assert result is None
            assert list(tmp_path.glob("tg_cleaned_*")) == []
        finally:
            p.stop()

    async def test_missing_sentinel_cleanup_ignores_already_removed_file(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            with patch("java_client.os.unlink", side_effect=FileNotFoundError):
                result = await client._stream_convert_response_to_file(
                    _StreamResponse(200, "partial without sentinel"),
                    task_id="truncated",
                )
            assert result is None
        finally:
            p.stop()

    async def test_200_without_sentinel_treated_as_truncated_and_retries(self):
        """T23: HTTP 200 без sentinel = truncated stream. Должен ретраить."""
        client, p = _make_client(max_retries=2)
        try:
            path = _make_temp_json()
            try:
                stream = _StreamSideEffect(
                    _StreamResponse(200, "partial content without sentinel"),
                    _StreamResponse(200, "partial content without sentinel"),
                    _StreamResponse(200, "partial content without sentinel"),
                )
                client._http_client.stream = stream
                with patch("java_client.asyncio.sleep", new_callable=AsyncMock):
                    result = await client._upload_file_to_java(path)
                assert result is None
                assert stream.call_count == 3
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_200_sentinel_without_newline_treated_as_truncated(self):
        """T23 regression: sentinel должен быть точно \n##OK## (с newline)."""
        client, p = _make_client(max_retries=0)
        try:
            path = _make_temp_json()
            try:
                client._http_client.stream = _StreamSideEffect(
                    _StreamResponse(200, "some content##OK##")
                )
                with patch("java_client.asyncio.sleep", new_callable=AsyncMock):
                    result = await client._upload_file_to_java(path)
                assert result is None, "должен быть None (truncated), а не 'some content'"
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_200_empty_string_treated_as_truncated(self):
        """T23 boundary: пустой ответ — не имеет sentinel, тоже truncated."""
        client, p = _make_client(max_retries=0)
        try:
            path = _make_temp_json()
            try:
                client._http_client.stream = _StreamSideEffect(_StreamResponse(200, ""))
                with patch("java_client.asyncio.sleep", new_callable=AsyncMock):
                    result = await client._upload_file_to_java(path)
                assert result is None
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_200_only_sentinel_strips_to_empty_file(self):
        """T23 boundary: ровно '\n##OK##' (нет контента) → empty file."""
        client, p = _make_client(max_retries=0)
        try:
            path = _make_temp_json()
            try:
                client._http_client.stream = _StreamSideEffect(_StreamResponse(200, "\n##OK##"))
                result = await client._upload_file_to_java(path)
                assert self._read_and_unlink(result) == ""
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_200_truncated_then_success_recovers(self):
        """T23: один truncated → retry → второй с sentinel → success."""
        client, p = _make_client(max_retries=3)
        try:
            path = _make_temp_json()
            try:
                stream = _StreamSideEffect(
                    _StreamResponse(200, "partial chunk"),
                    _StreamResponse(200, "final result\n##OK##"),
                )
                client._http_client.stream = stream
                with patch("java_client.asyncio.sleep", new_callable=AsyncMock):
                    result = await client._upload_file_to_java(path)
                assert self._read_and_unlink(result) == "final result"
                assert stream.call_count == 2
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_400_returns_none_without_retry(self):
        client, p = _make_client(max_retries=3)
        try:
            path = _make_temp_json()
            try:
                stream = _StreamSideEffect(_StreamResponse(400, "Bad request"))
                client._http_client.stream = stream
                result = await client._upload_file_to_java(path)
                assert result is None
                assert stream.call_count == 1
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_500_retries_until_exhausted(self):
        client, p = _make_client(max_retries=2)
        try:
            path = _make_temp_json()
            try:
                stream = _StreamSideEffect(
                    _StreamResponse(500, "Server error"),
                    _StreamResponse(500, "Server error"),
                    _StreamResponse(500, "Server error"),
                )
                client._http_client.stream = stream
                with patch("java_client.asyncio.sleep", new_callable=AsyncMock):
                    result = await client._upload_file_to_java(path)
                assert result is None
                assert stream.call_count == 3
            finally:
                os.unlink(path)
        finally:
            p.stop()

    async def test_500_then_success_on_retry(self):
        client, p = _make_client(max_retries=3)
        try:
            path = _make_temp_json()
            try:
                stream = _StreamSideEffect(
                    _StreamResponse(500, "err"),
                    _StreamResponse(500, "err"),
                    _StreamResponse(200, "Success!\n##OK##"),
                )
                client._http_client.stream = stream
                with patch("java_client.asyncio.sleep", new_callable=AsyncMock):
                    result = await client._upload_file_to_java(path)
                assert self._read_and_unlink(result) == "Success!"
                assert stream.call_count == 3
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
                client, "_send_file_path_to_user", new_callable=AsyncMock
            ) as mock_send:
                cleaned_path = _make_temp_text("cleaned text")
                mock_upload.return_value = cleaned_path
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
            assert not os.path.exists(cleaned_path)
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
                client, "_send_file_path_to_user", new_callable=AsyncMock
            ) as mock_send, patch.object(
                client, "notify_user_failure", new_callable=AsyncMock
            ) as mock_notify:
                cleaned_path = _make_temp_text("cleaned text")
                mock_upload.return_value = cleaned_path
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
            assert not os.path.exists(cleaned_path)
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

    async def test_empty_export_notifies_user_without_java_roundtrip(self):
        # actual_count=0: перехватываем ДО Java round-trip, пользователю — sendMessage.
        client, p = _make_client()
        try:
            with patch.object(
                client, "_stream_to_temp_json", new_callable=AsyncMock
            ) as mock_stream, patch.object(
                client, "_upload_file_to_java", new_callable=AsyncMock
            ) as mock_upload, patch.object(
                client, "_send_file_path_to_user", new_callable=AsyncMock
            ) as mock_send, patch.object(
                client, "notify_user_failure", new_callable=AsyncMock
            ) as mock_notify_failure:
                client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

                result = await client.send_response(
                    SendResponsePayload(
                        task_id="t_empty",
                        status="completed",
                        messages=[],
                        actual_count=0,
                        user_chat_id=42,
                        from_date="2026-04-14",
                        to_date="2026-04-15",
                    )
                )

            assert result is True
            mock_stream.assert_not_called()
            mock_upload.assert_not_called()
            mock_send.assert_not_called()
            mock_notify_failure.assert_not_called()

            client._http_client.post.assert_called_once()
            call_args = client._http_client.post.call_args
            url = call_args[0][0]
            data = call_args[1]["data"]
            assert "/sendMessage" in url
            assert "/sendDocument" not in url
            assert data["chat_id"] == 42
            assert "не найдено" in data["text"]
            assert "2026-04-14" in data["text"]
            assert "2026-04-15" in data["text"]
        finally:
            p.stop()

    async def test_empty_export_closes_async_iterator(self):
        # messages из main.py часто async-итератор — при actual_count==0
        # мы НЕ итерируем его в _stream_to_temp_json, поэтому send_response
        # должен явно aclose() чтобы не висел открытый SQLite-курсор/батч.
        # aclose у native async_generator read-only, поэтому имитируем через
        # класс с собственным aclose — getattr(..., "aclose", None) его найдёт.
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            class TrackedAsyncIter:
                def __init__(self):
                    self.closed = False

                def __aiter__(self):
                    return self

                async def __anext__(self):
                    raise StopAsyncIteration

                async def aclose(self):
                    self.closed = True

            messages_iter = TrackedAsyncIter()

            result = await client.send_response(
                SendResponsePayload(
                    task_id="t_empty",
                    status="completed",
                    messages=messages_iter,
                    actual_count=0,
                    user_chat_id=42,
                )
            )

            assert result is True
            assert messages_iter.closed, (
                "async iterator должен быть закрыт при empty-export early return"
            )
        finally:
            p.stop()

    async def test_empty_export_aclose_swallows_errors(self):
        # Даже если aclose() кидает — send_response не должен падать,
        # уведомление пользователю всё равно должно уйти.
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            class FailingAclose:
                def __aiter__(self):
                    return self

                async def __anext__(self):
                    raise StopAsyncIteration

                async def aclose(self):
                    raise RuntimeError("boom")

            result = await client.send_response(
                SendResponsePayload(
                    task_id="t_empty",
                    status="completed",
                    messages=FailingAclose(),
                    actual_count=0,
                    user_chat_id=42,
                )
            )

            assert result is True
            # Уведомление ушло, несмотря на ошибку aclose
            client._http_client.post.assert_called_once()
        finally:
            p.stop()

    async def test_empty_export_without_user_chat_id_is_silent_noop(self):
        client, p = _make_client()
        try:
            with patch.object(
                client, "_stream_to_temp_json", new_callable=AsyncMock
            ) as mock_stream, patch.object(
                client, "_upload_file_to_java", new_callable=AsyncMock
            ) as mock_upload:
                client._http_client.post = AsyncMock()

                result = await client.send_response(
                    SendResponsePayload(
                        task_id="t_empty",
                        status="completed",
                        messages=[],
                        actual_count=0,
                        user_chat_id=None,
                    )
                )

            assert result is True
            mock_stream.assert_not_called()
            mock_upload.assert_not_called()
            client._http_client.post.assert_not_called()
        finally:
            p.stop()

class TestDirectCachedResponse:

    def test_format_cached_message_line_matches_java_shape(self):
        msg = ExportedMessage(
            id=1,
            type="message",
            date="2026-06-09T12:34:56",
            text="hello\r\nworld",
        )

        line = JavaBotClient._format_cached_message_line(msg)

        assert line == "20260609 hello world"
        assert JavaBotClient._format_java_export_date("2026-06-09T12:00:00+00:00") == ""

    @pytest.mark.parametrize(
        "msg",
        [
            ExportedMessage(id=1, type="service", date="2026-06-09T12:00:00", text="x"),
            ExportedMessage(id=2, type="message", date="", text="x"),
            ExportedMessage(id=3, type="message", date="not-a-date", text="x"),
            ExportedMessage(id=4, type="message", date="2026-06-09T12:00:00", text="   "),
        ],
    )
    def test_format_cached_message_line_skips_java_skipped_messages(self, msg):
        assert JavaBotClient._format_cached_message_line(msg) is None

    def test_format_cached_message_line_applies_keywords(self):
        msg = ExportedMessage(
            id=1,
            type="message",
            date="2026-06-09T12:00:00",
            text="Alpha beta gamma",
        )

        assert JavaBotClient._format_cached_message_line(
            msg, include_keywords=["beta"], exclude_keywords=[]
        ) == "20260609 Alpha beta gamma"
        assert JavaBotClient._format_cached_message_line(
            msg, include_keywords=["missing"], exclude_keywords=[]
        ) is None
        assert JavaBotClient._format_cached_message_line(
            msg, include_keywords=[], exclude_keywords=["gamma"]
        ) is None

    @pytest.mark.asyncio
    async def test_direct_cached_response_writes_txt_and_skips_java_upload(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            messages = [
                ExportedMessage(id=1, type="message", date="2026-06-09T12:00:00", text="Keep"),
                ExportedMessage(id=2, type="message", date="2026-06-09T12:01:00", text="Drop"),
            ]
            captured = {}

            async def fake_send(chat_id, task_id, file_path, filename):
                captured["chat_id"] = chat_id
                captured["filename"] = filename
                with open(file_path, encoding="utf-8") as f:
                    captured["content"] = f.read()
                return True

            with patch.object(
                client, "_upload_file_to_java", new_callable=AsyncMock
            ) as mock_upload, patch.object(
                client, "_send_file_path_to_user", side_effect=fake_send
            ) as mock_send:
                success, bytes_count = await client.send_cached_response_direct(
                    SendResponsePayload(
                        task_id="direct_1",
                        status="completed",
                        messages=messages,
                        actual_count=2,
                        user_chat_id=42,
                        chat_title="Direct Chat",
                        keywords="keep",
                    )
                )

            assert success is True
            assert bytes_count == len("20260609 Keep\n".encode("utf-8"))
            assert captured["chat_id"] == 42
            assert captured["filename"] == "Direct_Chat_all.txt"
            assert captured["content"] == "20260609 Keep\n"
            mock_upload.assert_not_called()
            mock_send.assert_called_once()
            assert list(tmp_path.glob("tg_direct_*")) == []
        finally:
            p.stop()

    @pytest.mark.asyncio
    async def test_direct_cached_response_empty_after_filter_notifies_without_upload(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            messages = [
                ExportedMessage(id=1, type="message", date="2026-06-09T12:00:00", text="Drop"),
            ]
            with patch.object(
                client, "_send_file_path_to_user", new_callable=AsyncMock
            ) as mock_send, patch.object(
                client, "notify_empty_export", new_callable=AsyncMock
            ) as mock_notify:
                success, bytes_count = await client.send_cached_response_direct(
                    SendResponsePayload(
                        task_id="direct_empty",
                        status="completed",
                        messages=messages,
                        actual_count=1,
                        user_chat_id=42,
                        keywords="missing",
                    )
                )

            assert success is True
            assert bytes_count == 0
            mock_notify.assert_called_once()
            mock_send.assert_not_called()
            assert list(tmp_path.glob("tg_direct_*")) == []
        finally:
            p.stop()

    @pytest.mark.asyncio
    async def test_direct_cached_response_delegates_non_completed(self):
        client, p = _make_client()
        try:
            with patch.object(client, "send_response", new_callable=AsyncMock) as mock_send:
                mock_send.return_value = True
                success, bytes_count = await client.send_cached_response_direct(
                    SendResponsePayload(task_id="failed", status="failed", actual_count=1)
                )

            assert success is True
            assert bytes_count is None
            mock_send.assert_awaited_once()
        finally:
            p.stop()

    @pytest.mark.asyncio
    async def test_direct_cached_response_effectively_empty_notifies_and_cleans(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            path = tmp_path / "empty.txt"
            path.write_text("\ufeff", encoding="utf-8")
            with patch.object(
                client, "_stream_messages_to_cleaned_text", new_callable=AsyncMock
            ) as mock_stream, patch.object(
                client, "notify_empty_export", new_callable=AsyncMock
            ) as mock_notify, patch.object(
                client, "_send_file_path_to_user", new_callable=AsyncMock
            ) as mock_send:
                mock_stream.return_value = str(path)
                success, bytes_count = await client.send_cached_response_direct(
                    SendResponsePayload(
                        task_id="effective_empty",
                        status="completed",
                        messages=[],
                        actual_count=1,
                        user_chat_id=42,
                    )
                )

            assert success is True
            assert bytes_count == 0
            mock_notify.assert_awaited_once()
            mock_send.assert_not_called()
            assert not path.exists()
        finally:
            p.stop()

    @pytest.mark.asyncio
    async def test_direct_cached_response_send_failure_notifies_and_returns_false(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            path = tmp_path / "result.txt"
            path.write_text("20260609 hello\n", encoding="utf-8")
            with patch.object(
                client, "_stream_messages_to_cleaned_text", new_callable=AsyncMock
            ) as mock_stream, patch.object(
                client, "_send_file_path_to_user", new_callable=AsyncMock
            ) as mock_send, patch.object(
                client, "notify_user_failure", new_callable=AsyncMock
            ) as mock_notify:
                mock_stream.return_value = str(path)
                mock_send.return_value = False
                success, bytes_count = await client.send_cached_response_direct(
                    SendResponsePayload(
                        task_id="send_fail",
                        status="completed",
                        messages=[],
                        actual_count=1,
                        user_chat_id=42,
                    )
                )

            assert success is False
            assert bytes_count == len("20260609 hello\n".encode("utf-8"))
            mock_notify.assert_awaited_once()
            assert not path.exists()
        finally:
            p.stop()

    @pytest.mark.asyncio
    async def test_direct_cached_response_without_user_skips_upload(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            path = tmp_path / "result.txt"
            path.write_text("20260609 hello\n", encoding="utf-8")
            with patch.object(
                client, "_stream_messages_to_cleaned_text", new_callable=AsyncMock
            ) as mock_stream, patch.object(
                client, "_send_file_path_to_user", new_callable=AsyncMock
            ) as mock_send:
                mock_stream.return_value = str(path)
                success, bytes_count = await client.send_cached_response_direct(
                    SendResponsePayload(
                        task_id="no_user",
                        status="completed",
                        messages=[],
                        actual_count=1,
                    )
                )

            assert success is True
            assert bytes_count == len("20260609 hello\n".encode("utf-8"))
            mock_send.assert_not_called()
            assert not path.exists()
        finally:
            p.stop()

    @pytest.mark.asyncio
    async def test_direct_stream_low_disk_raises_and_cleans(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            with patch.object(client, "_has_free_disk_for_write", return_value=False):
                with pytest.raises(OSError):
                    await client._stream_messages_to_cleaned_text(
                        SendResponsePayload(
                            task_id="low_disk",
                            status="completed",
                            messages=[
                                ExportedMessage(
                                    id=1,
                                    type="message",
                                    date="2026-06-09T12:00:00",
                                    text="hello",
                                )
                            ],
                            actual_count=1,
                        )
                    )

            assert list(tmp_path.glob("tg_direct_*")) == []
        finally:
            p.stop()

    @pytest.mark.asyncio
    async def test_direct_stream_disk_exhausted_during_write_raises(self, tmp_path, monkeypatch):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            monkeypatch.setattr(java_client, "_DISK_FREE_CHECK_INTERVAL_BYTES", 1)
            with patch.object(client, "_has_free_disk_for_write", side_effect=[True, False]):
                with pytest.raises(OSError):
                    await client._stream_messages_to_cleaned_text(
                        SendResponsePayload(
                            task_id="disk_exhausted",
                            status="completed",
                            messages=[
                                ExportedMessage(
                                    id=1,
                                    type="message",
                                    date="2026-06-09T12:00:00",
                                    text="hello",
                                )
                            ],
                            actual_count=1,
                        )
                    )

            assert list(tmp_path.glob("tg_direct_*")) == []
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

    def test_username_has_priority_over_mixed_unicode_title(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            assert (
                client._build_filename("MIX КИБЕРПОРТАЛ", None, None, "strbypass")
                == "strbypass_all.txt"
            )
        finally:
            p.stop()

    def test_unicode_title_without_username_is_preserved(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            assert (
                client._build_filename("Александр Гурулев", None, None)
                == "Александр_Гурулев_all.txt"
            )
        finally:
            p.stop()

    def test_username_priority_strips_at_prefix(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            assert (
                client._build_filename("MIX КИБЕРПОРТАЛ", None, None, "@strbypass")
                == "strbypass_all.txt"
            )
        finally:
            p.stop()

    def test_non_ascii_title_sanitizes_username_fallback(self):
        _, p = _patch_settings()
        try:
            client = JavaBotClient()
            assert (
                client._build_filename("Александр Гурулев", None, None, "@agurulev")
                == "agurulev_all.txt"
            )
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
            assert result == "ChatName_test_all.txt"
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

# ---------- file-path delivery ------------------------------------------

@pytest.mark.asyncio
class TestSendFileToUser:

    async def test_response_preview_stops_at_limit(self):
        response = _StreamResponse(500, chunks=[b"abc", b"def"])
        preview = await JavaBotClient._read_response_preview(response, limit=3)
        assert preview == "abc"

    async def test_transform_entities_invalid_entity_returns_original(self):
        original = [None]
        assert JavaBotClient._transform_entities("text", original) is original

    async def test_small_file_path_sent_as_stream(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            source = tmp_path / "small.txt"
            source.write_text("small", encoding="utf-8")

            with patch.object(client, "_send_single_file", new_callable=AsyncMock) as send:
                send.return_value = True
                result = await client._send_file_path_to_user(123, "task_1", str(source), "small.txt")

            assert result is True
            send.assert_called_once()
            assert not isinstance(send.call_args.args[2], (bytes, bytearray))
        finally:
            p.stop()

    async def test_part_caption_does_not_use_precomputed_total(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            source = tmp_path / "cleaned.txt"
            source.write_text("aa😀", encoding="utf-8")

            with patch("java_client.TELEGRAM_MAX_FILE_SIZE_BYTES", 5), \
                 patch.object(client, "_send_single_file", new_callable=AsyncMock) as send:
                send.return_value = True
                result = await client._send_file_path_to_user(123, "task_1", str(source), "big.txt")

            assert result is True
            captions = [call.args[4] for call in send.call_args_list]
            assert captions == ["✅ Часть 1", "✅ Часть 2"]
            assert all("/" not in caption for caption in captions)
        finally:
            p.stop()

    async def test_file_path_larger_than_part_limit_streams_parts_and_cleans_temp_parts(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            source = tmp_path / "cleaned.txt"
            source.write_text("line-1\nline-2\nline-3\nline-4\n", encoding="utf-8")

            with patch("java_client.TELEGRAM_MAX_FILE_SIZE_BYTES", 10), \
                 patch.object(client, "_send_single_file", new_callable=AsyncMock) as send:
                send.return_value = True
                result = await client._send_file_path_to_user(123, "task_1", str(source), "big.txt")

            assert result is True
            assert send.call_count >= 3
            assert all(not isinstance(call.args[2], (bytes, bytearray)) for call in send.call_args_list)
            assert list(tmp_path.glob("tg_part_*")) == []

        finally:
            p.stop()

    async def test_large_file_parts_use_resolved_username_filename(self, tmp_path):
        client = JavaBotClient.__new__(JavaBotClient)
        source = tmp_path / "cleaned.txt"
        source.write_bytes(b"abcdef")

        with patch("java_client.TELEGRAM_MAX_FILE_SIZE_BYTES", 5), \
             patch("java_client.settings.EXPORT_TEMP_DIR", str(tmp_path)), \
             patch.object(client, "_send_single_file", new_callable=AsyncMock) as send:
            send.return_value = True
            result = await client._send_file_path_to_user(
                123,
                "task_1",
                str(source),
                "strbypass_all.txt",
            )

        assert result is True
        filenames = [call.args[3] for call in send.call_args_list]
        assert filenames == ["part1_strbypass_all.txt", "part2_strbypass_all.txt"]

    async def test_file_part_removed_when_telegram_upload_fails(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            source = tmp_path / "cleaned.txt"
            source.write_text("line-1\nline-2\nline-3\n", encoding="utf-8")

            with patch("java_client.TELEGRAM_MAX_FILE_SIZE_BYTES", 10), \
                 patch.object(client, "_send_single_file", new_callable=AsyncMock) as send:
                send.return_value = False
                result = await client._send_file_path_to_user(123, "task_1", str(source), "big.txt")

            assert result is False
            assert send.call_count == 1
            assert list(tmp_path.glob("tg_part_*")) == []

        finally:
            p.stop()

    async def test_file_part_cleanup_ignores_already_removed_part(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            source = tmp_path / "cleaned.txt"
            source.write_text("line-1\nline-2\nline-3\n", encoding="utf-8")

            with patch("java_client.TELEGRAM_MAX_FILE_SIZE_BYTES", 10), \
                 patch.object(client, "_send_single_file", new_callable=AsyncMock) as send, \
                 patch("java_client.os.unlink", side_effect=FileNotFoundError):
                send.return_value = False
                result = await client._send_file_path_to_user(123, "task_1", str(source), "big.txt")

            assert result is False
            assert send.call_count == 1
        finally:
            p.stop()

    async def test_file_split_keeps_utf8_boundaries(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            content = "😀😀😀😀"
            source = tmp_path / "unicode.txt"
            source.write_text(content, encoding="utf-8")

            parts = []
            async for part_path in client._split_file_by_size(str(source), 5):
                with open(part_path, "rb") as f:
                    data = f.read()
                data.decode("utf-8")
                parts.append(data)
                os.unlink(part_path)

            assert b"".join(parts).decode("utf-8") == content

        finally:
            p.stop()

    async def test_file_split_empty_source_removes_empty_part(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            source = tmp_path / "empty.txt"
            source.write_bytes(b"")

            parts = []
            async for part_path in client._split_file_by_size(str(source), 10):
                parts.append(part_path)

            assert parts == []
            assert list(tmp_path.glob("tg_part_*")) == []
        finally:
            p.stop()

    async def test_file_split_exception_cleans_current_part(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            missing = tmp_path / "missing.txt"
            with pytest.raises(FileNotFoundError):
                async for _ in client._split_file_by_size(str(missing), 10):
                    pass
            assert list(tmp_path.glob("tg_part_*")) == []
        finally:
            p.stop()

    async def test_file_split_rolls_to_new_part_when_utf8_char_does_not_fit(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            content = "aa😀"
            source = tmp_path / "rollover.txt"
            source.write_text(content, encoding="utf-8")

            parts = []
            async for part_path in client._split_file_by_size(str(source), 5):
                with open(part_path, "rb") as f:
                    data = f.read()
                data.decode("utf-8")
                parts.append(data)
                os.unlink(part_path)

            assert b"".join(parts).decode("utf-8") == content
        finally:
            p.stop()

    async def test_file_split_keeps_single_utf8_char_intact_when_limit_is_tiny(self, tmp_path):
        client, p = _make_client(EXPORT_TEMP_DIR=str(tmp_path))
        try:
            content = "😀"
            source = tmp_path / "tiny-limit.txt"
            source.write_text(content, encoding="utf-8")

            parts = []
            async for part_path in client._split_file_by_size(str(source), 1):
                with open(part_path, "rb") as f:
                    data = f.read()
                data.decode("utf-8")
                parts.append(data)
                os.unlink(part_path)

            assert b"".join(parts).decode("utf-8") == content
        finally:
            p.stop()

    async def test_utf8_char_width_detects_sequence_length(self):
        assert JavaBotClient._utf8_char_width("A".encode("utf-8")[0]) == 1
        assert JavaBotClient._utf8_char_width("é".encode("utf-8")[0]) == 2
        assert JavaBotClient._utf8_char_width("€".encode("utf-8")[0]) == 3
        assert JavaBotClient._utf8_char_width("😀".encode("utf-8")[0]) == 4
        assert JavaBotClient._utf8_char_width(0b1000_0000) == 1

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

# ---------- notify_empty_export + _format_period ------------------------

@pytest.mark.asyncio
class TestNotifyEmptyExport:

    async def test_sends_friendly_sendmessage_with_period(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            await client.notify_empty_export(
                123, "task_1", "2026-04-14", "2026-04-15"
            )

            client._http_client.post.assert_called_once()
            call_args = client._http_client.post.call_args
            url = call_args[0][0]
            data = call_args[1]["data"]
            assert "/sendMessage" in url
            assert data["chat_id"] == 123
            assert "не найдено" in data["text"]
            # Формат периода: "2026-04-14 — 2026-04-15"
            assert "2026-04-14" in data["text"]
            assert "2026-04-15" in data["text"]
            # Не должно быть префикса "Export failed" — это не ошибка
            assert "Export failed" not in data["text"]

        finally:
            p.stop()

    async def test_without_dates_omits_period_prefix(self):
        # Экспорт без date-фильтра: не должно быть "За выбранный период".
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))

            await client.notify_empty_export(123, "task_1", None, None)

            data = client._http_client.post.call_args[1]["data"]
            assert "не найдено" in data["text"]
            assert "За выбранный период" not in data["text"]
            assert "выбранный период" not in data["text"]
        finally:
            p.stop()

    async def test_swallows_http_errors(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(
                side_effect=Exception("connection refused")
            )
            # Must not raise
            await client.notify_empty_export(
                123, "task_1", "2026-04-14", "2026-04-15"
            )
        finally:
            p.stop()


class TestFormatPeriod:

    def test_both_dates(self):
        assert JavaBotClient._format_period("2026-04-14", "2026-04-15") == (
            "период 2026-04-14 — 2026-04-15"
        )

    def test_both_dates_with_time_suffix_is_truncated_to_day(self):
        # from_date/to_date прилетают в ISO с временем — показываем только день.
        assert JavaBotClient._format_period(
            "2026-04-14T00:00:00", "2026-04-15T23:59:59"
        ) == "период 2026-04-14 — 2026-04-15"

    def test_only_from_date(self):
        assert JavaBotClient._format_period("2026-04-14", None) == (
            "период с 2026-04-14"
        )

    def test_only_to_date(self):
        assert JavaBotClient._format_period(None, "2026-04-15") == (
            "период до 2026-04-15"
        )

    def test_no_dates_returns_none(self):
        # None сигнализирует notify_empty_export использовать укороченный текст
        # без префикса «За выбранный период».
        assert JavaBotClient._format_period(None, None) is None

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
            assert text.count("253432") == 2
            assert "244143" not in text
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

    async def test_seed_raises_underestimated_total_to_cached_count(self):
        mock_java_client = AsyncMock()
        mock_java_client.send_progress_update = AsyncMock(return_value=777)

        tracker = ProgressTracker(
            client=mock_java_client,
            user_chat_id=123,
            task_id="task_1",
        )

        await tracker.start(total=344355)
        await tracker.seed(cached_count=389129)

        seed_call = mock_java_client.send_progress_update.call_args_list[-1]
        assert seed_call.args[2] == 389129
        assert seed_call.args[3] == 389129

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

    async def test_set_total_none_fallback_to_started_message(self):
        # REGRESSION: при total=None (Telegram не смог посчитать) раньше был silent
        # return → пользователь застревал на "🔢 Определяю количество сообщений..."
        # до конца экспорта. Теперь редактируем на started=True.
        mock_java_client = AsyncMock()
        mock_java_client.send_progress_update = AsyncMock(side_effect=[777, 777])

        tracker = ProgressTracker(
            client=mock_java_client,
            user_chat_id=123,
            task_id="task_1",
        )

        await tracker.start()
        await tracker.set_total(None)

        assert mock_java_client.send_progress_update.call_count == 2
        second_call = mock_java_client.send_progress_update.call_args_list[1]
        assert second_call.kwargs["started"] is True
        assert second_call.kwargs.get("total") is None
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

    async def test_counting_noop_before_start(self):
        # Без message_id (start не вызывался) — counting() не должен слать API call.
        mock_java_client = AsyncMock()
        mock_java_client.send_progress_update = AsyncMock()
        tracker = ProgressTracker(mock_java_client, 123, "task_1")
        await tracker.counting()
        mock_java_client.send_progress_update.assert_not_called()

    async def test_counting_edits_existing_message(self):
        mock_java_client = AsyncMock()
        mock_java_client.send_progress_update = AsyncMock(side_effect=[777, None])
        tracker = ProgressTracker(mock_java_client, 123, "task_1", topic_name="T")
        await tracker.start()
        await tracker.counting()

        second = mock_java_client.send_progress_update.call_args_list[1]
        assert second.kwargs["counting"] is True
        assert second.kwargs["progress_message_id"] == 777
        assert second.kwargs["topic_name"] == "T"
        assert second.kwargs["message_count"] == 0

    async def test_send_progress_update_counting_flag_text(self):
        client, p = _make_client()
        try:
            client._http_client.post = AsyncMock(return_value=MagicMock(status_code=200))
            await client.send_progress_update(
                user_chat_id=123, task_id="task_1",
                message_count=0, counting=True, progress_message_id=777,
            )
            text = client._http_client.post.call_args[1]["data"]["text"]
            assert "Определяю количество сообщений" in text
        finally:
            p.stop()

    async def test_create_progress_tracker_with_topic_name(self):
        client, p = _make_client()
        try:
            tracker = client.create_progress_tracker(123, "task_1", topic_name="Мой топик")
            assert tracker._topic_name == "Мой топик"
        finally:
            p.stop()


# ======================================================================
# Hotfix @durov: вторая линия защиты от пустого документа
# ======================================================================
# actual_count > 0 (напр. 1 служебное сообщение — pin/join), но Java
# отфильтровала всё в пустой/whitespace текст. Telegram Bot API отклоняет
# пустой sendDocument → 400 Bad Request. Ожидаемое поведение: перехватываем
# ДО попытки отправки, шлём notify_empty_export, return True (не False).

@pytest.mark.asyncio
class TestEmptyCleanedTextFromJava:

    async def test_java_returns_empty_string_triggers_notify_not_senddocument(self):
        client, p = _make_client()
        try:
            messages = [ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="/pin")]
            with patch.object(client, "_stream_to_temp_json", new_callable=AsyncMock) as mock_stream, \
                 patch.object(client, "_upload_file_to_java", new_callable=AsyncMock) as mock_upload, \
                 patch.object(client, "_send_file_path_to_user", new_callable=AsyncMock) as mock_send, \
                 patch.object(client, "notify_empty_export", new_callable=AsyncMock) as mock_notify_empty, \
                 patch.object(client, "notify_user_failure", new_callable=AsyncMock) as mock_notify_fail:
                mock_stream.return_value = _make_temp_json()
                cleaned_path = _make_temp_text("")
                mock_upload.return_value = cleaned_path

                result = await client.send_response(SendResponsePayload(
                    task_id="durov_bug",
                    status="completed",
                    messages=messages,
                    actual_count=1,
                    user_chat_id=555,
                    from_date="2025-01-01T00:00:00",
                    to_date="2025-12-31T23:59:59",
                ))

            assert result is True, "Пустой результат — это не ошибка, а нулевой выхлоп"
            mock_notify_empty.assert_called_once()
            mock_send.assert_not_called(), "sendDocument НЕ должен вызываться с пустым текстом"
            mock_notify_fail.assert_not_called(), "Это не failure — не шлём 'Ошибка'"
            assert not os.path.exists(cleaned_path)
        finally:
            p.stop()

    async def test_java_returns_whitespace_only_treated_as_empty(self):
        """Whitespace-only тоже запрещён Telegram-ом для sendDocument."""
        client, p = _make_client()
        try:
            messages = [ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="x")]
            with patch.object(client, "_stream_to_temp_json", new_callable=AsyncMock) as mock_stream, \
                 patch.object(client, "_upload_file_to_java", new_callable=AsyncMock) as mock_upload, \
                 patch.object(client, "_send_file_path_to_user", new_callable=AsyncMock) as mock_send, \
                 patch.object(client, "notify_empty_export", new_callable=AsyncMock) as mock_notify_empty:
                mock_stream.return_value = _make_temp_json()
                cleaned_path = _make_temp_text("   \n\t  \n  ")
                mock_upload.return_value = cleaned_path

                result = await client.send_response(SendResponsePayload(
                    task_id="t1", status="completed", messages=messages,
                    actual_count=2, user_chat_id=1,
                ))

            assert result is True
            mock_notify_empty.assert_called_once()
            mock_send.assert_not_called()
            assert not os.path.exists(cleaned_path)
        finally:
            p.stop()

    async def test_invisible_unicode_treated_as_empty(self):
        """BOM, zero-width space, format-символы → пустой документ для юзера.
        Не ловится простым strip() — только через unicodedata.category."""
        client, p = _make_client()
        try:
            messages = [ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="x")]
            with patch.object(client, "_stream_to_temp_json", new_callable=AsyncMock) as mock_stream, \
                 patch.object(client, "_upload_file_to_java", new_callable=AsyncMock) as mock_upload, \
                 patch.object(client, "_send_file_path_to_user", new_callable=AsyncMock) as mock_send, \
                 patch.object(client, "notify_empty_export", new_callable=AsyncMock) as mock_notify_empty:
                mock_stream.return_value = _make_temp_json()
                cleaned_path = _make_temp_text("\ufeff\u200b\u200d\u00a0 \n")
                mock_upload.return_value = cleaned_path

                result = await client.send_response(SendResponsePayload(
                    task_id="t1", status="completed", messages=messages,
                    actual_count=1, user_chat_id=1,
                ))

            assert result is True
            mock_notify_empty.assert_called_once()
            mock_send.assert_not_called()
            assert not os.path.exists(cleaned_path)
        finally:
            p.stop()

    async def test_java_returns_non_empty_still_uploads_document(self):
        """Регрессия: нормальный путь не сломан защитой от пустоты."""
        client, p = _make_client()
        try:
            messages = [ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="hi")]
            with patch.object(client, "_stream_to_temp_json", new_callable=AsyncMock) as mock_stream, \
                 patch.object(client, "_upload_file_to_java", new_callable=AsyncMock) as mock_upload, \
                 patch.object(client, "_send_file_path_to_user", new_callable=AsyncMock) as mock_send, \
                 patch.object(client, "notify_empty_export", new_callable=AsyncMock) as mock_notify_empty:
                mock_stream.return_value = _make_temp_json()
                cleaned_path = _make_temp_text("Hello, world!")
                mock_upload.return_value = cleaned_path
                mock_send.return_value = True

                result = await client.send_response(SendResponsePayload(
                    task_id="t1", status="completed", messages=messages,
                    actual_count=1, user_chat_id=1, chat_title="Chat",
                ))

            assert result is True
            mock_send.assert_called_once()
            mock_notify_empty.assert_not_called()
            assert not os.path.exists(cleaned_path)
        finally:
            p.stop()

    async def test_empty_cleaned_text_no_user_chat_id_still_returns_true(self):
        """Без user_chat_id нечего уведомлять, но и падать не должны."""
        client, p = _make_client()
        try:
            messages = [ExportedMessage(id=1, type="message", date="2025-01-01T00:00:00", text="x")]
            with patch.object(client, "_stream_to_temp_json", new_callable=AsyncMock) as mock_stream, \
                 patch.object(client, "_upload_file_to_java", new_callable=AsyncMock) as mock_upload, \
                 patch.object(client, "notify_empty_export", new_callable=AsyncMock) as mock_notify_empty:
                mock_stream.return_value = _make_temp_json()
                cleaned_path = _make_temp_text("")
                mock_upload.return_value = cleaned_path

                result = await client.send_response(SendResponsePayload(
                    task_id="t1", status="completed", messages=messages,
                    actual_count=1, user_chat_id=None,
                ))

            assert result is True
            mock_notify_empty.assert_not_called()
            assert not os.path.exists(cleaned_path)
        finally:
            p.stop()


class TestSafeErr:

    def test_redacts_bot_token_in_url(self):
        e = Exception(
            "Request failed for URL https://api.telegram.org/bot123456:ABC-DEF1234/sendDocument"
        )
        result = _safe_err(e)
        assert "123456:ABC-DEF1234" not in result
        assert "/bot<REDACTED>/" in result

    def test_no_token_url_unchanged(self):
        e = Exception("Connection refused")
        result = _safe_err(e)
        assert result == "Connection refused"

    def test_redacts_multiple_token_occurrences(self):
        e = Exception(
            "Failed https://api.telegram.org/bot111:AAA/sendMessage "
            "and https://api.telegram.org/bot222:BBB/editMessageText"
        )
        result = _safe_err(e)
        assert "111:AAA" not in result
        assert "222:BBB" not in result
        assert result.count("/bot<REDACTED>/") == 2

    def test_returns_string(self):
        e = RuntimeError("some error")
        assert isinstance(_safe_err(e), str)
