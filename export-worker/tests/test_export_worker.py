
import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch, call
from datetime import datetime

from main import ExportWorker
from java_client import ProgressTracker
from message_cache import MessageCache
from models import ExportRequest, ExportedMessage

def _make_mock_java_client():
    client = AsyncMock()
    client.send_progress_update = AsyncMock(return_value=12345)
    client.create_progress_tracker = lambda uid, tid, topic_name=None: ProgressTracker(client, uid, tid, topic_name=topic_name)
    return client

class TestExportWorkerInitialization:

    @pytest.mark.asyncio
    async def test_initialize_success(self):
        worker = ExportWorker()

        # Mock the component creation
        with patch('main.create_queue_consumer') as mock_queue_creator, \
             patch('main.create_telegram_client') as mock_telegram_creator, \
             patch('main.create_java_client') as mock_java_creator:

            mock_queue = AsyncMock()
            mock_queue.connect = AsyncMock(return_value=True)
            mock_queue_creator.return_value = mock_queue

            mock_telegram = AsyncMock()
            mock_telegram.connect = AsyncMock(return_value=True)
            mock_telegram_creator.return_value = mock_telegram

            mock_java = AsyncMock()
            mock_java_creator.return_value = mock_java

            result = await worker.initialize()

            assert result is True
            assert worker.queue_consumer is not None
            assert worker.telegram_client is not None
            assert worker.java_client is not None

    @pytest.mark.asyncio
    async def test_initialize_queue_failure(self):
        worker = ExportWorker()

        with patch('main.create_queue_consumer') as mock_queue_creator:
            mock_queue = AsyncMock()
            mock_queue.connect = AsyncMock(return_value=False)
            mock_queue_creator.return_value = mock_queue

            result = await worker.initialize()

            assert result is False

class TestExportWorkerJobProcessing:

    @pytest.fixture
    def worker(self):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        # Disabled cache — tests exercise direct Telegram fetch path
        worker.message_cache = MessageCache(enabled=False)
        return worker

    @pytest.mark.asyncio
    async def test_process_job_success(self, worker):
        job = ExportRequest(
            task_id="test_task_123",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        # Mock Telegram client
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test Chat", "type": "supergroup"}, None)
        )

        # Mock message generator
        messages = [
            ExportedMessage(id=2, date="2025-01-01T00:00:01", text="World"),
            ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Hello"),
        ]

        async def mock_history(*args, **kwargs):
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history

        # Mock Java client
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert worker.jobs_processed == 1
        worker.java_client.send_response.assert_called_once()
        payload = worker.java_client.send_response.call_args[0][0]
        assert payload.task_id == "test_task_123"
        assert payload.status == "completed"
        assert len(payload.messages) == 2

    @pytest.mark.asyncio
    async def test_process_job_chat_not_accessible(self, worker):
        job = ExportRequest(
            task_id="test_task_456",
            user_id=123,
            user_chat_id=123,
            chat_id=999,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(False, None, "CHAT_NOT_ACCESSIBLE")
        )
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_failed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert worker.jobs_failed == 0  # Not counted in jobs_failed, marked separately
        worker.java_client.send_response.assert_called_once()
        payload = worker.java_client.send_response.call_args[0][0]
        assert payload.status == "failed"
        assert payload.error_code == "CHAT_NOT_ACCESSIBLE"

    @pytest.mark.asyncio
    async def test_process_job_export_error(self, worker):
        job = ExportRequest(
            task_id="test_task_789",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "supergroup"}, None)
        )

        # Mock error during export
        async def mock_history_error(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="First")
            raise ValueError("Export error")

        worker.telegram_client.get_chat_history = mock_history_error
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_failed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert worker.jobs_failed == 1
        worker.java_client.send_response.assert_called_once()
        payload = worker.java_client.send_response.call_args[0][0]
        assert payload.status == "failed"
        assert "Export failed" in payload.error

    @pytest.mark.asyncio
    async def test_process_job_response_failure(self, worker):
        job = ExportRequest(
            task_id="test_task_101",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "supergroup"}, None)
        )

        # Mock successful export
        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Message")

        worker.telegram_client.get_chat_history = mock_history

        # Mock Java client failure
        worker.java_client.send_response = AsyncMock(return_value=False)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_failed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert worker.jobs_failed == 1
        worker.queue_consumer.mark_job_failed.assert_called()

def _make_pipeline_mock():
    pipe = MagicMock()
    pipe.execute = AsyncMock(return_value=[True, True])
    return pipe

