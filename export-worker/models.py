
from typing import Any, List, Optional, Union
from datetime import datetime
from enum import Enum
from typing_extensions import TypedDict
from pydantic import BaseModel, Field, ConfigDict, field_validator


class FromIdInfo(TypedDict):
    peer_type: str
    peer_id: int

class ErrorCode(str, Enum):

    # Non-retryable errors
    CHAT_NOT_ACCESSIBLE = "CHAT_NOT_ACCESSIBLE"
    CHAT_PRIVATE = "CHAT_PRIVATE"
    CHAT_ADMIN_REQUIRED = "CHAT_ADMIN_REQUIRED"
    INVALID_CHAT_ID = "INVALID_CHAT_ID"

    # Retryable errors
    EXPORT_ERROR = "EXPORT_ERROR"
    NETWORK_ERROR = "NETWORK_ERROR"
    TIMEOUT = "TIMEOUT"

    # Rate limiting
    RATE_LIMIT = "RATE_LIMIT"

class ExportRequest(BaseModel):

    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "task_id": "export_12345",
                "user_id": 123456789,
                "chat_id": -1001234567890,
                "limit": 1000,
                "offset_id": 0,
                "from_date": "2025-01-01T00:00:00",
                "to_date": "2025-12-31T23:59:59"
            }
        }
    )

    task_id: str = Field(..., description="Unique task ID from Java")
    user_id: int = Field(..., description="Telegram user ID requesting export")
    user_chat_id: Optional[int] = Field(None, description="Telegram chat ID to send result back (bot sends here)")
    chat_id: Union[int, str] = Field(..., description="Telegram chat ID or username to export")
    topic_id: Optional[int] = Field(None, gt=0, description="Forum topic ID (message_thread_id) for topic-specific export")
    limit: int = Field(default=0, description="Max messages (0=all)")
    offset_id: int = Field(default=0, description="Start from message ID")
    from_date: Optional[str] = Field(None, description="ISO date filter (YYYY-MM-DD, YYYY-MM-DDTHH:MM or YYYY-MM-DDTHH:MM:SS)")
    to_date: Optional[str] = Field(None, description="ISO date filter (YYYY-MM-DD, YYYY-MM-DDTHH:MM or YYYY-MM-DDTHH:MM:SS)")
    keywords: Optional[str] = Field(None, description="Comma-separated keywords to include")
    exclude_keywords: Optional[str] = Field(None, description="Comma-separated keywords to exclude")

    @property
    def effective_topic_id(self) -> int:
        return self.topic_id or 0

    @field_validator("chat_id", mode="before")
    @classmethod
    def coerce_chat_id(cls, v):
        if isinstance(v, str):
            stripped = v.strip()
            try:
                return int(stripped)
            except ValueError:
                return stripped  # username — keep as string
        return v

    @field_validator("from_date", "to_date", mode="before")
    @classmethod
    def validate_date(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M", "%Y-%m-%d"):
            try:
                datetime.strptime(v, fmt)
                return v
            except ValueError:
                continue
        raise ValueError(
            f"Неверный формат даты: '{v}'. Ожидается YYYY-MM-DD, YYYY-MM-DDTHH:MM или YYYY-MM-DDTHH:MM:SS"
        )

class MessageEntity(BaseModel):

    model_config = ConfigDict(
        json_schema_extra={
            "examples": [
                {"type": "bold", "offset": 0, "length": 4},
                {"type": "url", "offset": 5, "length": 10},
                {
                    "type": "text_url",
                    "offset": 5,
                    "length": 10,
                    "url": "https://example.com"
                }
            ]
        }
    )

    type: str = Field(..., description="Entity type: bold, italic, code, url, etc")
    offset: int = Field(
        ...,
        description=(
            "Start offset in UTF-16 code units (Telegram API convention). "
            "Emoji and other characters outside the Basic Multilingual Plane "
            "(U+10000+) count as 2 units each — consistent with Java char[] "
            "and JavaScript string.length, NOT with Python len()."
        ),
    )
    length: int = Field(
        ...,
        description=(
            "Length in UTF-16 code units (Telegram API convention). "
            "Same UTF-16 surrogate-pair counting as offset."
        ),
    )
    url: Optional[str] = Field(None, description="URL for text_url/custom_emoji")
    user_id: Optional[int] = Field(None, description="User ID for text_mention")

class ExportedMessage(BaseModel):

    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "id": 123,
                "type": "message",
                "date": "2025-06-24T15:29:46",
                "text": "Hello, world!",
                "from_user": "John Doe",
                "from_id": {"peer_type": "user", "peer_id": 456},
                "text_entities": [
                    {"type": "bold", "offset": 0, "length": 5}
                ]
            }
        }
    )

    id: int = Field(..., description="Message ID")
    type: str = Field(default="message", description="Always 'message'")
    date: str = Field(..., description="ISO 8601 datetime")
    text: str = Field(default="", description="Message text")
    from_user: Optional[str] = Field(None, description="Sender name")
    from_id: Optional[FromIdInfo] = Field(None, description="Sender ID info")
    text_entities: Optional[List[MessageEntity]] = Field(None, description="Text formatting")
    media_type: Optional[str] = Field(None, description="photo, video, audio, etc")
    edited: Optional[bool] = Field(None, description="True if message was edited")
    edit_date: Optional[str] = Field(None, description="Edit timestamp (ISO 8601)")
    forward_from: Optional[str] = Field(None, description="Original sender name")
    forward_date: Optional[str] = Field(None, description="Forward timestamp")
    reply_to_message_id: Optional[int] = Field(None, description="Replied message ID")

    # Media files
    photo: Optional[str] = Field(None, description="Photo filename")
    video: Optional[str] = Field(None, description="Video filename")
    audio: Optional[str] = Field(None, description="Audio filename")
    voice: Optional[str] = Field(None, description="Voice message filename")
    document: Optional[str] = Field(None, description="Document filename")
    animation: Optional[str] = Field(None, description="Animation/GIF filename")
    sticker: Optional[str] = Field(None, description="Sticker filename")

    # Media dimensions
    width: Optional[int] = Field(None, description="Media width")
    height: Optional[int] = Field(None, description="Media height")
    duration: Optional[int] = Field(None, description="Media duration (seconds)")

