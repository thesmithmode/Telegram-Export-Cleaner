
import pytest
from pydantic import ValidationError

from models import (
    ExportRequest,
    ExportedMessage,
    ExportResponse,
    MessageEntity
)

class TestExportRequest:

    def test_valid_request(self):
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
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            from_date="2025-01-01T00:00:00",
            to_date="2025-12-31T23:59:59"
        )

        assert req.from_date == "2025-01-01T00:00:00"
        assert req.to_date == "2025-12-31T23:59:59"

    def test_request_with_date_only_format(self):
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            from_date="2025-01-01",
            to_date="2025-12-31"
        )

        assert req.from_date == "2025-01-01"
        assert req.to_date == "2025-12-31"

    def test_request_with_datetime_no_seconds_format(self):
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            from_date="2026-03-30T00:00",
            to_date="2026-03-30T23:59"
        )

        assert req.from_date == "2026-03-30T00:00"
        assert req.to_date == "2026-03-30T23:59"

    def test_request_invalid_from_date_rejects(self):
        with pytest.raises(ValidationError) as exc_info:
            ExportRequest(
                task_id="export_12345",
                user_id=123456789,
                chat_id=-1001234567890,
                from_date="2025-01-99"
            )
        assert "from_date" in str(exc_info.value) or "Неверный формат" in str(exc_info.value)

    def test_request_invalid_to_date_rejects(self):
        with pytest.raises(ValidationError):
            ExportRequest(
                task_id="export_12345",
                user_id=123456789,
                chat_id=-1001234567890,
                to_date="31/12/2025"  # wrong format
            )

    def test_request_invalid_date_garbage_rejects(self):
        with pytest.raises(ValidationError):
            ExportRequest(
                task_id="export_12345",
                user_id=123456789,
                chat_id=-1001234567890,
                from_date="not-a-date"
            )

    def test_request_none_dates_accepted(self):
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            from_date=None,
            to_date=None
        )
        assert req.from_date is None
        assert req.to_date is None

    def test_request_missing_required_field(self):
        with pytest.raises(ValidationError):
            ExportRequest(
                user_id=123456789,
                chat_id=-1001234567890
                # Missing task_id
            )

    def test_request_default_values(self):
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
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            limit=1000
        )

        json_data = req.model_dump()

        assert json_data["task_id"] == "export_12345"
        assert json_data["limit"] == 1000

    def test_topic_id_defaults_to_none(self):
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890
        )
        assert req.topic_id is None

    def test_topic_id_valid(self):
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            topic_id=148220
        )
        assert req.topic_id == 148220

    def test_topic_id_one_valid(self):
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            topic_id=1
        )
        assert req.topic_id == 1

    def test_topic_id_zero_rejected(self):
        with pytest.raises(ValidationError):
            ExportRequest(
                task_id="export_12345",
                user_id=123456789,
                chat_id=-1001234567890,
                topic_id=0
            )

    def test_topic_id_negative_rejected(self):
        with pytest.raises(ValidationError):
            ExportRequest(
                task_id="export_12345",
                user_id=123456789,
                chat_id=-1001234567890,
                topic_id=-5
            )

    def test_topic_id_in_json_serialization(self):
        req = ExportRequest(
            task_id="export_12345",
            user_id=123456789,
            chat_id=-1001234567890,
            topic_id=148220
        )
        json_data = req.model_dump()
        assert json_data["topic_id"] == 148220

class TestMessageEntity:

    def test_simple_entity(self):
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

    def test_text_link_entity(self):
        entity = MessageEntity(
            type="text_link",
            offset=0,
            length=4,
            url="https://example.com"
        )

        assert entity.type == "text_link"
        assert entity.url == "https://example.com"

    def test_text_mention_entity(self):
        entity = MessageEntity(
            type="text_mention",
            offset=0,
            length=4,
            user_id=123456789
        )

        assert entity.type == "text_mention"
        assert entity.user_id == 123456789

    def test_entity_missing_required(self):
        with pytest.raises(ValidationError):
            MessageEntity(
                type="bold"
                # Missing offset and length
            )

    def test_emoji_entity_offset_utf16_convention(self):
        emoji_text = "🎉 text"

        # Python len() sees 6 characters: emoji=1 code point + space=1 + 'text'=4.
        # Python uses UCS-4 internally — one Python char per Unicode code point,
        # surrogates not exposed. THIS IS THE WRONG NUMBER FOR TELEGRAM OFFSETS.
        assert len(emoji_text) == 6

        # UTF-16 encoding: 🎉 → surrogate pair (2 units) + space (1) + 'text' (4) = 7.
        # This is the count Telegram API uses for offset/length in MessageEntity —
        # 1 unit MORE than Python len() because of the surrogate pair.
        utf16_units = len(emoji_text.encode("utf-16-le")) // 2
        assert utf16_units == 7
        assert utf16_units == len(emoji_text) + 1  # exactly 1 unit more

        # emoji alone: 2 UTF-16 units, 1 Python char
        emoji_alone = "🎉"
        assert len(emoji_alone) == 1                                    # Python sees 1
        assert len(emoji_alone.encode("utf-16-le")) // 2 == 2          # UTF-16: 2 units

        # Entity on "text" after "🎉 " → UTF-16 offset is 3 (2+1), NOT 2 (1+1 in Python)
        entity = MessageEntity(type="bold", offset=3, length=4)
        assert entity.offset == 3, (
            "bold entity on 'text' in '🎉 text' must have UTF-16 offset 3: "
            "emoji=2 units + space=1 unit. offset=2 would be wrong (Python indexing)."
        )
        assert entity.length == 4  # 't','e','x','t' — all BMP, 1 unit each

    def test_multi_emoji_offsets_accumulate(self):
        text = "🔥🎉 ok"
        # UTF-16 length of prefix "🔥🎉 "
        prefix_utf16 = len("🔥🎉 ".encode("utf-16-le")) // 2
        assert prefix_utf16 == 5  # 2 + 2 + 1

        entity = MessageEntity(type="italic", offset=5, length=2)
        assert entity.offset == 5
        assert entity.length == 2

class TestExportedMessage:

    def test_minimal_message(self):
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
        msg = ExportedMessage(
            id=123,
            type="message",  # Should be "message"
            date="2025-06-24T15:29:46"
        )

        assert msg.type == "message"

class TestExportResponse:

    def test_success_response(self):
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
        response = ExportResponse(
            task_id="export_12345",
            status="in_progress",
            message_count=50
        )

        assert response.status == "in_progress"

    def test_invalid_status(self):
        with pytest.raises(ValidationError):
            ExportResponse(
                task_id="export_12345",
                status="invalid_status"  # Not in enum
            )

    def test_response_json_serialization(self):
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

    def test_request_to_response_flow(self):
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