class TestChatIdNormalization:

    @pytest.fixture
    def worker(self):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=1)
        worker.message_cache = MessageCache(enabled=False)
        worker.control_redis = AsyncMock()
        worker.control_redis.get = AsyncMock(return_value=None)
        # Pipeline mock — canonical: mappings use pipe.set / pipe.execute.
        # Без этого pipe = control_redis.pipeline() вернул бы coroutine и упал.
        pipe = _make_pipeline_mock()
        worker.control_redis.pipeline = MagicMock(return_value=pipe)
        worker._test_pipe = pipe  # ← тесты читают, чтобы ассертить canonical:keys
        return worker

    @pytest.mark.asyncio
    async def test_chat_id_normalized_from_username_to_numeric(self, worker):
        canonical_id = -1002477958568
        job = ExportRequest(
            task_id="norm_task_1", user_id=1, user_chat_id=1,
            chat_id="strbypass", limit=0, offset_id=0,
        )

        # verify_and_get_info возвращает канонический числовой ID
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": canonical_id, "title": "Test", "type": "supergroup"}, None)
        )

        messages = [ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Hi")]

        async def mock_history(*args, **kwargs):
            # Проверяем: get_chat_history вызывается с нормализованным числовым ID
            assert kwargs.get("chat_id") == canonical_id or args[0] == canonical_id
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        result = await worker.process_job(job)
        assert result is True

        # СТРОГО: canonical-маппинг должен быть записан в Redis, иначе Java-бот
        # не сможет зарезолвить username→numeric на fast-path при следующем
        # экспорте того же чата.
        worker.control_redis.pipeline.assert_called_once()
        pipe_set_calls = worker._test_pipe.set.call_args_list
        matching = [
            c for c in pipe_set_calls
            if c.args[:2] == ("canonical:strbypass", str(canonical_id))
        ]
        assert matching, (
            f"canonical:<input>→<numeric_id> не записан в Redis. "
            f"Все pipe.set вызовы: {list(pipe_set_calls)}"
        )
        # TTL должен быть ~30 дней — иначе маппинг слишком быстро протухнет
        assert matching[0].kwargs.get("ex") == 86400 * 30
        # pipe.execute должен быть awaited — иначе команды просто накоплены и не ушли в Redis
        worker._test_pipe.execute.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_chat_id_no_change_when_already_canonical(self, worker):
        canonical_id = -1002477958568
        job = ExportRequest(
            task_id="norm_task_2", user_id=1, user_chat_id=1,
            chat_id=canonical_id, limit=0, offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": canonical_id, "title": "Test", "type": "supergroup", "username": "test_chat"}, None)
        )

        messages = [ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Hi")]

        async def mock_history(*args, **kwargs):
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        result = await worker.process_job(job)
        assert result is True

        # СТРОГО: при наличии username пишется И прямой маппинг canonical:<input>,
        # И обратный canonical:<numeric_id>→<username> — чтобы пикер мог резолвить
        # numeric ID из выбора юзера в username для повторного экспорта.
        pipe_set_calls = worker._test_pipe.set.call_args_list
        forward = [
            c for c in pipe_set_calls
            if c.args[:2] == (f"canonical:{canonical_id}", str(canonical_id))
        ]
        reverse = [
            c for c in pipe_set_calls
            if c.args[:2] == (f"canonical:{canonical_id}", "test_chat")
        ]
        assert forward, (
            f"Прямой canonical:<input>→<numeric_id> не записан. "
            f"Все pipe.set: {list(pipe_set_calls)}"
        )
        assert reverse, (
            f"Обратный canonical:<numeric_id>→<username> не записан. "
            f"Все pipe.set: {list(pipe_set_calls)}"
        )
        worker._test_pipe.execute.assert_awaited_once()

class TestThreePathCaching:

    @pytest.fixture
    async def worker_with_cache(self, tmp_path):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()
        worker.queue_consumer.mark_job_failed = AsyncMock()

        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.java_client.send_response = AsyncMock(return_value=True)

        # Реальный SQLite-кэш
        worker.message_cache = MessageCache(
            db_path=str(tmp_path / "worker_cache.db"),
            max_disk_bytes=10 * 1024 * 1024,
            max_messages_per_chat=10_000,
            ttl_seconds=3600,
            enabled=True,
        )
        await worker.message_cache.initialize()

        # control_redis с правильно замоканным pipeline
        worker.control_redis = AsyncMock()
        worker.control_redis.get = AsyncMock(return_value=None)
        pipe = _make_pipeline_mock()
        worker.control_redis.pipeline = MagicMock(return_value=pipe)

        yield worker
        await worker.message_cache.close()

    @pytest.mark.asyncio
    async def test_date_hit_does_not_call_telegram_history(self, worker_with_cache):
        worker = worker_with_cache
        CHAT_ID = 555001

        # Предварительно заполняем кэш сообщениями за Jan 1-10
        prefilled = [
            ExportedMessage(
                id=i,
                date=f"2025-01-{i:02d}T10:00:00",
                text=f"msg{i}",
            )
            for i in range(1, 11)
        ]
        await worker.message_cache.store_messages(CHAT_ID, prefilled)

        # Спец-флаг: если вдруг вызван — тест провалится
        history_calls: list[dict] = []

        async def tripwire_history(*args, **kwargs):
            history_calls.append(kwargs)
            if False:  # pragma: no cover
                yield None

        worker.telegram_client.get_chat_history = tripwire_history
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": CHAT_ID, "title": "Cached Chat", "type": "supergroup"}, None)
        )
        worker.telegram_client.get_messages_count = AsyncMock(return_value=10)

        job = ExportRequest(
            task_id="date_hit_task",
            user_id=1, user_chat_id=1,
            chat_id=CHAT_ID,
            from_date="2025-01-01T00:00:00",
            to_date="2025-01-10T23:59:59",
            limit=0,
            offset_id=0,
        )

        result = await worker.process_job(job)
        assert result is True

        # СТРОГО: ни одного вызова get_chat_history — cache-HIT означает
        # "работаем только с SQLite".
        assert history_calls == [], (
            f"Cache-HIT должен полностью избежать Telegram fetch. "
            f"Но get_chat_history был вызван {len(history_calls)} раз: {history_calls}"
        )

        # Java получил ответ с 10 сообщениями
        worker.java_client.send_response.assert_called_once()
        payload = worker.java_client.send_response.call_args[0][0]
        assert payload.status == "completed"
        assert payload.task_id == "date_hit_task"

    @pytest.mark.asyncio
    async def test_date_partial_miss_fetches_only_missing_gap(self, worker_with_cache):
        worker = worker_with_cache
        CHAT_ID = 555002

        # Предзаполняем два куска с «дыркой» посередине
        part1 = [
            ExportedMessage(id=i, date=f"2025-02-{i:02d}T10:00:00", text=f"m{i}")
            for i in range(1, 6)   # Feb 1-5
        ]
        part2 = [
            ExportedMessage(id=i + 100, date=f"2025-02-{i:02d}T10:00:00", text=f"m{i}")
            for i in range(11, 16)  # Feb 11-15
        ]
        await worker.message_cache.store_messages(CHAT_ID, part1)
        await worker.message_cache.store_messages(CHAT_ID, part2)

        # Трекер gap-запросов
        history_calls: list[dict] = []

        async def tracked_history(*args, **kwargs):
            history_calls.append({
                "from_date": kwargs.get("from_date"),
                "to_date": kwargs.get("to_date"),
            })
            # Отдаём фейковые сообщения за Feb 6-10 — это и есть gap
            for i in range(6, 11):
                yield ExportedMessage(
                    id=i + 50,
                    date=f"2025-02-{i:02d}T10:00:00",
                    text=f"gap_m{i}",
                )

        worker.telegram_client.get_chat_history = tracked_history
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": CHAT_ID, "title": "Gap Chat", "type": "supergroup"}, None)
        )
        worker.telegram_client.get_messages_count = AsyncMock(return_value=15)

        job = ExportRequest(
            task_id="date_gap_task",
            user_id=1, user_chat_id=1,
            chat_id=CHAT_ID,
            from_date="2025-02-01T00:00:00",
            to_date="2025-02-15T23:59:59",
            limit=0,
            offset_id=0,
        )

        result = await worker.process_job(job)
        assert result is True

        # СТРОГО: get_chat_history вызван ровно 1 раз — на gap.
        assert len(history_calls) == 1, (
            f"Ожидался 1 вызов get_chat_history (только на gap Feb 6-10), "
            f"получено {len(history_calls)}: {history_calls}"
        )
        # Gap должен быть Feb 6-10 (или около — допускаем точность до дня)
        gap_from = history_calls[0]["from_date"]
        gap_to = history_calls[0]["to_date"]
        assert gap_from is not None and gap_to is not None
        assert gap_from.strftime("%Y-%m-%d") == "2025-02-06"
        assert gap_to.strftime("%Y-%m-%d") == "2025-02-10"

        # Gap-сообщения реально сохранены в кэше — проверяем через БД напрямую
        date_ranges = await worker.message_cache.get_cached_date_ranges(CHAT_ID)
        # После мерджа должен быть один сплошной интервал Feb 1-15
        assert date_ranges == [["2025-02-01", "2025-02-15"]], (
            f"После закрытия gap date-ranges должны смерджиться в один интервал, "
            f"получено: {date_ranges}"
        )

    @pytest.mark.asyncio
    async def test_id_path_fetches_newer_above_cache_max(self, worker_with_cache):
        worker = worker_with_cache
        CHAT_ID = 555003

        # Предзаполняем кэш id=1..50
        prefilled = [
            ExportedMessage(id=i, date=f"2025-03-01T10:00:{i:02d}", text=f"old_m{i}")
            for i in range(1, 51)
        ]
        await worker.message_cache.store_messages(CHAT_ID, prefilled)

        # Трекер вызовов с параметрами
        history_calls: list[dict] = []

        async def tracked_history(*args, **kwargs):
            history_calls.append(dict(kwargs))
            # Step 1: newer above cache_max_id=50 → id 51..60
            # Step 2: no gaps inside — cache is contiguous
            # Step 3: older than cache_min_id=1 → none (cache_min_id == 1)
            min_id = kwargs.get("min_id", 0)
            offset_id = kwargs.get("offset_id", 0)
            if min_id == 50 and offset_id == 0:
                # Fresh messages above cache
                for i in range(51, 61):
                    yield ExportedMessage(
                        id=i, date=f"2025-03-02T10:00:{i - 50:02d}", text=f"new_m{i}"
                    )
            # all other branches: empty

        worker.telegram_client.get_chat_history = tracked_history
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": CHAT_ID, "title": "ID Chat", "type": "supergroup"}, None)
        )
        worker.telegram_client.get_messages_count = AsyncMock(return_value=60)

        job = ExportRequest(
            task_id="id_path_task",
            user_id=1, user_chat_id=1,
            chat_id=CHAT_ID,
            limit=0,
            offset_id=0,
        )

        result = await worker.process_job(job)
        assert result is True

        # СТРОГО: первый вызов должен быть на min_id=50 (fresh above)
        assert len(history_calls) >= 1
        first = history_calls[0]
        assert first.get("min_id") == 50, (
            f"Первый fetch должен быть fresh-above с min_id=cache_max=50, "
            f"получено: {first}"
        )

        # После завершения в кэше должны быть все 60 сообщений
        all_msgs = await worker.message_cache.get_messages(CHAT_ID, 1, 100)
        assert len(all_msgs) == 60, (
            f"После id-path экспорта кэш должен содержать 60 сообщений (50 старых + "
            f"10 свежих), найдено: {len(all_msgs)}"
        )

        # Ranges смерджились в один непрерывный [1, 60]
        id_ranges = await worker.message_cache.get_cached_ranges(CHAT_ID)
        assert id_ranges == [[1, 60]], f"Ranges должны смерджиться в [[1,60]], получено: {id_ranges}"

    @pytest.mark.asyncio
    async def test_id_path_full_miss_triggers_full_fetch(self, worker_with_cache):
        worker = worker_with_cache
        CHAT_ID = 555004

        async def full_history(*args, **kwargs):
            for i in range(1, 21):
                yield ExportedMessage(
                    id=i, date=f"2025-04-01T10:00:{i:02d}", text=f"m{i}"
                )

        worker.telegram_client.get_chat_history = full_history
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": CHAT_ID, "title": "Empty Chat", "type": "supergroup"}, None)
        )
        worker.telegram_client.get_messages_count = AsyncMock(return_value=20)

        job = ExportRequest(
            task_id="id_full_miss_task",
            user_id=1, user_chat_id=1,
            chat_id=CHAT_ID,
            limit=0,
            offset_id=0,
        )

        result = await worker.process_job(job)
        assert result is True

        # СТРОГО: после экспорта все 20 сообщений должны быть в кэше
        cached = await worker.message_cache.get_messages(CHAT_ID, 1, 100)
        assert len(cached) == 20, (
            f"После FULL MISS все скачанные сообщения должны быть сохранены в "
            f"кэш, иначе следующий экспорт снова полезет в Telegram. "
            f"В кэше: {len(cached)}"
        )
        assert await worker.message_cache.get_cached_ranges(CHAT_ID) == [[1, 20]]

