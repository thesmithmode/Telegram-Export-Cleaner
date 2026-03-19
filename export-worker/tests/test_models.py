"""
Unit tests for models.py

Tests:
- Pydantic model validation
- JSON serialization
- Field validation
"""

import pytest
from pydantic import ValidationError

from models import (
    ExportRequest,
    ExportedMessage,
    ExportResponse,
    MessageEntity
)


class TestExportRequest:
    """Tests for ExportRequest model"""

    def test_valid_request(self):
        """Should create valid ExportRequest"""
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            limit=1000
        )

        assert req.task_id == "export_12345"
        assert req.user_id == 123456789
        assert req.chat_id == -1001234567890
        assert req.limit == 1000

    def test_request_with_dates(self):
        """Should accept ISO date filters"""
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            from_date="2025-01-01T00:00:00",
            to_date="2025-12-31T23:59:59"
        )

        assert req.from_date == "2025-01-01T00:00:00"
        assert req.to_date == "2025-12-31T23:59:59"

    def test_request_missing_required_field(self):
        """Should fail without required fields"""
        with pytest.raises(ValidationError):
            ExportRequest(
                user_id=123456789,
                chat_id=-1001234567890
                # Missing task_id
            )

    def test_request_default_values(self):
        """Should have sensible defaults"""
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890
        )

        assert req.limit == 0  # 0 = unlimited
        assert req.offset_id == 0
        assert req.from_date is None
        assert req.to_date is None

    def test_request_json_serialization(self):
        """Should serialize to JSON"""
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            limit=1000
        )

        json_data = req.model_dump()

        assert json_data["task_id"] == "export_12345"
        assert json_data["limit"] == 1000


class TestMessageEntity:
    """Tests for MessageEntity model"""

    def test_simple_entity(self):
        """Should create simple entity"""
        entity = MessageEntity(
            type="bold",
            offset=0,
            length=4
        )

        assert entity.type == "bold"
        assert entity.offset == 0
        assert entity.length == 4
        assert entity.url is None
        assert entity.user_id is None

    def test_text_url_entity(self):
        """Should handle text_url with URL"""
        entity = MessageEntity(
            type="text_url",
            offset=0,
            length=4,
            url="https://example.com"
        )

        assert entity.type == "text_url"
        assert entity.url == "https://example.com"

    def test_text_mention_entity(self):
        """Should handle text_mention with user_id"""
        entity = MessageEntity(
            type="text_mention",
            offset=0,
            length=4,
            user_id=123456789
        )

        assert entity.type == "text_mention"
        assert entity.user_id == 123456789

    def test_entity_missing_required(self):
        """Should fail without required fields"""
        with pytest.raises(ValidationError):
            MessageEntity(
                type="bold"
                # Missing offset and length
            )


class TestExportedMessage:
    """Tests for ExportedMessage model"""

    def test_minimal_message(self):
        """Should create message with minimal fields"""
        msg = ExportedMessage(
            id=123,
            type="message",
            date="2025-06-24T15:29:46"
        )

        assert msg.id == 123
        assert msg.type == "message"
        assert msg.date == "2025-06-24T15:29:46"
        assert msg.text == ""

    def test_full_message(self):
        """Should create message with all fields"""
        msg = ExportedMessage(
            id=123,
            type="message",
            date="2025-06-24T15:29:46",
            text="Hello world",
            from_user="John Doe",
            from_id={"peer_type": "user", "peer_id": 456},
            text_entities=[
                MessageEntity(type="bold", offset=0, length=5)
            ],
            media_type="photo",
            photo="photo_123.jpg",
            width=1024,
            height=768,
            edited=False,
            forward_from="Alice",
            reply_to_message_id=122
        )

        assert msg.id == 123
        assert msg.text == "Hello world"
        assert msg.media_type == "photo"
        assert msg.width == 1024

    def test_message_with_entities(self):
        """Should handle text entities"""
        msg = ExportedMessage(
            id=123,
            type="message",
            date="2025-06-24T15:29:46",
            text="Bold italic",
            text_entities=[
                MessageEntity(type="bold", offset=0, length=4),
                MessageEntity(type="italic", offset=5, length=6)
            ]
        )

        assert len(msg.text_entities) == 2
        assert msg.text_entities[0].type == "bold"
        assert msg.text_entities[1].type == "italic"

    def test_message_json_serialization(self):
        """Should serialize to JSON"""
        msg = ExportedMessage(
            id=123,
            type="message",
            date="2025-06-24T15:29:46",
            text="Hello"
        )

        json_data = msg.model_dump(exclude_none=True)

        assert json_data["id"] == 123
        assert json_data["text"] == "Hello"
        assert "from_user" not in json_data  # None values excluded

    def test_message_type_validation(self):
        """Should validate message type"""
        msg = ExportedMessage(
            id=123,
            type="message",  # Should be "message"
            date="2025-06-24T15:29:46"
        )

        assert msg.type == "message"


