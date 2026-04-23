
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from main import ExportWorker
from message_cache import MessageCache
from models import ExportRequest, ExportedMessage


def _make_subscription_job() -> ExportRequest:
    return ExportRequest(
        task_id="sub_export_001",
        user_id=123456789,
        user_chat_id=123456789,
        chat_id=-1001234567890,
        limit=0,
        offset_id=0,
        from_date="2026-04-01T00:00:00",
        to_date="2026-04-23T23:59:59",
        source="subscription",
        subscription_id=42,
    )


def _make_worker() -> ExportWorker:
    worker = ExportWorker()
    worker.queue_consumer = AsyncMock()
    worker.queue_consumer.mark_job_completed = AsyncMock(return_value=True)
    worker.queue_consumer.mark_job_failed = AsyncMock(return_value=True)
    worker.queue_consumer.mark_job_processing = AsyncMock(return_value=True)
    worker.telegram_client = AsyncMock()
    worker.telegram_client.verify_and_get_info = AsyncMock(
        return_value=(True, {"title": "Test Channel", "type": "channel", "id": -1001234567890}, None)
    )
    worker.telegram_client.get_messages_count = AsyncMock(return_value=0)
    worker.java_client = AsyncMock()
    worker.java_client.notify_subscription_empty = AsyncMock()
    worker.java_client.send_response = AsyncMock(return_value=True)
    worker.control_redis = AsyncMock()
    worker.control_redis.get = AsyncMock(return_value=None)
    worker.control_redis.set = AsyncMock()
    worker.control_redis.pipeline = MagicMock(return_value=AsyncMock())
    worker.message_cache = MessageCache(enabled=False)
    return worker


class TestSubscriptionEmptyExport:

    @pytest.mark.asyncio
    async def test_empty_export_for_subscription_sends_text_not_file(self):
        """Подписка + 0 сообщений → send_message (notify_subscription_empty),
        NOT send_response/send_document."""
        worker = _make_worker()
        job = _make_subscription_job()

        chat_info = {"title": "Test Channel", "type": "channel"}

        await worker._send_completed_result(
            job=job,
            msg_count=0,
            messages_for_send=[],
            chat_info=chat_info,
        )

        # Должен вызваться notify_subscription_empty
        worker.java_client.notify_subscription_empty.assert_called_once()
        call_args = worker.java_client.notify_subscription_empty.call_args
        assert call_args.args[0] == job.user_chat_id  # chat_id
        assert "Test Channel" in call_args.args[1]     # chat_label содержит название чата

        # send_response (отправка файла) НЕ должен вызываться
        worker.java_client.send_response.assert_not_called()

        # Задача должна быть помечена как завершённая
        worker.queue_consumer.mark_job_completed.assert_called_once_with(job.task_id)

    @pytest.mark.asyncio
    async def test_non_empty_export_for_subscription_sends_file(self):
        """Подписка + 5+ сообщений → send_response (файл), НЕ notify_subscription_empty."""
        worker = _make_worker()
        job = _make_subscription_job()

        messages = [
            ExportedMessage(id=i, date=f"2026-04-0{i}T10:00:00", text=f"Message {i}")
            for i in range(1, 6)
        ]
        chat_info = {"title": "Test Channel", "type": "channel"}

        await worker._send_completed_result(
            job=job,
            msg_count=5,
            messages_for_send=messages,
            chat_info=chat_info,
        )

        # send_response должен вызваться (отправка файла)
        worker.java_client.send_response.assert_called_once()

        # notify_subscription_empty НЕ должен вызываться
        worker.java_client.notify_subscription_empty.assert_not_called()

    @pytest.mark.asyncio
    async def test_bot_source_zero_messages_uses_regular_path(self):
        """source=bot + 0 сообщений → стандартный путь через send_response,
        notify_subscription_empty НЕ вызывается."""
        worker = _make_worker()
        job = ExportRequest(
            task_id="bot_export_001",
            user_id=123456789,
            user_chat_id=123456789,
            chat_id=-1001234567890,
            limit=0,
            offset_id=0,
            from_date="2026-04-01T00:00:00",
            to_date="2026-04-23T23:59:59",
            source="bot",
        )
        chat_info = {"title": "Test Channel", "type": "channel"}

        await worker._send_completed_result(
            job=job,
            msg_count=0,
            messages_for_send=[],
            chat_info=chat_info,
        )

        # notify_subscription_empty НЕ должен вызываться для source=bot
        worker.java_client.notify_subscription_empty.assert_not_called()

        # send_response должен вызваться (стандартный путь)
        worker.java_client.send_response.assert_called_once()

    @pytest.mark.asyncio
    async def test_subscription_empty_includes_date_range_in_notification(self):
        """Уведомление об итерации подписки должно отправляться с правильными датами."""
        worker = _make_worker()
        job = _make_subscription_job()
        chat_info = {"title": "МойКанал", "type": "channel"}

        await worker._send_completed_result(
            job=job,
            msg_count=0,
            messages_for_send=[],
            chat_info=chat_info,
        )

        worker.java_client.notify_subscription_empty.assert_called_once()
        call_args = worker.java_client.notify_subscription_empty.call_args
        # Передаём from_date и to_date из job
        assert call_args.args[2] == job.from_date
        assert call_args.args[3] == job.to_date