class TestExportWorkerProgressReporting:

    @pytest.fixture
    def worker(self):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        # process_job() сначала зовёт verify_and_get_info — если не замокать,
        # AsyncMock вернёт MagicMock, который нельзя распаковать в (accessible,
        # info, reason), исключение упадёт в outer except и до _fetch_all
        # код вообще не дойдёт.
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "supergroup"}, None)
        )
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        worker.message_cache = MessageCache(enabled=False)
        return worker

    @pytest.mark.asyncio
    async def test_fetch_all_sends_started_with_total(self, worker):
        job = ExportRequest(
            task_id="progress_1", user_id=1, user_chat_id=1,
            chat_id=456, limit=0, offset_id=0,
        )

        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="A")

        worker.telegram_client.get_chat_history = mock_history
        worker.telegram_client.get_messages_count = AsyncMock(return_value=5000)
        worker.java_client.send_response = AsyncMock(return_value=True)

        await worker.process_job(job)

        # Check started notification: start() без total, затем counting(), затем set_total()
        calls = worker.java_client.send_progress_update.call_args_list
        assert len(calls) >= 3
        first_call = calls[0]
        assert first_call.kwargs["started"] is True
        assert first_call.kwargs.get("total") is None
        second_call = calls[1]
        assert second_call.kwargs.get("counting") is True
        third_call = calls[2]
        assert third_call.kwargs["total"] == 5000

    @pytest.mark.asyncio
    async def test_fetch_all_sends_5pct_milestones(self, worker):
        job = ExportRequest(
            task_id="progress_2", user_id=1, user_chat_id=1,
            chat_id=456, limit=0, offset_id=0,
        )

        # 100 messages, total=100 → should fire at 5%, 10%, ... 95%
        messages = [
            ExportedMessage(id=i, date="2025-01-01T00:00:00", text=f"msg{i}")
            for i in range(1, 101)
        ]

        async def mock_history(*args, **kwargs):
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        worker.java_client.send_response = AsyncMock(return_value=True)

        await worker.process_job(job)

        # started + counting + set_total + 19 milestones (5%..95%) + finalize
        progress_calls = worker.java_client.send_progress_update.call_args_list
        assert len(progress_calls) == 23  # 1 start + 1 counting + 1 set_total + 19 milestones + 1 finalize

    @pytest.mark.asyncio
    async def test_process_job_exception_notifies_user(self, worker):
        job = ExportRequest(
            task_id="error_1", user_id=1, user_chat_id=42,
            chat_id=456, limit=0, offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "supergroup"}, None)
        )
        # Make send_response raise to trigger except block
        worker.java_client.send_response = AsyncMock(side_effect=RuntimeError("OOM"))

        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="msg")

        worker.telegram_client.get_chat_history = mock_history

        result = await worker.process_job(job)

        assert result is True
        # User should be notified of error
        worker.java_client.notify_user_failure.assert_called_once()
        call_args = worker.java_client.notify_user_failure.call_args
        assert call_args[0][0] == 42  # user_chat_id

