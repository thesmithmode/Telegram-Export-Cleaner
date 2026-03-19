"""
Performance and concurrency tests for Export Worker.

Tests performance characteristics and concurrent behavior.
"""

import pytest
import asyncio
import time
from datetime import datetime

from models import ExportedMessage, ExportRequest, ExportResponse
from json_converter import MessageConverter
from unittest.mock import Mock


class TestMessageConversionPerformance:
    """Performance tests for message conversion."""

    def test_single_message_conversion_speed(self):
        """Test conversion speed for single message."""
        # Create mock message
        message = Mock()
        message.id = 1
        message.date = datetime.now()
        message.text = "Test message"
        message.from_user = Mock()
        message.from_user.first_name = "John"
        message.from_user.last_name = "Doe"
        message.from_user.id = 123
        message.entities = None
        message.media = None
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        # Measure conversion time
        start = time.time()
        result = MessageConverter.convert_message(message)
        elapsed = time.time() - start

        # Should be very fast (< 1ms)
        assert elapsed < 0.001
        assert isinstance(result, ExportedMessage)

    def test_batch_message_conversion_speed(self):
        """Test conversion speed for batch of messages."""
        messages = []
        for i in range(100):
            msg = Mock()
            msg.id = i
            msg.date = datetime.now()
            msg.text = f"Message {i}"
            msg.from_user = Mock()
            msg.from_user.first_name = "John"
            msg.from_user.last_name = "Doe"
            msg.from_user.id = 123
            msg.entities = None
            msg.media = None
            msg.forward_from = None
            msg.forward_sender_name = None
            msg.forward_date = None
            msg.edit_date = None
            msg.reply_to_message_id = None
            messages.append(msg)

        # Measure batch conversion time
        start = time.time()
        results = MessageConverter.convert_messages(messages)
        elapsed = time.time() - start

        assert len(results) == 100
        # Should convert 100 messages in < 100ms (1ms per message)
        assert elapsed < 0.1
        per_message = elapsed / len(results)
        assert per_message < 0.001

    def test_large_batch_conversion(self):
        """Test conversion of large batch (1000 messages)."""
        messages = []
        for i in range(1000):
            msg = Mock()
            msg.id = i
            msg.date = datetime.now()
            msg.text = f"Message {i}"
            msg.from_user = Mock()
            msg.from_user.first_name = "John"
            msg.from_user.id = 123
            msg.from_user.last_name = None
            msg.entities = None
            msg.media = None
            msg.forward_from = None
            msg.forward_sender_name = None
            msg.forward_date = None
            msg.edit_date = None
            msg.reply_to_message_id = None
            messages.append(msg)

        start = time.time()
        results = MessageConverter.convert_messages(messages)
        elapsed = time.time() - start

        assert len(results) == 1000
        # 1000 messages should take < 1 second
        assert elapsed < 1.0


class TestDataModelPerformance:
    """Performance tests for data models."""

    def test_export_request_serialization(self):
        """Test ExportRequest serialization speed."""
        request = ExportRequest(
            task_id="test_123",
            user_id=456789,
            chat_id=-1001234567890,
            limit=1000
        )

        start = time.time()
        json_data = request.model_dump()
        elapsed = time.time() - start

        # Should be very fast
        assert elapsed < 0.001
        assert json_data['task_id'] == "test_123"

    def test_large_response_serialization(self):
        """Test ExportResponse serialization with many messages."""
        messages = [
            ExportedMessage(
                id=i,
                type="message",
                date="2025-06-24T15:29:46",
                text=f"Message {i}"
            )
            for i in range(1000)
        ]

        response = ExportResponse(
            task_id="test",
            status="completed",
            message_count=len(messages),
            messages=messages
        )

        start = time.time()
        response_json = response.model_dump(exclude_none=True)
        elapsed = time.time() - start

        # Serialization should be fast even for large response
        assert elapsed < 0.1
        assert len(response_json['messages']) == 1000


