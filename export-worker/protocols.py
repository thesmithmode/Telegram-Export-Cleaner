"""
Type hints (Protocols) for dependency injection and type checking.

Protocols define duck-typing interfaces for worker components.
Allows replacing implementations without changing caller code.
"""

from typing import Protocol, AsyncGenerator, Optional, Tuple, Any
from datetime import datetime

from models import ExportedMessage, ExportRequest


class TelegramClientProtocol(Protocol):
    """Protocol for Telegram client (exported by PyrogramClient)."""

    async def connect(self) -> bool:
        """
        Connect to Telegram API.

        Returns:
            True if connected successfully, False otherwise.
        """
        ...

    async def disconnect(self):
        """Disconnect from Telegram API."""
        ...

    async def verify_and_get_info(self, chat_id: int) -> Tuple[bool, Optional[dict]]:
        """
        Verify access to chat and get chat metadata.

        Args:
            chat_id: Target chat ID

        Returns:
            (is_accessible, chat_info) where:
            - is_accessible: bool indicating if chat is accessible
            - chat_info: dict with title, type, etc. or None if not accessible
        """
        ...

    async def get_chat_history(
        self,
        chat_id: int,
        limit: int = 0,
        offset_id: int = 0,
        from_date: Optional[datetime] = None,
        to_date: Optional[datetime] = None,
    ) -> AsyncGenerator[ExportedMessage, None]:
        """
        Export chat history.

        Args:
            chat_id: Target chat ID
            limit: Max messages (0 = all)
            offset_id: Start from message ID
            from_date: Filter from date (optional)
            to_date: Filter to date (optional)

        Yields:
            ExportedMessage objects one by one
        """
        ...


class QueueConsumerProtocol(Protocol):
    """Protocol for Redis queue consumer."""

    async def connect(self) -> bool:
        """
        Connect to Redis.

        Returns:
            True if successful, False otherwise.
        """
        ...

    async def disconnect(self):
        """Disconnect from Redis."""
        ...

    async def get_job(self) -> Optional[ExportRequest]:
        """
        Get next job from queue (blocking).

        Returns:
            ExportRequest or None if connection error
        """
        ...

    async def mark_job_processing(self, task_id: str):
        """Mark job as being processed."""
        ...

    async def mark_job_completed(self, task_id: str):
        """Mark job as completed successfully."""
        ...

    async def mark_job_failed(self, task_id: str, error: str):
        """Mark job as failed with error message."""
        ...


class JavaClientProtocol(Protocol):
    """Protocol for Java Bot API client."""

    async def send_response(
        self,
        task_id: str,
        status: str,
        messages: list[ExportedMessage],
        user_chat_id: int,
        error: Optional[str] = None,
        error_code: Optional[str] = None,
    ) -> bool:
        """
        Send export result to Java Bot API.

        Args:
            task_id: Job ID
            status: "completed" or "failed"
            messages: Exported messages
            user_chat_id: Telegram chat for delivery
            error: Error message (if status="failed")
            error_code: Error code (if status="failed")

        Returns:
            True if sent successfully, False otherwise
        """
        ...

    async def aclose(self):
        """Close HTTP session gracefully."""
        ...
