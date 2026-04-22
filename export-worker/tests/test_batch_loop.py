import pytest
from unittest.mock import AsyncMock, MagicMock
from main import ExportWorker
from models import ExportRequest, ExportedMessage
from pyrogram_client import ExportCancelled


def make_job(chat_id=12345, topic_id=None, user_id=999, task_id="t1"):
    return ExportRequest(
        task_id=task_id,
        user_id=user_id,
        chat_id=chat_id,
        topic_id=topic_id,
    )


def make_msg(msg_id):
    return ExportedMessage(id=msg_id, type="message", date="2025-01-01T00:00:00", text=f"m{msg_id}")


async def aiter(items):
    for it in items:
        yield it


@pytest.mark.asyncio
async def test_run_batch_loop_no_messages_returns_initial_count():
    worker = ExportWorker()
    worker._CACHE_BATCH_SIZE = 3
    worker.message_cache = AsyncMock()
    worker._flush_batch_and_check_cancel = AsyncMock(return_value=False)
    worker._flush_partial_batch = AsyncMock()

    result = await worker._run_batch_loop(make_job(), aiter([]), tracker=None, initial_count=0)

    assert result == 0
    worker.message_cache.store_messages.assert_not_called()
    worker._flush_batch_and_check_cancel.assert_not_called()


@pytest.mark.asyncio
async def test_run_batch_loop_small_batch_flushed_at_end():
    worker = ExportWorker()
    worker._CACHE_BATCH_SIZE = 100
    worker.message_cache = AsyncMock()
    worker._flush_batch_and_check_cancel = AsyncMock(return_value=False)
    worker._flush_partial_batch = AsyncMock()

    msgs = [make_msg(i) for i in range(5)]
    result = await worker._run_batch_loop(make_job(), aiter(msgs), tracker=None)

    assert result == 5
    # batch < _CACHE_BATCH_SIZE → flush_batch_and_check_cancel не вызывался
    worker._flush_batch_and_check_cancel.assert_not_called()
    # в конце store_messages вызвался один раз с этими 5 сообщениями
    worker.message_cache.store_messages.assert_awaited_once()
    args = worker.message_cache.store_messages.call_args
    assert args[0][0] == 12345  # chat_id
    assert len(args[0][1]) == 5


@pytest.mark.asyncio
async def test_run_batch_loop_full_batch_triggers_intermediate_flush():
    worker = ExportWorker()
    worker._CACHE_BATCH_SIZE = 3
    worker.message_cache = AsyncMock()
    worker._flush_batch_and_check_cancel = AsyncMock(return_value=False)
    worker._flush_partial_batch = AsyncMock()

    msgs = [make_msg(i) for i in range(7)]  # 3 + 3 + 1
    result = await worker._run_batch_loop(make_job(), aiter(msgs), tracker=None)

    assert result == 7
    # 7 messages → 2 промежуточных flush на 3-м и 6-м, потом final store_messages для оставшегося 1
    assert worker._flush_batch_and_check_cancel.await_count == 2


@pytest.mark.asyncio
async def test_run_batch_loop_cancel_during_flush_returns_none():
    worker = ExportWorker()
    worker._CACHE_BATCH_SIZE = 2
    worker.message_cache = AsyncMock()
    # первый flush возвращает True (cancel detected)
    worker._flush_batch_and_check_cancel = AsyncMock(return_value=True)
    worker._flush_partial_batch = AsyncMock()

    msgs = [make_msg(i) for i in range(5)]
    result = await worker._run_batch_loop(make_job(), aiter(msgs), tracker=None)

    assert result is None
    worker._flush_batch_and_check_cancel.assert_awaited_once()


@pytest.mark.asyncio
async def test_run_batch_loop_tracker_called_per_message():
    worker = ExportWorker()
    worker._CACHE_BATCH_SIZE = 100
    worker.message_cache = AsyncMock()
    worker._flush_batch_and_check_cancel = AsyncMock(return_value=False)
    worker._flush_partial_batch = AsyncMock()

    tracker = AsyncMock()
    msgs = [make_msg(i) for i in range(3)]
    result = await worker._run_batch_loop(make_job(), aiter(msgs), tracker=tracker, initial_count=10)

    assert result == 13  # 10 + 3
    assert tracker.track.await_count == 3
    # последний вызов с финальным счётчиком 13
    tracker.track.assert_awaited_with(13)


@pytest.mark.asyncio
async def test_run_batch_loop_on_each_msg_callback_invoked():
    worker = ExportWorker()
    worker._CACHE_BATCH_SIZE = 100
    worker.message_cache = AsyncMock()
    worker._flush_batch_and_check_cancel = AsyncMock(return_value=False)
    worker._flush_partial_batch = AsyncMock()

    seen_ids = []
    msgs = [make_msg(i) for i in range(4)]
    await worker._run_batch_loop(
        make_job(), aiter(msgs), tracker=None,
        on_each_msg=lambda m: seen_ids.append(m.id),
    )

    assert seen_ids == [0, 1, 2, 3]


@pytest.mark.asyncio
async def test_run_batch_loop_export_cancelled_flushes_partial_and_raises():
    worker = ExportWorker()
    worker._CACHE_BATCH_SIZE = 100
    worker.message_cache = AsyncMock()
    worker._flush_batch_and_check_cancel = AsyncMock(return_value=False)
    worker._flush_partial_batch = AsyncMock()

    async def cancelled_iter():
        yield make_msg(1)
        yield make_msg(2)
        raise ExportCancelled("user cancelled")

    with pytest.raises(ExportCancelled):
        await worker._run_batch_loop(make_job(), cancelled_iter(), tracker=None)

    # partial batch (2 сообщения) сохранён через _flush_partial_batch
    worker._flush_partial_batch.assert_awaited_once()
    # store_messages не вызывался — exit через ExportCancelled
    worker.message_cache.store_messages.assert_not_called()