@pytest.mark.asyncio
class TestConcurrency:
    """Concurrency tests."""

    async def test_multiple_concurrent_operations(self):
        """Test multiple concurrent message conversions."""
        # Create mock messages
        messages = []
        for i in range(10):
            msg = Mock()
            msg.id = i
            msg.date = datetime.now()
            msg.text = f"Message {i}"
            msg.from_user = Mock()
            msg.from_user.first_name = "John"
            msg.from_user.last_name = "Doe"
            msg.from_user.id = 123
            msg.entities = None
            msg.media = None
            msg.forward_from = None
            msg.forward_sender_name = None
            msg.forward_date = None
            msg.edit_date = None
            msg.reply_to_message_id = None
            messages.append(msg)

        # Convert all concurrently
        async def convert_message(msg):
            loop = asyncio.get_event_loop()
            return await loop.run_in_executor(None, MessageConverter.convert_message, msg)

        start = time.time()
        results = await asyncio.gather(*[convert_message(msg) for msg in messages])
        elapsed = time.time() - start

        assert len(results) == 10
        # Concurrent operations should be faster than sequential
        assert elapsed < 0.1

    async def test_concurrent_response_building(self):
        """Test building multiple responses concurrently."""
        async def build_response(task_id, message_count):
            messages = [
                ExportedMessage(
                    id=i,
                    type="message",
                    date="2025-06-24T15:29:46",
                    text=f"Task {task_id} Message {i}"
                )
                for i in range(message_count)
            ]

            return ExportResponse(
                task_id=task_id,
                status="completed",
                message_count=len(messages),
                messages=messages
            )

        start = time.time()
        responses = await asyncio.gather(*[
            build_response(f"task_{i}", 100)
            for i in range(10)
        ])
        elapsed = time.time() - start

        assert len(responses) == 10
        for response in responses:
            assert response.message_count == 100

    async def test_concurrent_json_serialization(self):
        """Test concurrent JSON serialization."""
        async def serialize_response(task_id):
            messages = [
                ExportedMessage(
                    id=i,
                    type="message",
                    date="2025-06-24T15:29:46",
                    text=f"Message {i}"
                )
                for i in range(100)
            ]

            response = ExportResponse(
                task_id=task_id,
                status="completed",
                message_count=len(messages),
                messages=messages
            )

            return response.model_dump(exclude_none=True)

        start = time.time()
        results = await asyncio.gather(*[
            serialize_response(f"task_{i}")
            for i in range(10)
        ])
        elapsed = time.time() - start

        assert len(results) == 10
        # All serializations should complete quickly
        assert elapsed < 0.5


class TestMemoryUsage:
    """Memory usage characteristics."""

    def test_large_message_list_memory(self):
        """Test memory usage with large message list."""
        import sys
        import tracemalloc

        # Start memory tracking
        tracemalloc.start()

        messages = [
            ExportedMessage(
                id=i,
                type="message",
                date="2025-06-24T15:29:46",
                text=f"Message {i}" * 10  # Longer text
            )
            for i in range(1000)
        ]

        # Get current memory usage
        current, peak = tracemalloc.get_traced_memory()
        tracemalloc.stop()

        # Verify memory is used
        assert current > 0

        # Calculate actual size of all messages
        total_size = sum(sys.getsizeof(msg) for msg in messages)
        avg_per_message = total_size / len(messages)

        # Each message should be relatively small
        # (exact size depends on text content, but should be < 10KB)
        assert avg_per_message < 10000  # < 10KB per message
        assert total_size < 10_000_000  # < 10MB for 1000 messages


class TestExportRateBenchmark:
    """Benchmark export rates."""

    def test_message_export_rate(self):
        """Test export rate: messages per second."""
        # Simulate exporting 100 messages
        messages = []
        for i in range(100):
            msg = Mock()
            msg.id = i
            msg.date = datetime.now()
            msg.text = f"Message {i}"
            msg.from_user = Mock()
            msg.from_user.first_name = "John"
            msg.from_user.last_name = "Doe"
            msg.from_user.id = 123
            msg.entities = None
            msg.media = None
            msg.forward_from = None
            msg.forward_sender_name = None
            msg.forward_date = None
            msg.edit_date = None
            msg.reply_to_message_id = None
            messages.append(msg)

        # Convert all messages
        start = time.time()
        results = MessageConverter.convert_messages(messages)
        elapsed = time.time() - start

        rate = len(results) / elapsed if elapsed > 0 else float('inf')

        # Should export at > 1000 messages/sec
        assert rate > 1000

    def test_response_building_rate(self):
        """Test response building rate."""
        start = time.time()
        response = ExportResponse(
            task_id="test",
            status="completed",
            message_count=10000,
            messages=[
                ExportedMessage(
                    id=i,
                    type="message",
                    date="2025-06-24T15:29:46",
                    text=f"Message {i}"
                )
                for i in range(10000)
            ]
        )
        elapsed = time.time() - start

        # Building response with 10K messages should be fast
        assert elapsed < 1.0