class TestExportWorkerCleanup:

    @pytest.mark.asyncio
    async def test_cleanup_disconnects_all(self):
        worker = ExportWorker()
        worker.running = True
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()

        await worker.cleanup()

        assert worker.running is False
        worker.telegram_client.disconnect.assert_called_once()
        worker.queue_consumer.disconnect.assert_called_once()
        worker.java_client.aclose.assert_called_once()

    @pytest.mark.asyncio
    async def test_cleanup_handles_missing_components(self):
        worker = ExportWorker()
        worker.running = True
        worker.queue_consumer = None
        worker.telegram_client = None
        worker.java_client = None

        # Should not raise
        await worker.cleanup()

        assert worker.running is False

class TestExportWorkerWithCache:

    @pytest.fixture
    async def worker(self, tmp_path):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        cache = MessageCache(
            db_path=str(tmp_path / "test_cache.db"),
            max_disk_bytes=10 * 1024 * 1024,
            ttl_seconds=3600,
            max_messages_per_chat=1000,
        )
        await cache.initialize()
        worker.message_cache = cache
        yield worker
        await cache.close()

    @pytest.mark.asyncio
    async def test_first_export_populates_cache(self, worker):
        job = ExportRequest(
            task_id="cache_test_1",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "supergroup"}, None)
        )

        messages = [
            ExportedMessage(id=3, date="2025-01-01T00:00:02", text="C"),
            ExportedMessage(id=2, date="2025-01-01T00:00:01", text="B"),
            ExportedMessage(id=1, date="2025-01-01T00:00:00", text="A"),
        ]

        async def mock_history(*args, **kwargs):
            for msg in messages:
                yield msg

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)

        await worker.process_job(job)

        # Cache should now have these messages
        cached = await worker.message_cache.get_cached_ranges(456)
        assert cached == [[1, 3]]

    @pytest.mark.asyncio
    async def test_second_export_uses_cache(self, worker):
        job = ExportRequest(
            task_id="cache_test_2",
            user_id=999,
            user_chat_id=999,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test", "type": "supergroup"}, None)
        )

        # Pre-populate cache with messages 1-3
        await worker.message_cache.store_messages(456, [
            ExportedMessage(id=1, date="2025-01-01T00:00:00", text="A"),
            ExportedMessage(id=2, date="2025-01-01T00:00:01", text="B"),
            ExportedMessage(id=3, date="2025-01-01T00:00:02", text="C"),
        ])

        # Telegram only returns NEW messages (id=4,5)
        new_msgs = [
            ExportedMessage(id=5, date="2025-01-01T00:00:04", text="E"),
            ExportedMessage(id=4, date="2025-01-01T00:00:03", text="D"),
        ]

        call_count = 0

        async def mock_history(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                # First call: fetch newer than cache max (min_id=3)
                for msg in new_msgs:
                    yield msg
            # No gap-filling calls expected (range [1-3] is continuous)

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)

        await worker.process_job(job)

        # Verify: Java received ALL 5 messages (3 cached + 2 new).
        # send_response вызывается с SendResponsePayload как позиционный аргумент
        payload = worker.java_client.send_response.call_args[0][0]
        assert payload.status == "completed"
        result_ids = sorted([m.id async for m in payload.messages])
        assert result_ids == [1, 2, 3, 4, 5]

