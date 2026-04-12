
import json
import pytest
from unittest.mock import Mock, MagicMock, AsyncMock, patch

from models import ExportRequest
from queue_consumer import QueueConsumer

class TestQueueConsumer:

    def test_init(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()

            assert consumer.queue_name == "telegram_export"
            assert consumer.redis_client is None

    def test_redis_url_without_password(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()

            assert consumer.redis_url == "redis://redis:6379/0"

    def test_redis_url_with_password(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = "secret123"
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()

            assert consumer.redis_url == "redis://:secret123@redis:6379/0"

    def test_job_serialization(self):
        job = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            limit=1000
        )

        job_json = json.dumps(job.model_dump())
        job_data = json.loads(job_json)

        # Verify round-trip
        restored = ExportRequest(**job_data)
        assert restored.task_id == job.task_id
        assert restored.chat_id == job.chat_id

    def test_job_deserialization_invalid_json(self):
        invalid_json = "not valid json {"

        try:
            data = json.loads(invalid_json)
            pytest.fail("Should have raised JSONDecodeError")
        except json.JSONDecodeError:
            # Expected
            pass

    def test_job_deserialization_missing_field(self):
        incomplete_job = {
            "task_id": "export_12345",
            # Missing user_id and chat_id
        }

        try:
            job = ExportRequest(**incomplete_job)
            pytest.fail("Should have raised validation error")
        except Exception:
            # Expected
            pass

    def test_job_with_optional_fields(self):
        job_data = {
            "task_id": "export_12345",
            "user_id": 123456789,
            "chat_id": -1001234567890,
            "from_date": "2025-01-01T00:00:00",
            "to_date": "2025-12-31T23:59:59"
        }

        job = ExportRequest(**job_data)

        assert job.from_date == "2025-01-01T00:00:00"
        assert job.to_date == "2025-12-31T23:59:59"

@pytest.mark.asyncio
class TestQueueConsumerJobManagement:

    async def test_push_job_serializes_and_rpush(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.rpush = AsyncMock(return_value=1)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                job = ExportRequest(
                    task_id="test_123",
                    user_id=456,
                    chat_id=-1001234567890,
                    limit=100
                )

                result = await consumer.push_job(job)

                assert result is True
                mock_client.rpush.assert_called_once()
                # Verify job was serialized to JSON
                call_args = mock_client.rpush.call_args
                assert "telegram_export" in call_args[0]
                # Check JSON contains task_id
                json_data = json.loads(call_args[0][1])
                assert json_data['task_id'] == "test_123"

    async def test_mark_job_processing_sets_key_with_ttl(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"
            mock_settings.JOB_TIMEOUT = 3600

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.setex = AsyncMock(return_value=True)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                result = await consumer.mark_job_processing("test_123")

                assert result is True
                mock_client.setex.assert_called_once()
                # Verify key format and TTL
                call_args = mock_client.setex.call_args
                assert "job:processing:test_123" in call_args[0]
                assert call_args[0][1] == 3600  # TTL

    async def test_mark_job_completed_deletes_and_sets(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                # No staging meta → lrem path skipped
                mock_client.get = AsyncMock(return_value=None)

                # Pipeline mock: sync pipeline(), sync queueing, async execute()
                mock_pipe = MagicMock()
                mock_pipe.execute = AsyncMock(return_value=[1, 1, 1, 1])
                mock_client.pipeline = MagicMock(return_value=mock_pipe)

                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                result = await consumer.mark_job_completed("test_123")

                assert result is True
                # Regression: pipeline must be created with explicit transaction=True
                mock_client.pipeline.assert_called_once_with(transaction=True)
                # delete queued for processing key
                mock_pipe.delete.assert_any_call("job:processing:test_123")
                # setex queued for completed key
                assert any(
                    call.args[0] == "job:completed:test_123"
                    for call in mock_pipe.setex.call_args_list
                )
                # Pipeline actually executed (otherwise nothing happens on Redis)
                mock_pipe.execute.assert_awaited_once()

    async def test_mark_job_failed_stores_error_json(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.delete = AsyncMock(return_value=1)
                mock_client.setex = AsyncMock(return_value=True)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                result = await consumer.mark_job_failed("test_123", "Network timeout")

                assert result is True
                mock_client.delete.assert_called_once()
                mock_client.setex.assert_called_once()
                # Verify error is stored as JSON
                call_args = mock_client.setex.call_args
                json_value = json.loads(call_args[0][2])
                assert json_value['error'] == "Network timeout"
                assert 'timestamp' in json_value

    async def test_get_queue_stats_returns_count(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.llen = AsyncMock(return_value=42)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                consumer.redis_client = mock_client

                result = await consumer.get_queue_stats()

                assert result is not None
                assert result['queue_name'] == "telegram_export"
                assert result['pending_jobs'] == 42
                assert 'timestamp' in result
                mock_client.llen.assert_called_once_with("telegram_export")

@pytest.mark.asyncio
class TestQueueConsumerAsync:

    async def test_context_manager(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.ping = AsyncMock(return_value=True)
                mock_client.close = AsyncMock()
                mock_redis.return_value = mock_client

                async with QueueConsumer() as consumer:
                    assert consumer.redis_client == mock_client

    async def test_connect_success(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.ping = AsyncMock(return_value=True)
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                result = await consumer.connect()

                assert result is True
                assert consumer.is_connected is True

    async def test_disconnect(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            with patch('queue_consumer.redis.from_url', new_callable=AsyncMock) as mock_redis:
                mock_client = AsyncMock()
                mock_client.ping = AsyncMock(return_value=True)
                mock_client.close = AsyncMock()
                mock_redis.return_value = mock_client

                consumer = QueueConsumer()
                await consumer.connect()
                await consumer.disconnect()

                mock_client.close.assert_called_once()

class TestExportRequestIntegration:

    def test_complete_job_message(self):
        job_msg = {
            "task_id": "export_12345",
            "user_id": 123456789,
            "chat_id": -1001234567890,
            "limit": 1000,
            "offset_id": 0,
            "from_date": "2025-06-01T00:00:00",
            "to_date": "2025-06-30T23:59:59"
        }

        # Should not raise
        job = ExportRequest(**job_msg)

        assert job.task_id == "export_12345"
        assert job.limit == 1000

    def test_minimal_job_message(self):
        job_msg = {
            "task_id": "export_12345",
            "user_id": 123456789,
            "chat_id": -1001234567890
        }

        # Should not raise
        job = ExportRequest(**job_msg)

        assert job.task_id == "export_12345"
        assert job.limit == 0  # Default

    def test_job_with_zero_limit(self):
        job = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            limit=0  # Unlimited
        )

        assert job.limit == 0

    def test_job_with_negative_chat_id(self):
        job = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890  # Group/channel
        )

        assert job.chat_id == -1001234567890

    def test_job_type_coercion(self):
        job_msg = {
            "task_id": "export_12345",
            "user_id": "123456789",  # String
            "chat_id": "-1001234567890",  # String
            "limit": "1000"  # String
        }

        job = ExportRequest(**job_msg)

        assert isinstance(job.user_id, int)
        assert isinstance(job.chat_id, int)
        assert isinstance(job.limit, int)

@pytest.mark.asyncio
class TestGetPendingJobs:

    async def test_returns_parsed_jobs(self):
        job1 = ExportRequest(task_id="t1", user_id=1, chat_id=100)
        job2 = ExportRequest(task_id="t2", user_id=2, chat_id=200)

        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()
            mock_client = AsyncMock()
            mock_client.llen = AsyncMock(side_effect=lambda key: 2 if "express" in key else 0)
            mock_client.lrange = AsyncMock(return_value=[
                json.dumps(job1.model_dump()),
                json.dumps(job2.model_dump()),
            ])
            consumer.redis_client = mock_client

            result = await consumer.get_pending_jobs()

            assert result["total_count"] == 2
            assert len(result["jobs"]) == 2
            assert result["jobs"][0].task_id == "t1"
            assert result["jobs"][1].task_id == "t2"
            mock_client.lrange.assert_called()

    async def test_returns_empty_on_no_client(self):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()
            # redis_client is None by default
            result = await consumer.get_pending_jobs()
            assert result == {"jobs": [], "total_count": 0}

    async def test_skips_invalid_json(self):
        valid_job = ExportRequest(task_id="t1", user_id=1, chat_id=100)

        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()
            mock_client = AsyncMock()
            mock_client.llen = AsyncMock(side_effect=lambda key: 2 if "express" in key else 0)
            mock_client.lrange = AsyncMock(return_value=[
                "not valid json {",
                json.dumps(valid_job.model_dump()),
            ])
            consumer.redis_client = mock_client

            result = await consumer.get_pending_jobs()

            assert len(result["jobs"]) == 1
            assert result["jobs"][0].task_id == "t1"

@pytest.mark.asyncio
class TestDeadLetterQueue:

    def _make_consumer(self, mock_client):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"
            consumer = QueueConsumer()
        consumer.redis_client = mock_client
        return consumer

    @staticmethod
    def _dlq_calls(mock_client):
        return [
            c for c in mock_client.rpush.call_args_list
            if c.args and "_dead" in c.args[0]
        ]

    async def test_invalid_json_moves_to_dlq(self):
        mock_client = AsyncMock()
        mock_client.blpop = AsyncMock(return_value=("telegram_export", "not valid json {"))
        mock_client.rpush = AsyncMock(return_value=1)
        mock_client.lrem = AsyncMock(return_value=1)

        consumer = self._make_consumer(mock_client)
        result = await consumer.get_job()

        assert result is None
        dlq_calls = self._dlq_calls(mock_client)
        assert len(dlq_calls) == 1, "Невалидный JSON должен ровно один раз уйти в DLQ"
        dlq_call = dlq_calls[0]
        assert dlq_call.args[0] == "telegram_export_dead"
        dlq_entry = json.loads(dlq_call.args[1])
        assert "JSON parse error" in dlq_entry["reason"]
        assert dlq_entry["raw"] == "not valid json {"
        # И должен быть удалён из staging чтобы не подняться при recover_staging
        mock_client.lrem.assert_called_once_with(
            "telegram_export_processing", 1, "not valid json {"
        )

    async def test_validation_error_moves_to_dlq(self):
        invalid_job = json.dumps({"task_id": "t1"})  # Missing user_id, chat_id
        mock_client = AsyncMock()
        mock_client.blpop = AsyncMock(return_value=("telegram_export", invalid_job))
        mock_client.rpush = AsyncMock(return_value=1)
        mock_client.lrem = AsyncMock(return_value=1)

        consumer = self._make_consumer(mock_client)
        result = await consumer.get_job()

        assert result is None
        dlq_calls = self._dlq_calls(mock_client)
        assert len(dlq_calls) == 1, "Невалидная задача должна ровно один раз уйти в DLQ"
        dlq_call = dlq_calls[0]
        assert dlq_call.args[0] == "telegram_export_dead"
        dlq_entry = json.loads(dlq_call.args[1])
        assert "Validation error" in dlq_entry["reason"]
        mock_client.lrem.assert_called_once_with(
            "telegram_export_processing", 1, invalid_job
        )

    async def test_valid_job_does_not_go_to_dlq(self):
        valid_job = json.dumps({
            "task_id": "export_ok",
            "user_id": 123,
            "chat_id": -1001234567890
        })
        mock_client = AsyncMock()
        mock_client.blpop = AsyncMock(return_value=("telegram_export", valid_job))
        mock_client.rpush = AsyncMock()

        consumer = self._make_consumer(mock_client)
        result = await consumer.get_job()

        assert result is not None
        assert result.task_id == "export_ok"
        # В DLQ не уходит — но в staging RPUSH делается (см. TestStagingDurability)
        assert self._dlq_calls(mock_client) == []

class TestStagingDurability:

    def _make_consumer(self, mock_redis_client):
        with patch('queue_consumer.settings') as mock_settings:
            mock_settings.REDIS_HOST = "redis"
            mock_settings.REDIS_PORT = 6379
            mock_settings.REDIS_DB = 0
            mock_settings.REDIS_PASSWORD = None
            mock_settings.REDIS_QUEUE_NAME = "telegram_export"

            consumer = QueueConsumer()
            consumer.redis_client = mock_redis_client
            return consumer

    @pytest.mark.asyncio
    async def test_get_job_pushes_to_staging(self):
        valid_job = json.dumps({
            "task_id": "task_123",
            "user_id": 456,
            "chat_id": -1001234567890
        })
        mock_client = AsyncMock()
        mock_client.blpop = AsyncMock(return_value=("telegram_export", valid_job))
        mock_client.rpush = AsyncMock()
        mock_client.sadd = AsyncMock()
        mock_client.setex = AsyncMock()

        consumer = self._make_consumer(mock_client)
        result = await consumer.get_job()

        assert result is not None
        assert result.task_id == "task_123"
        # Job pushed to staging (not LMOVE — BLPOP already removed it)
        mock_client.rpush.assert_called_once_with(
            "telegram_export_processing", valid_job
        )
        # Job tracked in staging set
        mock_client.sadd.assert_called_once_with("staging:jobs", "task_123")
        # Staging payload persisted for cleanup on completion
        setex_calls = mock_client.setex.call_args_list
        staging_meta_call = [c for c in setex_calls if "staging:meta:task_123" in str(c)]
        assert len(staging_meta_call) == 1

    @pytest.mark.asyncio
    async def test_express_job_pushes_to_express_staging(self):
        valid_job = json.dumps({
            "task_id": "express_task",
            "user_id": 456,
            "chat_id": -1001234567890
        })
        mock_client = AsyncMock()
        mock_client.blpop = AsyncMock(return_value=("telegram_export_express", valid_job))
        mock_client.rpush = AsyncMock()
        mock_client.sadd = AsyncMock()
        mock_client.setex = AsyncMock()

        consumer = self._make_consumer(mock_client)
        result = await consumer.get_job()

        assert result is not None
        mock_client.rpush.assert_called_once_with(
            "telegram_export_express_processing", valid_job
        )

    @pytest.mark.asyncio
    async def test_invalid_json_goes_to_dlq_and_removed_from_staging(self):
        invalid_json = "not valid json"
        mock_client = AsyncMock()
        mock_client.blpop = AsyncMock(return_value=("telegram_export", invalid_json))
        mock_client.rpush = AsyncMock()
        mock_client.lrem = AsyncMock()

        consumer = self._make_consumer(mock_client)
        result = await consumer.get_job()

        assert result is None
        # Invalid job removed from staging via LREM с конкретным payload
        mock_client.lrem.assert_called_once_with(
            "telegram_export_processing", 1, invalid_json
        )
        # Moved to DLQ
        dlq_call = [c for c in mock_client.rpush.call_args_list if "dead" in str(c)]
        assert len(dlq_call) > 0

    @pytest.mark.asyncio
    async def test_recover_staging_jobs(self):
        mock_client = AsyncMock()
        # First call moves item, second returns None (empty)
        mock_client.lmove = AsyncMock(side_effect=[b'{"task_id":"t1"}', None, b'{"task_id":"t2"}', None])
        mock_client.delete = AsyncMock()

        consumer = self._make_consumer(mock_client)
        count = await consumer.recover_staging_jobs()

        assert count == 2
        assert mock_client.lmove.call_count == 4
        mock_client.delete.assert_called_once_with("staging:jobs")

    @pytest.mark.asyncio
    async def test_mark_job_completed_untracks_and_removes_staging(self):
        import json as _json
        staging_meta = _json.dumps({
            "payload": '{"task_id":"task_123"}',
            "queue": "telegram_export_processing"
        })
        mock_client = AsyncMock()
        mock_client.get = AsyncMock(return_value=staging_meta)

        # Pipeline mock: sync queueing, async execute
        mock_pipe = MagicMock()
        mock_pipe.execute = AsyncMock(return_value=[1, 1, 1, 1, 1])
        mock_client.pipeline = MagicMock(return_value=mock_pipe)

        consumer = self._make_consumer(mock_client)
        await consumer.mark_job_completed("task_123")

        # Regression S2: transaction=True обязателен — иначе partial-fail
        # оставит job в несогласованном состоянии (processing deleted, staging
        # ещё числит → recover_staging_jobs перезапустит уже завершённую).
        mock_client.pipeline.assert_called_once_with(transaction=True)

        # Staging payload получен ДО pipeline (GET не может быть в MULTI с чтением)
        mock_client.get.assert_called_with("staging:meta:task_123")

        # Все write-операции заказаны в pipe, а не напрямую в клиент
        mock_pipe.srem.assert_called_with("staging:jobs", "task_123")
        mock_pipe.lrem.assert_called_once_with(
            "telegram_export_processing", 1, '{"task_id":"task_123"}'
        )
        # delete вызывается дважды: processing key и staging:meta
        delete_args = [c.args for c in mock_pipe.delete.call_args_list]
        assert ("job:processing:task_123",) in delete_args
        assert ("staging:meta:task_123",) in delete_args

        # Pipeline реально выполнен (execute awaited)
        mock_pipe.execute.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_mark_job_completed_pipeline_failure_reports_false(self):
        import json as _json
        staging_meta = _json.dumps({
            "payload": '{"task_id":"task_123"}',
            "queue": "telegram_export_processing"
        })
        mock_client = AsyncMock()
        mock_client.get = AsyncMock(return_value=staging_meta)

        mock_pipe = MagicMock()
        mock_pipe.execute = AsyncMock(side_effect=Exception("EXEC failed"))
        mock_client.pipeline = MagicMock(return_value=mock_pipe)

        consumer = self._make_consumer(mock_client)
        result = await consumer.mark_job_completed("task_123")

        assert result is False, (
            "При падении pipeline.execute() функция ДОЛЖНА вернуть False, "
            "иначе caller поверит в успех и не перезапустит job"
        )
        mock_client.pipeline.assert_called_once_with(transaction=True)

    @pytest.mark.asyncio
    async def test_mark_job_failed_untracks_and_removes_staging(self):
        import json as _json
        staging_meta = _json.dumps({
            "payload": '{"task_id":"task_123"}',
            "queue": "telegram_export_processing"
        })
        mock_client = AsyncMock()
        mock_client.get = AsyncMock(return_value=staging_meta)
        mock_client.lrem = AsyncMock()
        mock_client.delete = AsyncMock()
        consumer = self._make_consumer(mock_client)
        await consumer.mark_job_failed("task_123", "some error")

        mock_client.srem.assert_called_with("staging:jobs", "task_123")
        mock_client.lrem.assert_called_once_with(
            "telegram_export_processing", 1, '{"task_id":"task_123"}'
        )