class TestExportResponse:
    """Tests for ExportResponse model"""

    def test_success_response(self):
        """Should create successful response"""
        response = ExportResponse(
            task_id="export_12345",
            status="completed",
            message_count=100,
            messages=[
                ExportedMessage(
                    id=1,
                    type="message",
                    date="2025-06-24T15:29:46",
                    text="Hello"
                )
            ],
            exported_at="2025-06-24T15:30:00"
        )

        assert response.task_id == "export_12345"
        assert response.status == "completed"
        assert response.message_count == 100
        assert len(response.messages) == 1

    def test_failed_response(self):
        """Should create failed response"""
        response = ExportResponse(
            task_id="export_12345",
            status="failed",
            error="Chat not accessible",
            error_code="CHAT_PRIVATE"
        )

        assert response.status == "failed"
        assert response.error == "Chat not accessible"
        assert response.error_code == "CHAT_PRIVATE"
        assert response.message_count == 0

    def test_in_progress_response(self):
        """Should handle in_progress status"""
        response = ExportResponse(
            task_id="export_12345",
            status="in_progress",
            message_count=50
        )

        assert response.status == "in_progress"

    def test_invalid_status(self):
        """Should validate status values"""
        with pytest.raises(ValidationError):
            ExportResponse(
                task_id="export_12345",
                status="invalid_status"  # Not in enum
            )

    def test_response_json_serialization(self):
        """Should serialize response to JSON"""
        response = ExportResponse(
            task_id="export_12345",
            status="completed",
            message_count=0,
            exported_at="2025-06-24T15:30:00"
        )

        json_data = response.model_dump(exclude_none=True)

        assert json_data["task_id"] == "export_12345"
        assert json_data["status"] == "completed"
        assert json_data["message_count"] == 0

    def test_response_with_partial_messages(self):
        """Should handle partial results on error"""
        response = ExportResponse(
            task_id="export_12345",
            status="failed",
            message_count=50,
            messages=[
                ExportedMessage(
                    id=i,
                    type="message",
                    date="2025-06-24T15:29:46",
                    text=f"Message {i}"
                )
                for i in range(1, 51)
            ],
            error="Export interrupted",
            error_code="TIMEOUT"
        )

        assert response.status == "failed"
        assert response.message_count == 50
        assert len(response.messages) == 50


class TestModelIntegration:
    """Integration tests for model chain"""

    def test_request_to_response_flow(self):
        """Should flow from request through response"""
        # Create request
        request = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            limit=1000
        )

        # Process (create response)
        response = ExportResponse(
            task_id=request.task_id,
            status="completed",
            message_count=500,
            messages=[
                ExportedMessage(
                    id=i,
                    type="message",
                    date="2025-06-24T15:29:46",
                    text=f"Message {i}"
                )
                for i in range(1, 501)
            ],
            exported_at="2025-06-24T15:30:00"
        )

        # Verify flow
        assert response.task_id == request.task_id
        assert response.message_count == 500

    def test_json_round_trip(self):
        """Should survive JSON serialization round-trip"""
        original = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            limit=1000,
            from_date="2025-01-01T00:00:00"
        )

        # Serialize
        json_data = original.model_dump()

        # Deserialize
        restored = ExportRequest(**json_data)

        # Verify
        assert restored.task_id == original.task_id
        assert restored.limit == original.limit
        assert restored.from_date == original.from_date