class TestExportWorkerDateCache:

    @pytest.fixture
    async def worker(self, tmp_path):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        cache = MessageCache(
            db_path=str(tmp_path / "date_cache.db"),
            max_disk_bytes=10 * 1024 * 1024,
            ttl_seconds=3600,
            max_messages_per_chat=1000,
        )
        await cache.initialize()
        worker.message_cache = cache
        yield worker
        await cache.close()

    @pytest.mark.asyncio
    async def test_vasya_petya_kolya_date_export(self, worker):
        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"id": -1001001001, "title": "Test Chat", "type": "supergroup"}, None)
        )
        worker.java_client.send_response = AsyncMock(return_value=True)

        # --- Vasya: Jan 11-13 ---
        vasya_msgs = [
            ExportedMessage(id=13, date="2025-01-13T10:00:00", text="msg_13"),
            ExportedMessage(id=12, date="2025-01-12T10:00:00", text="msg_12"),
            ExportedMessage(id=11, date="2025-01-11T10:00:00", text="msg_11"),
        ]

        async def vasya_history(*args, **kwargs):
            for m in vasya_msgs:
                yield m

        worker.telegram_client.get_chat_history = vasya_history

        vasya_job = ExportRequest(
            task_id="vasya_1", user_id=100, user_chat_id=100, chat_id=-1001001001,
            from_date="2025-01-11T00:00:00", to_date="2025-01-13T23:59:59",
        )
        await worker.process_job(vasya_job)

        # Verify cache has Jan 11-13
        date_ranges = await worker.message_cache.get_cached_date_ranges(-1001001001)
        assert date_ranges == [["2025-01-11", "2025-01-13"]]

        # --- Petya: Jan 1-8 ---
        petya_msgs = [
            ExportedMessage(id=i, date=f"2025-01-{i:02d}T10:00:00", text=f"msg_{i}")
            for i in range(8, 0, -1)  # newest first
        ]

        async def petya_history(*args, **kwargs):
            for m in petya_msgs:
                yield m

        worker.telegram_client.get_chat_history = petya_history

        petya_job = ExportRequest(
            task_id="petya_1", user_id=200, user_chat_id=200, chat_id=-1001001001,
            from_date="2025-01-01T00:00:00", to_date="2025-01-08T23:59:59",
        )
        await worker.process_job(petya_job)

        # Verify cache has Jan 1-8 and Jan 11-13
        date_ranges = await worker.message_cache.get_cached_date_ranges(-1001001001)
        assert date_ranges == [["2025-01-01", "2025-01-08"], ["2025-01-11", "2025-01-13"]]

        # --- Kolya: Jan 1-15 ---
        # Should only fetch Jan 9-10 and Jan 14-15 from Telegram
        fetch_calls = []

        async def kolya_history(*args, **kwargs):
            from_date = kwargs.get("from_date")
            to_date = kwargs.get("to_date")
            fetch_calls.append((
                from_date.strftime("%Y-%m-%d") if from_date else None,
                to_date.strftime("%Y-%m-%d") if to_date else None,
            ))
            # Return messages for the requested gap
            if from_date and from_date.day == 9:
                # Gap Jan 9-10
                yield ExportedMessage(id=10, date="2025-01-10T10:00:00", text="msg_10")
                yield ExportedMessage(id=9, date="2025-01-09T10:00:00", text="msg_9")
            elif from_date and from_date.day == 14:
                # Gap Jan 14-15
                yield ExportedMessage(id=15, date="2025-01-15T10:00:00", text="msg_15")
                yield ExportedMessage(id=14, date="2025-01-14T10:00:00", text="msg_14")

        worker.telegram_client.get_chat_history = kolya_history

        kolya_job = ExportRequest(
            task_id="kolya_1", user_id=300, user_chat_id=300, chat_id=-1001001001,
            from_date="2025-01-01T00:00:00", to_date="2025-01-15T23:59:59",
        )
        await worker.process_job(kolya_job)

        # Verify: only 2 fetch calls (for the 2 gaps)
        assert len(fetch_calls) == 2
        assert ("2025-01-09", "2025-01-10") in fetch_calls
        assert ("2025-01-14", "2025-01-15") in fetch_calls

        # Verify: Java received all 15 messages.
        # send_response вызывается с SendResponsePayload как позиционный аргумент
        payload = worker.java_client.send_response.call_args[0][0]
        result_ids = sorted([m.id async for m in payload.messages])
        assert result_ids == list(range(1, 16))

        # Cache now has complete Jan 1-15
        date_ranges = await worker.message_cache.get_cached_date_ranges(-1001001001)
        assert date_ranges == [["2025-01-01", "2025-01-15"]]