class SendResponsePayload(BaseModel):

    task_id: str = Field(..., description="Unique task ID")
    status: str = Field(
        ...,
        description="completed or failed",
        pattern="^(completed|failed)$"
    )
    # Runtime-полиморфно: List[ExportedMessage] из in-memory путей или
    # AsyncIterator/AsyncGenerator из cache-aware экспорта. Pydantic не валидирует
    # async-итераторы — оставляем Any, валидация поля не нужна (сериализуется не
    # через Pydantic, а напрямую в _stream_to_temp_json).
    messages: Union[List[ExportedMessage], Any] = Field(
        ...,
        description="Exported messages (list or AsyncIterator)"
    )
    actual_count: int = Field(default=0, description="Number of messages actually exported")
    error: Optional[str] = Field(None, description="Error message if status=failed")
    error_code: Optional[str] = Field(None, description="Error code for retries")
    user_chat_id: Optional[int] = Field(None, description="User chat ID to send result")
    user_id: Optional[int] = Field(None, description="Telegram bot user_id for stats (bot_user_id)")
    chat_title: Optional[str] = Field(None, description="Chat title for filename")
    from_date: Optional[str] = Field(None, description="Date range filter start")
    to_date: Optional[str] = Field(None, description="Date range filter end")
    keywords: Optional[str] = Field(None, description="Keywords filter")
    exclude_keywords: Optional[str] = Field(None, description="Exclude keywords filter")

class ExportResponse(BaseModel):

    model_config = ConfigDict(
        json_schema_extra={
            "examples": [
                {
                    "task_id": "export_12345",
                    "status": "completed",
                    "message_count": 100,
                    "exported_at": "2025-06-24T15:30:00",
                    "messages": [
                        {
                            "id": 123,
                            "type": "message",
                            "date": "2025-06-24T15:29:46",
                            "text": "Hello"
                        }
                    ]
                },
                {
                    "task_id": "export_12345",
                    "status": "failed",
                    "error": "Chat not accessible",
                    "error_code": "CHAT_PRIVATE"
                }
            ]
        }
    )

    task_id: str = Field(..., description="Original task ID")
    status: str = Field(
        ...,
        description="completed, failed, in_progress",
        pattern="^(completed|failed|in_progress)$"
    )
    message_count: int = Field(default=0, description="Number of messages exported")
    messages: List[ExportedMessage] = Field(default_factory=list, description="Exported messages")
    error: Optional[str] = Field(None, description="Error message if failed")
    error_code: Optional[str] = Field(None, description="Error code for retries")
    exported_at: Optional[str] = Field(None, description="Export timestamp")
