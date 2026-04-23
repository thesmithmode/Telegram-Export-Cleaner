
import json
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from models import ExportRequest
from queue_consumer import QueueConsumer


def _make_job_json(task_id: str, source: str = "bot") -> str:
    return json.dumps({
        "task_id": task_id,
        "user_id": 111111,
        "chat_id": -1001234567890,
        "limit": 0,
        "offset_id": 0,
        "source": source,
    })


def _make_consumer() -> QueueConsumer:
    """Создаёт QueueConsumer с замоканными settings."""
    with patch("queue_consumer.settings") as mock_settings:
        mock_settings.REDIS_HOST = "redis"
        mock_settings.REDIS_PORT = 6379
        mock_settings.REDIS_DB = 0
        mock_settings.REDIS_PASSWORD = None
        mock_settings.REDIS_QUEUE_NAME = "telegram_export"
        mock_settings.REDIS_SUBSCRIPTION_QUEUE_SUFFIX = "_subscription"
        return QueueConsumer()


class TestQueuePriority:

    @pytest.mark.asyncio
    async def test_prefers_express_over_main_and_subscription(self):
        """Когда все три очереди непустые — express забирается первой."""
        consumer = _make_consumer()
        consumer.redis_client = AsyncMock()

        express_payload = _make_job_json("express_task_001", source="bot")
        # blmove: express → возвращает задачу, main/subscription не опрашиваются
        consumer.redis_client.blmove = AsyncMock(return_value=express_payload)
        consumer.redis_client.sadd = AsyncMock()
        consumer.redis_client.setex = AsyncMock()

        job = await consumer.get_job()

        assert job is not None
        assert job.task_id == "express_task_001"
        # Первый вызов blmove — это express-очередь
        first_call = consumer.redis_client.blmove.call_args_list[0]
        assert first_call.args[0] == consumer.express_queue_name
        # Второй вызов (main или subscription) не должен был произойти
        assert consumer.redis_client.blmove.call_count == 1

    @pytest.mark.asyncio
    async def test_prefers_main_over_subscription(self):
        """Express пуста, main и subscription непустые — main забирается."""
        consumer = _make_consumer()
        consumer.redis_client = AsyncMock()

        main_payload = _make_job_json("main_task_001", source="bot")

        async def _blmove_side_effect(*args, **kwargs):
            src = args[0]
            if src == consumer.express_queue_name:
                return None   # express пуста
            if src == consumer.queue_name:
                return main_payload   # main имеет задачу
            return None   # subscription (не должна дойти)

        consumer.redis_client.blmove = AsyncMock(side_effect=_blmove_side_effect)
        consumer.redis_client.sadd = AsyncMock()
        consumer.redis_client.setex = AsyncMock()

        job = await consumer.get_job()

        assert job is not None
        assert job.task_id == "main_task_001"
        # Всего два вызова: express (None) → main (payload)
        assert consumer.redis_client.blmove.call_count == 2
        calls = consumer.redis_client.blmove.call_args_list
        assert calls[0].args[0] == consumer.express_queue_name
        assert calls[1].args[0] == consumer.queue_name

    @pytest.mark.asyncio
    async def test_falls_back_to_subscription_when_others_empty(self):
        """Express и main пусты — задача берётся из subscription."""
        consumer = _make_consumer()
        consumer.redis_client = AsyncMock()

        subscription_payload = _make_job_json("sub_task_001", source="subscription")

        async def _blmove_side_effect(*args, **kwargs):
            src = args[0]
            if src == consumer.express_queue_name:
                return None
            if src == consumer.queue_name:
                return None
            if src == consumer.subscription_queue_name:
                return subscription_payload
            return None

        consumer.redis_client.blmove = AsyncMock(side_effect=_blmove_side_effect)
        consumer.redis_client.sadd = AsyncMock()
        consumer.redis_client.setex = AsyncMock()

        job = await consumer.get_job()

        assert job is not None
        assert job.task_id == "sub_task_001"
        assert job.source == "subscription"
        # Все три очереди опрошены
        assert consumer.redis_client.blmove.call_count == 3
        calls = consumer.redis_client.blmove.call_args_list
        assert calls[0].args[0] == consumer.express_queue_name
        assert calls[1].args[0] == consumer.queue_name
        assert calls[2].args[0] == consumer.subscription_queue_name

    @pytest.mark.asyncio
    async def test_returns_none_when_all_queues_empty(self):
        """Все три очереди пусты — возвращается None."""
        consumer = _make_consumer()
        consumer.redis_client = AsyncMock()
        consumer.redis_client.blmove = AsyncMock(return_value=None)
        consumer.redis_client.sadd = AsyncMock()

        job = await consumer.get_job()

        assert job is None
        assert consumer.redis_client.blmove.call_count == 3

    def test_consumer_has_subscription_queue_name(self):
        """QueueConsumer должен хранить имя subscription-очереди как атрибут."""
        consumer = _make_consumer()
        assert hasattr(consumer, "subscription_queue_name")
        assert consumer.subscription_queue_name == "telegram_export_subscription"

    def test_consumer_has_staging_subscription_name(self):
        """QueueConsumer должен хранить имя staging-очереди для subscription."""
        consumer = _make_consumer()
        assert hasattr(consumer, "staging_subscription_name")
        assert consumer.staging_subscription_name == "telegram_export_subscription_processing"
