"""
JSON Converter: Pyrogram Message → Telegram Desktop export format.

Converts Pyrogram Message objects to result.json compatible format.
Handles:
- Text and entities (formatting)
- Media metadata (photo, video, audio, etc)
- Forwarded messages
- Edited messages
- User information
"""

import logging
from typing import Optional, List, Dict, Any
from datetime import datetime

from pyrogram import types as pyrogram_types
from models import ExportedMessage, MessageEntity as MessageEntityModel

logger = logging.getLogger(__name__)


class MessageConverter:
    """Converter from Pyrogram Message to result.json format."""

    # Entity type mapping: Pyrogram → result.json
    ENTITY_TYPE_MAP = {
        "bold": "bold",
        "italic": "italic",
        "code": "code",
        "pre": "pre",
        "url": "link",
        "text_url": "text_url",
        "mention": "mention",
        "text_mention": "text_mention",
        "email": "email",
        "phone_number": "phone_number",
        "hashtag": "hashtag",
        "cashtag": "cashtag",
        "bot_command": "bot_command",
        "strikethrough": "strikethrough",
        "underline": "underline",
        "blockquote": "blockquote",
        "spoiler": "spoiler",
        "custom_emoji": "custom_emoji",
    }

    # Media type mapping: Pyrogram class → result.json string
    MEDIA_TYPE_MAP = {
        "Photo": "photo",
        "Video": "video",
        "Audio": "audio",
        "Voice": "voice",
        "VideoNote": "video_note",
        "Document": "document",
        "Animation": "animation",
        "Sticker": "sticker",
        "Contact": "contact",
        "Location": "location",
        "Poll": "poll",
        "Venue": "venue",
        "Game": "game",
        "WebPage": "web_page",
    }

    @staticmethod
    def get_user_display_name(user: Optional[pyrogram_types.User]) -> Optional[str]:
        """
        Convert User object to display name string.

        Returns: "FirstName LastName", "@username", "ID:123", or None
        """
        if not user:
            return None

        parts = []
        if user.first_name:
            parts.append(user.first_name)
        if user.last_name:
            parts.append(user.last_name)

        if parts:
            return " ".join(parts)

        if user.username:
            return f"@{user.username}"

        if user.id:
            return f"ID:{user.id}"

        return None

    @staticmethod
    def convert_entities(
        entities: Optional[List[pyrogram_types.MessageEntity]],
        text: str = ""
    ) -> Optional[List[MessageEntityModel]]:
        """
        Convert MessageEntity list to result.json format.

        MessageEntity has: type, offset (position), length (count)
        result.json needs: type, offset (from), length (to)
        """
        if not entities:
            return None

        result = []
        try:
            for entity in entities:
                entity_type = MessageConverter.ENTITY_TYPE_MAP.get(
                    entity.type.lower(),
                    entity.type.lower()
                )

                model = MessageEntityModel(
                    type=entity_type,
                    offset=entity.offset,
                    length=entity.length
                )

                # Add URL for text_url type
                if entity.type == "text_url" and hasattr(entity, 'url'):
                    model.url = entity.url

                # Add user ID for text_mention type
                if entity.type == "text_mention" and hasattr(entity, 'user') and entity.user:
                    model.user_id = entity.user.id

                result.append(model)

        except Exception as e:
            logger.warning(f"Error converting entities: {e}")
            return None if not result else result

        return result if result else None

    @staticmethod
    def get_media_type(media: Optional[Any]) -> Optional[str]:
        """
        Determine media type from Pyrogram media object.

        Returns: "photo", "video", "audio", etc., or None
        """
        if not media:
            return None

        media_class_name = media.__class__.__name__
        return MessageConverter.MEDIA_TYPE_MAP.get(media_class_name, None)

    @staticmethod
    def convert_message(message: pyrogram_types.Message) -> ExportedMessage:
        """
        Convert Pyrogram Message to result.json format.

        Main conversion logic:
        1. Basic fields (id, type, date, text)
        2. User information
        3. Text entities (formatting)
        4. Media metadata
        5. Forward information
        6. Edit information
        7. Reply information
        """
        try:
            # Basic fields
            exported = ExportedMessage(
                id=message.id,
                type="message",
                date=message.date.isoformat() if message.date else "",
                text=message.text or ""
            )

            # User information
            if message.from_user:
                exported.from_user = MessageConverter.get_user_display_name(message.from_user)
                exported.from_id = {
                    "peer_type": "user",
                    "peer_id": message.from_user.id
                }

            # Text entities (formatting)
            if message.entities:
                exported.text_entities = MessageConverter.convert_entities(
                    message.entities,
                    message.text or ""
                )

            # Media
            if message.media:
                media_type = MessageConverter.get_media_type(message.media)
                if media_type:
                    exported.media_type = media_type

                    # Store filename
                    if hasattr(message.media, 'file_name') and message.media.file_name:
                        setattr(exported, media_type, message.media.file_name)

                    # Media dimensions
                    if hasattr(message.media, 'width'):
                        exported.width = message.media.width
                    if hasattr(message.media, 'height'):
                        exported.height = message.media.height
                    if hasattr(message.media, 'duration'):
                        exported.duration = message.media.duration

            # Forwarded message info
            if message.forward_from or message.forward_sender_name:
                if message.forward_from:
                    exported.forward_from = MessageConverter.get_user_display_name(
                        message.forward_from
                    )
                else:
                    exported.forward_from = message.forward_sender_name

            if message.forward_date:
                exported.forward_date = message.forward_date.isoformat()

            # Edited message info
            if message.edit_date:
                exported.edited = True
                exported.edit_date = message.edit_date.isoformat()

            # Reply info
            if message.reply_to_message_id:
                exported.reply_to_message_id = message.reply_to_message_id

            return exported

        except Exception as e:
            logger.error(f"Error converting message {message.id}: {e}", exc_info=True)
            raise

    @staticmethod
    def convert_messages(
        messages: List[pyrogram_types.Message]
    ) -> List[ExportedMessage]:
        """Convert list of messages."""
        result = []
        for message in messages:
            try:
                converted = MessageConverter.convert_message(message)
                result.append(converted)
            except Exception as e:
                logger.error(f"Skipping message {message.id} due to error: {e}")
                continue

        return result
