
import pytest
from datetime import datetime
from unittest.mock import Mock, MagicMock

from json_converter import MessageConverter
from models import ExportedMessage, MessageEntity as MessageEntityModel

class TestUserDisplayName:

    def test_user_with_first_and_last_name(self):
        user = Mock()
        user.first_name = "John"
        user.last_name = "Doe"
        user.username = None
        user.id = 123

        result = MessageConverter.get_user_display_name(user)
        assert result == "John Doe"

    def test_user_with_only_first_name(self):
        user = Mock()
        user.first_name = "John"
        user.last_name = None
        user.username = None
        user.id = 123

        result = MessageConverter.get_user_display_name(user)
        assert result == "John"

    def test_user_with_only_username(self):
        user = Mock()
        user.first_name = None
        user.last_name = None
        user.username = "johndoe"
        user.id = 123

        result = MessageConverter.get_user_display_name(user)
        assert result == "@johndoe"

    def test_user_with_only_id(self):
        user = Mock()
        user.first_name = None
        user.last_name = None
        user.username = None
        user.id = 123

        result = MessageConverter.get_user_display_name(user)
        assert result == "ID:123"

    def test_none_user(self):
        result = MessageConverter.get_user_display_name(None)
        assert result is None

class TestEntityConversion:

    def test_single_bold_entity(self):
        entity = Mock()
        entity.type = "bold"
        entity.offset = 0
        entity.length = 4

        result = MessageConverter.convert_entities([entity])

        assert len(result) == 1
        assert result[0].type == "bold"
        assert result[0].offset == 0
        assert result[0].length == 4

    def test_multiple_entities(self):
        bold = Mock()
        bold.type = "bold"
        bold.offset = 0
        bold.length = 4

        link = Mock()
        link.type = "url"
        link.offset = 5
        link.length = 10

        result = MessageConverter.convert_entities([bold, link])

        assert len(result) == 2
        assert result[0].type == "bold"
        assert result[1].type == "link"

    def test_text_link_entity(self):
        entity = Mock()
        entity.type = Mock()
        entity.type.name = "TEXT_LINK"
        entity.offset = 0
        entity.length = 4
        entity.url = "https://example.com"

        result = MessageConverter.convert_entities([entity])

        assert len(result) == 1
        assert result[0].type == "text_link"
        assert result[0].url == "https://example.com"

    def test_empty_entities_list(self):
        result = MessageConverter.convert_entities([])
        assert result is None

    def test_none_entities(self):
        result = MessageConverter.convert_entities(None)
        assert result is None

class TestMediaTypeDetection:

    def test_photo_detection(self):
        from pyrogram import types

        media = Mock(spec=types.Photo)
        media.__class__.__name__ = "Photo"

        result = MessageConverter.get_media_type(media)
        assert result == "photo"

    def test_video_detection(self):
        media = Mock()
        media.__class__.__name__ = "Video"

        result = MessageConverter.get_media_type(media)
        assert result == "video"

    def test_audio_detection(self):
        media = Mock()
        media.__class__.__name__ = "Audio"

        result = MessageConverter.get_media_type(media)
        assert result == "audio"

    def test_document_detection(self):
        media = Mock()
        media.__class__.__name__ = "Document"

        result = MessageConverter.get_media_type(media)
        assert result == "document"

    def test_none_media(self):
        result = MessageConverter.get_media_type(None)
        assert result is None

    def test_unknown_media_type(self):
        media = Mock()
        media.__class__.__name__ = "UnknownType"

        result = MessageConverter.get_media_type(media)
        assert result is None