class TestExportWorkerMemoryLogging:

    def test_log_memory_usage(self):
        worker = ExportWorker()
        # Should not raise even without psutil
        worker.log_memory_usage("TEST")

class TestCancelBeforeStart:

    @pytest.fixture
    def worker(self):
        w = ExportWorker()
        w.queue_consumer = AsyncMock()
        w.queue_consumer.mark_job_processing = AsyncMock()
        w.queue_consumer.mark_job_completed = AsyncMock()
        w.queue_consumer.mark_job_failed = AsyncMock()
        # MagicMock (не AsyncMock) — иначе доступ к атрибутам вроде
        # pipeline() создаёт unawaited coroutines и RuntimeWarning при cleanup.
        w.telegram_client = MagicMock()
        w.java_client = _make_mock_java_client()
        w.message_cache = MessageCache(enabled=False)
        w.control_redis = AsyncMock()
        # По умолчанию — не отменено
        w.control_redis.get = AsyncMock(return_value=None)
        w.control_redis.delete = AsyncMock()
        w.control_redis.set = AsyncMock()
        # pipeline() — sync в redis-py, должен возвращать pipe-объект, не coroutine.
        # Без этого control_redis.pipeline() вернёт AsyncMock-coroutine → RuntimeWarning.
        pipe = _make_pipeline_mock()
        w.control_redis.pipeline = MagicMock(return_value=pipe)
        return w

    @pytest.mark.asyncio
    async def test_job_cancelled_before_start_completes_without_export(self, worker):
        job = ExportRequest(
            task_id="cancel_early_task",
            user_id=1,
            user_chat_id=1,
            chat_id=123,
            limit=0,
            offset_id=0,
        )

        # Эмулируем: cancel_export:cancel_early_task установлен в Redis
        async def get_side_effect(key):
            if key == "cancel_export:cancel_early_task":
                return b"1"
            return None

        worker.control_redis.get = AsyncMock(side_effect=get_side_effect)

        result = await worker.process_job(job)

        assert result is True
        # verify_and_get_info НЕ должен вызываться — отменили до старта
        worker.telegram_client.verify_and_get_info.assert_not_called()
        # Задача должна быть помечена как completed (не failed)
        worker.queue_consumer.mark_job_completed.assert_called_once_with("cancel_early_task")
        worker.queue_consumer.mark_job_failed.assert_not_called()

    @pytest.mark.asyncio
    async def test_active_processing_job_set_at_start_and_cleared_at_end(self, worker):
        job = ExportRequest(
            task_id="active_job_test",
            user_id=2,
            user_chat_id=2,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Chat", "type": "supergroup", "id": 456}, None)
        )
        worker.telegram_client.get_messages_count = AsyncMock(return_value=1)

        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="msg")

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)

        await worker.process_job(job)

        # Проверяем что set был вызван с active_processing_job
        set_calls = [str(c) for c in worker.control_redis.set.call_args_list]
        assert any("active_processing_job" in c for c in set_calls), (
            f"active_processing_job не установлен. Вызовы set: {set_calls}"
        )

        # Проверяем что delete был вызван с active_processing_job
        delete_calls = [str(c) for c in worker.control_redis.delete.call_args_list]
        assert any("active_processing_job" in c for c in delete_calls), (
            f"active_processing_job не удалён. Вызовы delete: {delete_calls}"
        )

class TestActiveProcessingJobHelpers:

    @pytest.mark.asyncio
    async def test_set_active_processing_job(self):
        worker = ExportWorker()
        worker.control_redis = AsyncMock()
        worker.control_redis.set = AsyncMock()

        await worker.set_active_processing_job("export_abc123")

        worker.control_redis.set.assert_called_once_with(
            "active_processing_job", "export_abc123", ex=180
        )

    @pytest.mark.asyncio
    async def test_clear_active_processing_job(self):
        worker = ExportWorker()
        worker.control_redis = AsyncMock()
        worker.control_redis.delete = AsyncMock()

        await worker.clear_active_processing_job()

        worker.control_redis.delete.assert_called_once_with("active_processing_job")

    @pytest.mark.asyncio
    async def test_set_active_processing_job_no_redis(self):
        worker = ExportWorker()
        worker.control_redis = None

        # Не должно бросать исключение
        await worker.set_active_processing_job("export_xyz")

    @pytest.mark.asyncio
    async def test_clear_active_processing_job_no_redis(self):
        worker = ExportWorker()
        worker.control_redis = None

        # Не должно бросать исключение
        await worker.clear_active_processing_job()

