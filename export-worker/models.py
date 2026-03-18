"""
Pydantic models for Export Worker.

Defines data structures for:
- Export requests from Java queue
- Export responses to Java API
- Message entity formatting
- Error code enumeration
"""

from typing import List, Optional, Dict, Any
from datetime import datetime
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class ErrorCode(str, Enum):
    """Export error codes for job failure categorization.

    Error codes help identify failure types and determine appropriate handling:

    Permanent Failures (don't retry):
    - CHAT_NOT_ACCESSIBLE: Chat was deleted or user was kicked
    - CHAT_PRIVATE: Chat is private or user has no access
    - CHAT_ADMIN_REQUIRED: Exporting chat requires admin rights
    - INVALID_CHAT_ID: Invalid chat ID format or syntax error

    Temporary Failures (may retry):
    - EXPORT_ERROR: Unexpected error during export (partial results sent)
    - NETWORK_ERROR: Network connectivity issue
    - TIMEOUT: Job exceeded maximum time limit

    Rate Limiting (auto-retry with backoff):
    - RATE_LIMIT: Telegram FloodWait (auto-retried with exponential backoff)
    """

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
    """Request to export chat messages."""

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
    chat_id: int = Field(..., description="Telegram chat ID to export")
    limit: int = Field(default=0, description="Max messages (0=all)")
    offset_id: int = Field(default=0, description="Start from message ID")
    from_date: Optional[str] = Field(None, description="ISO date filter")
    to_date: Optional[str] = Field(None, description="ISO date filter")


class MessageEntity(BaseModel):
    """Text entity (formatting, links, mentions)."""

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
    offset: int = Field(..., description="Start offset in UTF-8 characters")
    length: int = Field(..., description="Length in UTF-8 characters")
    url: Optional[str] = Field(None, description="URL for text_url/custom_emoji")
    user_id: Optional[int] = Field(None, description="User ID for text_mention")


class ExportedMessage(BaseModel):
    """Single exported message in result.json format."""

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
    from_id: Optional[Dict[str, Any]] = Field(None, description="Sender ID info")
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


class ExportResponse(BaseModel):
    """Response from export worker to Java API."""

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


class QueueJob(BaseModel):
    """Job from Redis queue."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    id: str = Field(..., description="Job ID")
    func: str = Field(..., description="Function to call")
    args: tuple = Field(default_factory=tuple)
    kwargs: dict = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.now)
