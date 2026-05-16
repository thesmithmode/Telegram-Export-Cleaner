"""Round 3 coverage: целевые тесты для java_client + main cleanup error-paths.

Закрывают gap до 95% gate: java_client.notify_subscription_empty,
_format_date_human, _upload_file_to_java error paths, main.cleanup
error swallowing.
"""

import asyncio
import os
import tempfile
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from java_client import JavaBotClient


# ---------- helpers ----------------------------------------------------------

def _patch_settings(**overrides):
    patcher = patch("java_client.settings")
    mock = patcher.start()
    mock.JAVA_API_BASE_URL = overrides.get("JAVA_API_BASE_URL", "http://localhost:8080")
    mock.TELEGRAM_BOT_TOKEN = overrides.get("TELEGRAM_BOT_TOKEN", "test_bot_token")
    mock.RETRY_BASE_DELAY = overrides.get("RETRY_BASE_DELAY", 0.0)
    mock.RETRY_MAX_DELAY = overrides.get("RETRY_MAX_DELAY", 60.0)
    mock.JAVA_API_KEY = overrides.get("JAVA_API_KEY", "")
    return patcher, mock


def _make_client(**overrides):
    patcher, _ = _patch_settings(**overrides)
    client = JavaBotClient(max_retries=overrides.get("max_retries", 2))
    client._http_client = AsyncMock()
    return client, patcher


# ---------- notify_subscription_empty (lines 359-376) ------------------------

@pytest.mark.asyncio
class TestNotifySubscriptionEmpty:

    async def test_success_200(self):
        client, patcher = _make_client()
        try:
            resp = MagicMock()
            resp.status_code = 200
            client._http_client.post.return_value = resp
            await client.notify_subscription_empty(
                chat_id=100, chat_label="@test",
                from_date="2026-01-01T00:00:00", to_date="2026-01-02T00:00:00"
            )
            client._http_client.post.assert_awaited_once()
        finally:
            patcher.stop()

    async def test_non_200_logs_warning(self, caplog):
        client, patcher = _make_client()
        try:
            resp = MagicMock()
            resp.status_code = 400
            resp.text = "Bad chat"
            client._http_client.post.return_value = resp
            await client.notify_subscription_empty(
                chat_id=101, chat_label="@x",
                from_date=None, to_date=None
            )
            client._http_client.post.assert_awaited_once()
        finally:
            patcher.stop()

    async def test_exception_swallowed(self):
        client, patcher = _make_client()
        try:
            client._http_client.post.side_effect = RuntimeError("network down")
            # не пробрасывает
            await client.notify_subscription_empty(
                chat_id=102, chat_label="@y",
                from_date="2026-01-01", to_date="2026-01-02"
            )
        finally:
            patcher.stop()


# ---------- _format_date_human (lines 381-389) -------------------------------

class TestFormatDateHuman:

    def test_none_returns_dash(self):
        assert JavaBotClient._format_date_human(None) == "—"

    def test_empty_returns_dash(self):
        assert JavaBotClient._format_date_human("") == "—"

    def test_iso_with_seconds(self):
        out = JavaBotClient._format_date_human("2026-05-16T14:30:00")
        assert "2026-05-16 14:30 UTC" == out

    def test_iso_no_seconds(self):
        out = JavaBotClient._format_date_human("2026-05-16T14:30")
        assert "2026-05-16 14:30 UTC" == out

    def test_date_only(self):
        out = JavaBotClient._format_date_human("2026-05-16")
        assert "2026-05-16 00:00 UTC" == out

    def test_unknown_format_returns_raw(self):
        # Все три парсера failят → возвращаем raw
        assert JavaBotClient._format_date_human("garbage") == "garbage"


# ---------- _upload_file_to_java error paths (lines 253-259) ----------------

@pytest.mark.asyncio
class TestUploadErrorPaths:

    async def test_file_not_found_returns_none_no_retry(self):
        client, patcher = _make_client(max_retries=3)
        try:
            client._http_client.post = AsyncMock()
            # FileNotFoundError бросит open()
            result = await client._upload_file_to_java(
                file_path="/nonexistent/path/result.json"
            )
            assert result is None
            # No retry — post вызывался 0 раз
            client._http_client.post.assert_not_called()
        finally:
            patcher.stop()

    async def test_generic_network_error_retries_and_exhausts(self):
        client, patcher = _make_client(max_retries=2)
        try:
            client._http_client.post.side_effect = RuntimeError("conn reset")
            fd, tmp = tempfile.mkstemp(suffix=".json")
            os.close(fd)
            with open(tmp, "wb") as f:
                f.write(b'{"x":1}')
            try:
                result = await client._upload_file_to_java(file_path=tmp)
                assert result is None
                # retried max_retries+1 раз
                assert client._http_client.post.await_count == 3
            finally:
                try:
                    os.unlink(tmp)
                except FileNotFoundError:
                    pass
        finally:
            patcher.stop()


# ---------- main.cleanup error swallowing (lines 1338-1372) ------------------

@pytest.mark.asyncio
class TestMainCleanupErrorSwallow:

    async def test_cleanup_swallows_subclient_close_errors(self):
        """Все подклиенты бросают на close — cleanup не пробрасывает."""
        from main import ExportWorker

        worker = ExportWorker.__new__(ExportWorker)
        worker.running = True
        worker.jobs_processed = 0
        worker.jobs_failed = 0

        # _cache_stats_task на cancel поднимает RuntimeError (не CancelledError) →
        # ветка lines 1338-1340 (except Exception)
        async def _running():
            try:
                await asyncio.sleep(100)
            except asyncio.CancelledError:
                raise RuntimeError("custom error on cancel")
        worker._cache_stats_task = asyncio.create_task(_running())
        await asyncio.sleep(0)  # let it start

        worker.telegram_client = MagicMock()
        worker.telegram_client.disconnect = AsyncMock(side_effect=RuntimeError("tg fail"))
        worker.queue_consumer = MagicMock()
        worker.queue_consumer.disconnect = AsyncMock(side_effect=RuntimeError("queue fail"))
        worker.java_client = MagicMock()
        worker.java_client.aclose = AsyncMock(side_effect=RuntimeError("java fail"))
        worker.message_cache = MagicMock()
        worker.message_cache.close = AsyncMock(side_effect=RuntimeError("cache fail"))
        worker.control_redis = MagicMock()
        worker.control_redis.aclose = AsyncMock(side_effect=RuntimeError("redis fail"))

        # Не должен пробросить никаких исключений
        await worker.cleanup()

        assert worker.running is False
        assert worker._cache_stats_task is None

    async def test_cleanup_cache_task_cancelled_silent(self):
        """_cache_stats_task поднимает CancelledError на await — silent."""
        from main import ExportWorker

        worker = ExportWorker.__new__(ExportWorker)
        worker.running = True
        worker.jobs_processed = 0
        worker.jobs_failed = 0

        async def _sleeper():
            await asyncio.sleep(100)
        worker._cache_stats_task = asyncio.create_task(_sleeper())
        await asyncio.sleep(0)

        worker.telegram_client = None
        worker.queue_consumer = None
        worker.java_client = None
        worker.message_cache = None
        worker.control_redis = None

        await worker.cleanup()
        assert worker._cache_stats_task is None