class TestFloodWaitHeartbeat:

    @pytest.fixture
    def worker(self):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()
        worker.queue_consumer.mark_job_failed = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.message_cache = MessageCache(enabled=False)
        return worker

    @pytest.mark.asyncio
    async def test_on_floodwait_called_with_wait_time(self, worker):
        from java_client import ProgressTracker

        floodwait_calls = []

        async def mock_history(*args, **kwargs):
            on_floodwait = kwargs.get("on_floodwait")
            if on_floodwait:
                await on_floodwait(25)
                floodwait_calls.append(25)
            yield ExportedMessage(id=1, type="message", date="2026-04-10T10:00:00", text="test")

        worker.telegram_client.get_chat_history = mock_history
        worker.telegram_client.get_messages_count = AsyncMock(return_value=1)

        # Настраиваем tracker
        tracker = MagicMock(spec=ProgressTracker)
        tracker.start = AsyncMock()
        tracker.set_total = AsyncMock()
        tracker.seed = AsyncMock()
        tracker.track = AsyncMock()
        tracker.finalize = AsyncMock()
        tracker.on_floodwait = AsyncMock()

        worker._create_tracker = MagicMock(return_value=tracker)

        job = ExportRequest(
            task_id="test_flood_123",
            user_id=42,
            chat_id=-1001234567890,
            limit=0,
            offset_id=0,
            from_date="2026-04-10T00:00:00",
            to_date="2026-04-10T23:59:59",
        )

        await worker._export_with_date_cache(job)

        # on_floodwait был вызван (через mock_history) — проверяем что он был передан
        assert len(floodwait_calls) == 1
        assert floodwait_calls[0] == 25


class TestExportWorkerTopicSupport:
    """Тесты прокидывания topic_id в get_chat_history."""

    @pytest.fixture
    def worker(self):
        worker = ExportWorker()
        worker.queue_consumer = AsyncMock()
        worker.telegram_client = AsyncMock()
        worker.java_client = _make_mock_java_client()
        worker.telegram_client.get_messages_count = AsyncMock(return_value=100)
        worker.message_cache = MessageCache(enabled=False)
        return worker

    @pytest.mark.asyncio
    async def test_process_job_with_topic_passes_topic_id(self, worker):
        """Job с topic_id передаёт его в get_chat_history"""
        job = ExportRequest(
            task_id="test_topic_123",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            topic_id=148220,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test Chat", "type": "supergroup"}, None)
        )

        history_kwargs_list = []

        async def mock_history(*args, **kwargs):
            history_kwargs_list.append(kwargs)
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Topic msg")

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert len(history_kwargs_list) > 0
        assert history_kwargs_list[0].get("topic_id") == 148220

    @pytest.mark.asyncio
    async def test_process_job_without_topic_passes_none(self, worker):
        """Job без topic_id передаёт topic_id=None"""
        job = ExportRequest(
            task_id="test_no_topic_123",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test Chat", "type": "supergroup"}, None)
        )

        history_kwargs_list = []

        async def mock_history(*args, **kwargs):
            history_kwargs_list.append(kwargs)
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Normal msg")

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        result = await worker.process_job(job)

        assert result is True
        assert len(history_kwargs_list) > 0
        assert history_kwargs_list[0].get("topic_id") is None

    @pytest.mark.asyncio
    async def test_topic_export_uses_cache(self, worker):
        """Job с topic_id не обходит кэш — кэш-методы чтения вызываются."""
        worker.message_cache = MagicMock()
        worker.message_cache.enabled = True
        worker.message_cache.get_cached_date_ranges = AsyncMock(return_value=[])
        worker.message_cache.get_cached_ranges = AsyncMock(return_value=[])
        worker.message_cache.get_missing_date_ranges = AsyncMock(return_value=[])
        worker.message_cache.get_missing_ranges = AsyncMock(return_value=[])
        worker.message_cache.store_messages = AsyncMock()
        worker.message_cache.mark_date_range_checked = AsyncMock()
        worker.message_cache.count_messages = AsyncMock(return_value=0)
        worker.message_cache.count_messages_by_date = AsyncMock(return_value=0)
        async def _empty_async_iter(*a, **kw):
            if False:
                yield
        worker.message_cache.iter_messages = _empty_async_iter
        worker.message_cache.iter_messages_by_date = _empty_async_iter

        job = ExportRequest(
            task_id="test_topic_cache",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            topic_id=148220,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test Chat", "type": "supergroup", "id": 456}, None)
        )
        worker.telegram_client.get_topic_name = AsyncMock(return_value="Тестовый топик")
        worker.telegram_client.get_messages_count = AsyncMock(return_value=50)

        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="Topic msg")

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        await worker.process_job(job)

        # Кэш НЕ обходится для топиков — методы чтения/записи должны вызываться
        cache_read_called = (
            worker.message_cache.get_cached_date_ranges.called
            or worker.message_cache.get_cached_ranges.called
        )
        assert cache_read_called, "Кэш обходится для топиков — должен использоваться"

    @pytest.mark.asyncio
    async def test_topic_export_passes_topic_id_to_get_messages_count(self, worker):
        """Job с topic_id передаёт его в get_messages_count."""
        job = ExportRequest(
            task_id="test_topic_count",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            topic_id=148220,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test Chat", "type": "supergroup", "id": 456}, None)
        )
        worker.telegram_client.get_topic_name = AsyncMock(return_value="Тест")
        worker.telegram_client.get_messages_count = AsyncMock(return_value=50)

        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="msg")

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        await worker.process_job(job)

        # Проверяем что topic_id передан в get_messages_count
        count_call = worker.telegram_client.get_messages_count.call_args
        assert count_call.kwargs.get("topic_id") == 148220

    @pytest.mark.asyncio
    async def test_topic_export_shows_topic_name_in_progress(self, worker):
        """Job с topic_id отображает название топика в прогрессе."""
        job = ExportRequest(
            task_id="test_topic_name",
            user_id=123,
            user_chat_id=123,
            chat_id=456,
            topic_id=148220,
            limit=0,
            offset_id=0,
        )

        worker.telegram_client.verify_and_get_info = AsyncMock(
            return_value=(True, {"title": "Test Chat", "type": "supergroup", "id": 456}, None)
        )
        worker.telegram_client.get_topic_name = AsyncMock(return_value="Обход блокировок")
        worker.telegram_client.get_messages_count = AsyncMock(return_value=50)

        async def mock_history(*args, **kwargs):
            yield ExportedMessage(id=1, date="2025-01-01T00:00:00", text="msg")

        worker.telegram_client.get_chat_history = mock_history
        worker.java_client.send_response = AsyncMock(return_value=True)
        worker.queue_consumer.mark_job_processing = AsyncMock()
        worker.queue_consumer.mark_job_completed = AsyncMock()

        await worker.process_job(job)

        # Проверяем что topic_name передан через send_progress_update
        progress_calls = worker.java_client.send_progress_update.call_args_list
        assert any(
            call.kwargs.get("topic_name") == "Обход блокировок"
            for call in progress_calls
        )


