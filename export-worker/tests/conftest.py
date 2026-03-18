"""
Pytest configuration and fixtures for export-worker tests
"""

import pytest
from datetime import datetime
from unittest.mock import Mock

from models import ExportRequest, ExportedMessage, MessageEntity


@pytest.fixture
def sample_export_request():
    """Fixture: Sample ExportRequest"""
    return ExportRequest(
        task_id="export_test_12345",
        user_id=123456789,
        chat_id=-1001234567890,
        limit=1000,
        offset_id=0,
        from_date="2025-01-01T00:00:00",
        to_date="2025-12-31T23:59:59"
    )


@pytest.fixture
def simple_message():
    """Fixture: Simple Pyrogram-like message mock"""
    message = Mock()
    message.id = 123
    message.date = datetime(2025, 6, 24, 15, 29, 46)
    message.text = "Hello world"
    message.from_user = Mock()
    message.from_user.first_name = "John"
    message.from_user.last_name = "Doe"
    message.from_user.id = 456
    message.from_user.username = None
    message.entities = None
    message.media = None
    message.forward_from = None
    message.forward_sender_name = None
    message.forward_date = None
    message.edit_date = None
    message.reply_to_message_id = None
    return message


@pytest.fixture
def message_with_entities():
    """Fixture: Message with text entities (formatting)"""
    message = Mock()
    message.id = 124
    message.date = datetime(2025, 6, 24, 15, 30, 0)
    message.text = "Bold italic code"
    message.from_user = None

    # Entities
    bold = Mock()
    bold.type = "bold"
    bold.offset = 0
    bold.length = 4

    italic = Mock()
    italic.type = "italic"
    italic.offset = 5
    italic.length = 6

    code = Mock()
    code.type = "code"
    code.offset = 12
    code.length = 4

    message.entities = [bold, italic, code]
    message.media = None
    message.forward_from = None
    message.forward_sender_name = None
    message.forward_date = None
    message.edit_date = None
    message.reply_to_message_id = None
    return message


@pytest.fixture
def message_with_media():
    """Fixture: Message with photo"""
    photo = Mock()
    photo.__class__.__name__ = "Photo"
    photo.file_name = "photo_123.jpg"
    photo.width = 1024
    photo.height = 768

    message = Mock()
    message.id = 125
    message.date = datetime(2025, 6, 24, 15, 31, 0)
    message.text = "Check this photo"
    message.from_user = Mock()
    message.from_user.first_name = "Alice"
    message.from_user.last_name = None
    message.from_user.id = 789
    message.from_user.username = None
    message.entities = None
    message.media = photo
    message.forward_from = None
    message.forward_sender_name = None
    message.forward_date = None
    message.edit_date = None
    message.reply_to_message_id = None
    return message


@pytest.fixture
def forwarded_message():
    """Fixture: Forwarded message"""
    forward_user = Mock()
    forward_user.first_name = "Bob"
    forward_user.last_name = None
    forward_user.id = 999
    forward_user.username = None

    message = Mock()
    message.id = 126
    message.date = datetime(2025, 6, 24, 15, 32, 0)
    message.text = "Interesting article"
    message.from_user = Mock()
    message.from_user.first_name = "Charlie"
    message.from_user.last_name = None
    message.from_user.id = 111
    message.from_user.username = None
    message.entities = None
    message.media = None
    message.forward_from = forward_user
    message.forward_sender_name = None
    message.forward_date = datetime(2025, 6, 23, 10, 0, 0)
    message.edit_date = None
    message.reply_to_message_id = None
    return message


@pytest.fixture
def edited_message():
    """Fixture: Edited message"""
    message = Mock()
    message.id = 127
    message.date = datetime(2025, 6, 24, 15, 33, 0)
    message.text = "Updated text"
    message.from_user = Mock()
    message.from_user.first_name = "David"
    message.from_user.last_name = None
    message.from_user.id = 222
    message.from_user.username = None
    message.entities = None
    message.media = None
    message.forward_from = None
    message.forward_sender_name = None
    message.forward_date = None
    message.edit_date = datetime(2025, 6, 24, 15, 35, 0)
    message.reply_to_message_id = None
    return message


@pytest.fixture
def sample_exported_message():
    """Fixture: Sample ExportedMessage"""
    return ExportedMessage(
        id=123,
        type="message",
        date="2025-06-24T15:29:46",
        text="Hello world",
        from_user="John Doe",
        from_id={"peer_type": "user", "peer_id": 456}
    )


@pytest.fixture
def sample_message_entity():
    """Fixture: Sample MessageEntity"""
    return MessageEntity(
        type="bold",
        offset=0,
        length=5
    )


@pytest.fixture
def url_message_entity():
    """Fixture: URL MessageEntity"""
    return MessageEntity(
        type="text_url",
        offset=0,
        length=7,
        url="https://example.com"
    )


@pytest.fixture
def mention_message_entity():
    """Fixture: Mention MessageEntity"""
    return MessageEntity(
        type="text_mention",
        offset=0,
        length=4,
        user_id=123456789
    )