class TestMessageConversion:

    def test_simple_text_message(self):
        message = Mock()
        message.id = 123
        message.date = datetime(2025, 6, 24, 15, 29, 46)
        message.text = "Hello world"
        message.from_user = Mock()
        message.from_user.first_name = "John"
        message.from_user.last_name = "Doe"
        message.from_user.id = 456
        message.entities = None
        message.media = None
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.id == 123
        assert result.type == "message"
        assert result.date == "2025-06-24T15:29:46"
        assert result.text == "Hello world"
        assert result.from_user == "John Doe"
        assert result.from_id == {"peer_type": "user", "peer_id": 456}

    def test_message_with_entities(self):
        message = Mock()
        message.id = 124
        message.date = datetime(2025, 6, 24, 15, 30, 0)
        message.text = "Bold text"
        message.from_user = None
        message.entities = [Mock(type="bold", offset=0, length=4)]
        message.media = None
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.text_entities is not None
        assert len(result.text_entities) == 1
        assert result.text_entities[0].type == "bold"

    def test_message_with_photo(self):
        photo = Mock()
        photo.__class__.__name__ = "Photo"
        photo.file_name = "photo_123.jpg"
        photo.width = 1024
        photo.height = 768

        message = Mock()
        message.id = 125
        message.date = datetime(2025, 6, 24, 15, 31, 0)
        message.text = "Check photo"
        message.from_user = None
        message.entities = None
        message.media = photo
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.media_type == "photo"
        assert result.photo == "photo_123.jpg"
        assert result.width == 1024
        assert result.height == 768

    def test_message_with_forward(self):
        forward_user = Mock()
        forward_user.first_name = "Alice"
        forward_user.last_name = None
        forward_user.id = 789

        message = Mock()
        message.id = 126
        message.date = datetime(2025, 6, 24, 15, 32, 0)
        message.text = "Interesting"
        message.from_user = None
        message.entities = None
        message.media = None
        message.forward_from = forward_user
        message.forward_sender_name = None
        message.forward_date = datetime(2025, 6, 24, 14, 0, 0)
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.forward_from == "Alice"
        assert result.forward_date == "2025-06-24T14:00:00"

    def test_message_with_edit(self):
        message = Mock()
        message.id = 127
        message.date = datetime(2025, 6, 24, 15, 33, 0)
        message.text = "Updated text"
        message.from_user = None
        message.entities = None
        message.media = None
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = datetime(2025, 6, 24, 15, 34, 0)
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.edited is True
        assert result.edit_date == "2025-06-24T15:34:00"

    def test_message_with_reply(self):
        message = Mock()
        message.id = 128
        message.date = datetime(2025, 6, 24, 15, 35, 0)
        message.text = "Reply text"
        message.from_user = None
        message.entities = None
        message.media = None
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = 127

        result = MessageConverter.convert_message(message)

        assert result.reply_to_message_id == 127

    def test_message_without_text(self):
        message = Mock()
        message.id = 129
        message.date = datetime(2025, 6, 24, 15, 36, 0)
        message.text = None
        message.caption = None
        message.from_user = None
        message.entities = None
        message.caption_entities = None
        message.media = None
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.text == ""

    def test_media_message_with_caption(self):
        photo = Mock()
        photo.__class__.__name__ = "Photo"
        photo.file_name = "photo.jpg"
        photo.width = 800
        photo.height = 600

        message = Mock()
        message.id = 130
        message.date = datetime(2025, 6, 24, 15, 37, 0)
        message.text = None
        message.caption = "Подпись к фото"
        message.from_user = None
        message.entities = None
        message.caption_entities = None
        message.media = photo
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.text == "Подпись к фото"
        assert result.media_type == "photo"

    def test_media_message_without_caption(self):
        photo = Mock()
        photo.__class__.__name__ = "Photo"
        photo.file_name = "photo.jpg"
        photo.width = 800
        photo.height = 600

        message = Mock()
        message.id = 131
        message.date = datetime(2025, 6, 24, 15, 38, 0)
        message.text = None
        message.caption = None
        message.from_user = None
        message.entities = None
        message.caption_entities = None
        message.media = photo
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.text == ""
        assert result.media_type == "photo"

    def test_media_message_with_caption_entities(self):
        photo = Mock()
        photo.__class__.__name__ = "Photo"
        photo.file_name = "photo.jpg"
        photo.width = 800
        photo.height = 600

        caption_entity = Mock(type="bold", offset=0, length=7)

        message = Mock()
        message.id = 132
        message.date = datetime(2025, 6, 24, 15, 39, 0)
        message.text = None
        message.caption = "Жирный текст"
        message.from_user = None
        message.entities = None
        message.caption_entities = [caption_entity]
        message.media = photo
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.text == "Жирный текст"
        assert result.text_entities is not None
        assert len(result.text_entities) == 1
        assert result.text_entities[0].type == "bold"

    def test_text_message_ignores_caption(self):
        message = Mock()
        message.id = 133
        message.date = datetime(2025, 6, 24, 15, 40, 0)
        message.text = "Обычный текст"
        message.caption = "Этот caption должен быть проигнорирован"
        message.from_user = None
        message.entities = None
        message.caption_entities = None
        message.media = None
        message.forward_from = None
        message.forward_sender_name = None
        message.forward_date = None
        message.edit_date = None
        message.reply_to_message_id = None

        result = MessageConverter.convert_message(message)

        assert result.text == "Обычный текст"

    def test_convert_messages_batch(self):
        messages = []
        for i in range(3):
            msg = Mock()
            msg.id = 100 + i
            msg.date = datetime(2025, 6, 24, 15, 30, 0)
            msg.text = f"Message {i}"
            msg.from_user = None
            msg.entities = None
            msg.media = None
            msg.forward_from = None
            msg.forward_sender_name = None
            msg.forward_date = None
            msg.edit_date = None
            msg.reply_to_message_id = None
            messages.append(msg)

        result = MessageConverter.convert_messages(messages)

        assert len(result) == 3
        assert result[0].id == 100
        assert result[1].id == 101
        assert result[2].id == 102

    def test_convert_messages_with_error_skips(self):
        good_msg = Mock()
        good_msg.id = 100
        good_msg.date = datetime(2025, 6, 24, 15, 30, 0)
        good_msg.text = "Good"
        good_msg.from_user = None
        good_msg.entities = None
        good_msg.media = None
        good_msg.forward_from = None
        good_msg.forward_sender_name = None
        good_msg.forward_date = None
        good_msg.edit_date = None
        good_msg.reply_to_message_id = None

        bad_msg = Mock()
        bad_msg.id = 101
        bad_msg.date = Mock()  # This will cause error
        bad_msg.date.isoformat.side_effect = Exception("Date error")

        result = MessageConverter.convert_messages([good_msg, bad_msg])

        # Should only have the good message
        assert len(result) == 1
        assert result[0].id == 100

class TestExportedMessageModel:

    def test_model_creation(self):
        msg = ExportedMessage(
            id=123,
            type="message",
            date="2025-06-24T15:29:46",
            text="Hello"
        )

        assert msg.id == 123
        assert msg.type == "message"
        assert msg.date == "2025-06-24T15:29:46"
        assert msg.text == "Hello"

    def test_model_json_serialization(self):
        msg = ExportedMessage(
            id=123,
            type="message",
            date="2025-06-24T15:29:46",
            text="Hello"
        )

        json_data = msg.model_dump()

        assert json_data["id"] == 123
        assert json_data["text"] == "Hello"

    def test_model_optional_fields(self):
        msg = ExportedMessage(
            id=123,
            type="message",
            date="2025-06-24T15:29:46"
        )

        assert msg.text == ""
        assert msg.from_user is None
        assert msg.media_type is None
