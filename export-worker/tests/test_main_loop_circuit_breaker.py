"""TD-002: проверяем circuit breaker в ExportWorker.run().

При непрерывных ошибках в main loop процесс должен сделать sys.exit(1)
после порога — supervisor (Docker restart policy) перезапустит контейнер.
До фикса worker лупился `sleep(5) → continue` бесконечно с живым heartbeat.
"""

import asyncio
import os
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

import main as main_module
from main import ExportWorker


@pytest.fixture(autouse=True)
def _restore_env():
    saved = dict(os.environ)
    yield
    os.environ.clear()
    os.environ.update(saved)


def _build_worker_with_failing_queue(error_count: int) -> ExportWorker:
    """ExportWorker, у которого initialize() = True и queue_consumer.get_job
    кидает RuntimeError бесконечно. Управление через mock'и, без реального Redis.
    """
    worker = ExportWorker.__new__(ExportWorker)
    worker.running = False
    worker.worker_id = "test-worker"
    worker.queue_consumer = MagicMock()
    worker.queue_consumer.get_job = AsyncMock(side_effect=RuntimeError("simulated upstream failure"))
    worker.message_cache = None
    worker.control_redis = None
    worker._cache_stats_task = None
    worker.cleanup = AsyncMock()
    worker.initialize = AsyncMock(return_value=True)
    worker._update_all_queue_positions = AsyncMock()
    worker.process_job = AsyncMock()
    return worker


@pytest.mark.asyncio
async def test_main_loop_exits_after_consecutive_errors_threshold(monkeypatch):
    """После порога consecutive_errors ExportWorker.run() вызывает sys.exit(1)."""
    threshold = 3
    monkeypatch.setattr(main_module.settings, "MAIN_LOOP_MAX_CONSECUTIVE_ERRORS", threshold, raising=False)

    sleep_calls = []

    async def fake_sleep(seconds):
        sleep_calls.append(seconds)
        # Прерываем после порога чтобы тест не висел: после threshold-той ошибки
        # код делает sys.exit(1) до sleep, поэтому если sleep вызывается threshold раз —
        # значит exit не сработал.
        return None

    with patch("asyncio.sleep", new=fake_sleep):
        worker = _build_worker_with_failing_queue(error_count=threshold + 5)
        with pytest.raises(SystemExit) as exc_info:
            await worker.run()

    assert exc_info.value.code == 1
    # sleep должен быть вызван (threshold - 1) раз — на последней ошибке делаем exit ДО sleep
    assert len(sleep_calls) == threshold - 1, (
        f"Ожидали {threshold - 1} sleep'ов до exit, получили {len(sleep_calls)}: {sleep_calls}"
    )
    worker.cleanup.assert_awaited()


@pytest.mark.asyncio
async def test_main_loop_resets_counter_on_success(monkeypatch):
    """Удачный job сбрасывает счётчик ошибок — worker не выходит после серии 'ошибка/успех/ошибка'."""
    threshold = 3
    monkeypatch.setattr(main_module.settings, "MAIN_LOOP_MAX_CONSECUTIVE_ERRORS", threshold, raising=False)

    # Сценарий: 2 ошибки → 1 успех (job=None, sleep) → 2 ошибки → cancel.
    # Без сброса счётчика на 4-й ошибке вышли бы. Со сбросом — продолжаем.
    call_count = {"n": 0}

    async def get_job_sequence():
        call_count["n"] += 1
        n = call_count["n"]
        if n in (1, 2, 4, 5):
            raise RuntimeError(f"fail #{n}")
        if n == 3:
            return None  # job=None → ветка успеха (consecutive_errors = 0)
        # На 6-м вызове отменяемся чтобы тест завершился
        raise asyncio.CancelledError()

    sleep_calls = []

    async def fake_sleep(seconds):
        sleep_calls.append(seconds)

    worker = _build_worker_with_failing_queue(error_count=0)
    worker.queue_consumer.get_job = AsyncMock(side_effect=get_job_sequence)

    with patch("asyncio.sleep", new=fake_sleep):
        # CancelledError в loop ловится и break — без SystemExit
        await worker.run()

    # 4 ошибки всего, но между 2-й и 3-й был сброс. Threshold=3 не превышен.
    # sleep вызывался: после err1, err2, после job=None (ветка else, sleep(1)), err4, err5.
    assert call_count["n"] == 6
    worker.cleanup.assert_awaited()


@pytest.mark.asyncio
async def test_main_loop_uses_exponential_backoff(monkeypatch):
    """Backoff между ошибками растёт экспоненциально, capped 60s."""
    threshold = 100  # высокий чтобы не выйти
    monkeypatch.setattr(main_module.settings, "MAIN_LOOP_MAX_CONSECUTIVE_ERRORS", threshold, raising=False)

    sleep_calls = []
    cancel_after = 5

    async def fake_sleep(seconds):
        sleep_calls.append(seconds)
        if len(sleep_calls) >= cancel_after:
            raise asyncio.CancelledError()

    worker = _build_worker_with_failing_queue(error_count=0)

    with patch("asyncio.sleep", new=fake_sleep):
        await worker.run()

    # 2^1=2, 2^2=4, 2^3=8, 2^4=16, 2^5=32 — растёт
    assert sleep_calls == [2.0, 4.0, 8.0, 16.0, 32.0]
