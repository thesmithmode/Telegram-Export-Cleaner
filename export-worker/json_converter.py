
import logging
from typing import Optional, List, Any

from pyrogram import types as pyrogram_types
from models import ExportedMessage, MessageEntity as MessageEntityModel

logger = logging.getLogger(__name__)

class MessageConverter:

    # Entity type mapping: Pyrogram → result.json
    ENTITY_TYPE_MAP = {
        "bold": "bold",
        "italic": "italic",
        "code": "code",
        "pre": "pre",
        "url": "link",
        "text_link": "text_link",
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
        "bank_card": "bank_card",
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
        entities: Optional[List[pyrogram_types.MessageEntity]]
    ) -> Optional[List[MessageEntityModel]]:
        if not entities:
            return None

        result = []
        try:
            for entity in entities:
                # entity.type is MessageEntityType enum, convert to string first
                type_str = entity.type.name.lower() if hasattr(entity.type, 'name') else str(entity.type).lower()
                entity_type = MessageConverter.ENTITY_TYPE_MAP.get(
                    type_str,
                    type_str
                )

                model = MessageEntityModel(
                    type=entity_type,
                    offset=entity.offset,
                    length=entity.length
                )

                # Add URL for text_url type
                if entity_type == "text_link" and hasattr(entity, 'url'):
                    model.url = entity.url

                # Add user ID for text_mention type
                if entity_type == "text_mention" and hasattr(entity, 'user') and entity.user:
                    model.user_id = entity.user.id

                result.append(model)

        except Exception as e:
            logger.warning(f"Error converting entities: {e}")
            return None if not result else result

        return result if result else None

    @staticmethod
    def get_media_type(media: Optional[Any]) -> Optional[str]:
        if not media:
            return None

        media_class_name = media.__class__.__name__
        return MessageConverter.MEDIA_TYPE_MAP.get(media_class_name, None)

    @staticmethod
    def convert_message(message: pyrogram_types.Message) -> ExportedMessage:
        try:
            # Basic fields
            exported = ExportedMessage(
                id=message.id,
                type="message",
                date=message.date.isoformat() if message.date else "",
                text=message.text or message.caption or ""
            )

            # User information
            if message.from_user:
                exported.from_user = MessageConverter.get_user_display_name(message.from_user)
                exported.from_id = {
                    "peer_type": "user",
                    "peer_id": message.from_user.id
                }

            # Text entities (formatting)
            entities = message.entities or message.caption_entities
            if entities:
                exported.text_entities = MessageConverter.convert_entities(
                    entities
                )

            # Media
            if message.media:
                media_type = MessageConverter.get_media_type(message.media)
                if media_type:
                    exported.media_type = media_type

                    # Store filename only for known model fields
                    known_media_fields = {"photo", "video", "audio", "voice", "document", "animation", "sticker"}
                    if media_type in known_media_fields:
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
        result = []
        for message in messages:
            try:
                converted = MessageConverter.convert_message(message)
                result.append(converted)
            except Exception as e:
                logger.error(f"Skipping message {message.id} due to error: {e}")
                continue

        return result