# ======================================================================
# Heartbeat observability (long-running jobs, 300k+ сообщений / 1ч+)
# ======================================================================
# Порог liveness — 60 сек, TTL ключа — 120 сек (двойной запас на один
# пропущенный heartbeat). Telegram API сам держит таймауты ~22 сек
# (FloodWait, net read), так что ставить порог ниже минуты нельзя —
# получим false-positive. Kill не делаем, только observability.

@pytest.mark.asyncio
class TestHeartbeat:

    async def test_heartbeat_writes_key_with_ttl(self):
        worker = ExportWorker()
        worker.control_redis = AsyncMock()

        await worker.heartbeat("task_abc", stage="fetch")

        # heartbeat() вызывает 3 set'а: основной heartbeat, extend active_processing_job,
        # extend job:processing:{task_id} (для long-running jobs > JOB_TIMEOUT)
        assert worker.control_redis.set.call_count == 3
        calls = worker.control_redis.set.call_args_list
        keys = [c.args[0] for c in calls]
        assert "worker:heartbeat:task_abc" in keys
        assert "active_processing_job" in keys
        assert "job:processing:task_abc" in keys

        heartbeat_call = next(c for c in calls if c.args[0] == "worker:heartbeat:task_abc")
        import json as _json
        payload = _json.loads(heartbeat_call.args[1])
        assert payload["stage"] == "fetch"
        assert isinstance(payload["ts"], int)
        assert heartbeat_call.kwargs.get("ex") == 120

        processing_call = next(c for c in calls if c.args[0] == "active_processing_job")
        assert processing_call.args[1] == "task_abc"
        assert processing_call.kwargs.get("ex") == 180

        # job:processing:{task_id} TTL = JOB_TIMEOUT (1800s) — long-running protection
        from config import settings as _settings
        job_proc_call = next(c for c in calls if c.args[0] == "job:processing:task_abc")
        assert job_proc_call.kwargs.get("ex") == _settings.JOB_TIMEOUT

    async def test_heartbeat_without_redis_is_noop(self):
        worker = ExportWorker()
        worker.control_redis = None
        # Не должен падать при отсутствии Redis
        await worker.heartbeat("task_abc")

    async def test_heartbeat_swallows_redis_errors(self):
        """Redis down не должен ронять экспорт — heartbeat best-effort."""
        worker = ExportWorker()
        worker.control_redis = AsyncMock()
        worker.control_redis.set = AsyncMock(side_effect=ConnectionError("Redis down"))

        # Must not raise
        await worker.heartbeat("task_abc")

    async def test_clear_heartbeat_deletes_key(self):
        worker = ExportWorker()
        worker.control_redis = AsyncMock()

        await worker.clear_heartbeat("task_abc")

        worker.control_redis.delete.assert_called_once_with(
            "worker:heartbeat:task_abc"
        )

    async def test_cancel_checker_emits_heartbeat_as_side_effect(self):
        """Главный инвариант: cancel-poll и heartbeat ходят парой.
        Pyrogram-слой зовёт is_cancelled_fn каждые 200 сообщений и каждую
        секунду в FloodWait → автоматически получаем heartbeat на той же
        частоте без отдельного таймера."""
        worker = ExportWorker()
        worker.control_redis = AsyncMock()
        worker.control_redis.get = AsyncMock(return_value=None)  # не отменено

        check = worker._make_cancel_checker("task_abc")
        result = await check()

        assert result is False
        # heartbeat SET вызван
        set_calls = worker.control_redis.set.call_args_list
        assert any(
            call_args[0][0] == "worker:heartbeat:task_abc"
            for call_args in set_calls
        ), "cancel-poll должен эмитить heartbeat как side-effect"
        # cancel-get тоже вызван
        worker.control_redis.get.assert_called_once_with("cancel_export:task_abc")

    async def test_cancel_checker_heartbeat_failure_does_not_break_cancel(self):
        """Если heartbeat сломался — cancel всё равно должен работать.
        Это критично: отмена важнее мониторинга."""
        worker = ExportWorker()
        worker.control_redis = AsyncMock()
        worker.control_redis.set = AsyncMock(side_effect=ConnectionError("down"))
        worker.control_redis.get = AsyncMock(return_value="1")  # отменено

        check = worker._make_cancel_checker("task_abc")
        result = await check()

        assert result is True, "cancel должен работать даже если heartbeat упал"

    async def test_cleanup_job_clears_heartbeat(self):
        """После завершения job — убираем heartbeat, чтобы observer видел
        'job закончился', а не 'живёт ещё 2 минуты по TTL'."""
        worker = ExportWorker()
        worker.control_redis = AsyncMock()
        worker.control_redis.get = AsyncMock(return_value=None)

        job = ExportRequest(
            task_id="task_abc", user_id=1, chat_id=-100,
            limit=0, offset_id=0,
            from_date="2025-01-01T00:00:00", to_date="2025-12-31T23:59:59",
        )
        await worker._cleanup_job(job)

        delete_calls = [c.args[0] for c in worker.control_redis.delete.call_args_list]
        assert "worker:heartbeat:task_abc" in delete_calls
