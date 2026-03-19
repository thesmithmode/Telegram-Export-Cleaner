"""
Protocols (abstract interfaces) for Export Worker.

Defines contracts for dependency injection and testing.
"""

from typing import Protocol, AsyncIterator, Optional, List
from typing_extensions import runtime_checkable

from models import ExportRequest, ExportedMessage


@runtime_checkable
class JobQueueConsumer(Protocol):
    """
    Protocol for job queue consumer.
    
    Defines contract for retrieving export jobs from queue.
    """
    
    async def get_job(self) -> Optional[ExportRequest]:
        """Get next job from queue (blocking)."""
        ...
    
    async def mark_job_processing(self, task_id: str) -> bool:
        """Mark job as being processed."""
        ...
    
    async def mark_job_completed(self, task_id: str) -> bool:
        """Mark job as completed."""
        ...
    
    async def mark_job_failed(self, task_id: str, error: str) -> bool:
        """Mark job as failed."""
        ...
    
    async def disconnect(self) -> None:
        """Disconnect from queue."""
        ...


@runtime_checkable  
class TelegramClientProtocol(Protocol):
    """
    Protocol for Telegram client.
    
    Defines contract for Telegram API operations.
    """
    
    async def get_chat_history(
        self,
        chat_id: int,
        limit: int = 0,
        offset_id: int = 0,
    ) -> AsyncIterator[ExportedMessage]:
        """Get chat message history."""
        ...
    
    async def verify_and_get_info(self, chat_id: int) -> tuple[bool, Optional[dict]]:
        """Check access and get chat info."""
        ...
    
    async def disconnect(self) -> None:
        """Disconnect from Telegram."""
        ...


@runtime_checkable
class ResultDeliveryClient(Protocol):
    """
    Protocol for result delivery.
    
    Defines contract for delivering processed results to user.
    """
    
    async def send_response(
        self,
        task_id: str,
        status: str,
        messages: List[ExportedMessage],
        error: Optional[str] = None,
        error_code: Optional[str] = None,
        user_chat_id: Optional[int] = None,
    ) -> bool:
        """Send export result to user."""
        ...
    
    async def aclose(self) -> None:
        """Close client connections."""
        ...


@runtime_checkable
class JavaApiClient(Protocol):
    """
    Protocol for Java API client.
    
    Defines contract for communication with Java backend.
    """
    
    async def _upload_to_java(self, result_json_bytes: bytes) -> Optional[str]:
        """Upload result.json to Java API."""
        ...
    
    async def verify_connectivity(self) -> bool:
        """Check if Java API is accessible."""
        ...
