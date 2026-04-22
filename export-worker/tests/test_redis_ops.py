import logging
import pytest
from unittest.mock import AsyncMock, MagicMock

from redis_ops import RedisOps


@pytest.mark.asyncio
async def test_safe_set_calls_client_set_with_ex():
    client = AsyncMock()
    logger = MagicMock(spec=logging.Logger)
    ops = RedisOps(client, logger)

    await ops.safe_set("key1", "val1", ex=300)

    client.set.assert_awaited_once_with("key1", "val1", ex=300)
    logger.warning.assert_not_called()


@pytest.mark.asyncio
async def test_safe_set_calls_client_set_without_ex():
    client = AsyncMock()
    logger = MagicMock(spec=logging.Logger)
    ops = RedisOps(client, logger)

    await ops.safe_set("k", "v")

    client.set.assert_awaited_once_with("k", "v", ex=None)


@pytest.mark.asyncio
async def test_safe_set_noop_when_client_is_none():
    logger = MagicMock(spec=logging.Logger)
    ops = RedisOps(None, logger)

    # should not raise
    await ops.safe_set("k", "v", ex=10)

    logger.warning.assert_not_called()
    logger.debug.assert_not_called()


@pytest.mark.asyncio
async def test_safe_set_swallows_exception_with_warning():
    client = AsyncMock()
    client.set.side_effect = RuntimeError("redis down")
    logger = MagicMock(spec=logging.Logger)
    ops = RedisOps(client, logger)

    await ops.safe_set("k", "v")

    logger.warning.assert_called_once()
    assert "k" in logger.warning.call_args[0][0]
    assert "redis down" in logger.warning.call_args[0][0]


@pytest.mark.asyncio
async def test_safe_set_swallows_exception_with_debug_level():
    client = AsyncMock()
    client.set.side_effect = RuntimeError("oops")
    logger = MagicMock(spec=logging.Logger)
    ops = RedisOps(client, logger)

    await ops.safe_set("k", "v", on_error_level="debug")

    logger.debug.assert_called_once()
    logger.warning.assert_not_called()


@pytest.mark.asyncio
async def test_safe_delete_calls_client_delete():
    client = AsyncMock()
    logger = MagicMock(spec=logging.Logger)
    ops = RedisOps(client, logger)

    await ops.safe_delete("key_to_delete")

    client.delete.assert_awaited_once_with("key_to_delete")
    logger.warning.assert_not_called()


@pytest.mark.asyncio
async def test_safe_delete_noop_when_client_is_none():
    logger = MagicMock(spec=logging.Logger)
    ops = RedisOps(None, logger)

    await ops.safe_delete("k")

    logger.warning.assert_not_called()


@pytest.mark.asyncio
async def test_safe_delete_swallows_exception_warning():
    client = AsyncMock()
    client.delete.side_effect = ConnectionError("network fail")
    logger = MagicMock(spec=logging.Logger)
    ops = RedisOps(client, logger)

    await ops.safe_delete("missing")

    logger.warning.assert_called_once()
    assert "missing" in logger.warning.call_args[0][0]


@pytest.mark.asyncio
async def test_safe_delete_swallows_exception_debug_level():
    client = AsyncMock()
    client.delete.side_effect = RuntimeError("x")
    logger = MagicMock(spec=logging.Logger)
    ops = RedisOps(client, logger)

    await ops.safe_delete("k", on_error_level="debug")

    logger.debug.assert_called_once()
    logger.warning.assert_not_called()
