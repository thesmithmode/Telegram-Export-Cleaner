"""
Java Bot API Client: HTTP communication with Java backend.

Handles:
- POST responses back to Java Bot API
- Retry logic with exponential backoff
- Request/response validation
- Error handling and logging
"""

import logging
import asyncio
from typing import Optional, Dict, Any
from datetime import datetime, timedelta

import httpx
from pydantic import ValidationError

from config import settings
from models import ExportResponse, ExportedMessage

logger = logging.getLogger(__name__)


class JavaBotClient:
    """HTTP client for Java Bot API communication."""

    def __init__(self, timeout: int = 30, max_retries: int = 3):
        """
        Initialize Java Bot API client.

        Args:
            timeout: Request timeout in seconds
            max_retries: Maximum retry attempts
        """
        self.base_url = settings.JAVA_API_BASE_URL
        self.api_key = settings.JAVA_API_KEY
        self.timeout = timeout
        self.max_retries = max_retries

        logger.info(f"Java Bot Client initialized (URL: {self.base_url})")

    async def send_response(
        self,
        task_id: str,
        status: str,
        messages: list[ExportedMessage],
        error: Optional[str] = None,
        error_code: Optional[str] = None,
    ) -> bool:
        """
        Send export response back to Java Bot.

        Args:
            task_id: Original task ID from queue
            status: "completed", "failed", or "in_progress"
            messages: List of exported messages
            error: Error message if status is failed
            error_code: Error code for categorization

        Returns:
            True if sent successfully, False otherwise
        """
        # Create response object
        response = ExportResponse(
            task_id=task_id,
            status=status,
            message_count=len(messages),
            messages=messages,
            error=error,
            error_code=error_code,
            exported_at=datetime.now().isoformat(),
        )

        # Prepare request
        url = f"{self.base_url}/api/export/callback"
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        try:
            response_json = response.model_dump(exclude_none=True)

        except ValidationError as e:
            logger.error(f"❌ Response validation error: {e}")
            return False

        # Send with retries
        return await self._send_with_retry(url, headers, response_json)

    async def _send_with_retry(
        self,
        url: str,
        headers: Dict[str, str],
        data: Dict[str, Any],
    ) -> bool:
        """
        Send HTTP POST with exponential backoff retry.

        Args:
            url: Full URL to POST to
            headers: HTTP headers
            data: JSON payload

        Returns:
            True if successful, False otherwise
        """
        retry_count = 0
        base_delay = settings.RETRY_BASE_DELAY

        while retry_count <= self.max_retries:
            try:
                async with httpx.AsyncClient(timeout=self.timeout) as client:
                    response = await client.post(url, json=data, headers=headers)

                    if response.status_code == 200:
                        logger.info(f"✅ Response sent to Java Bot (task: {data.get('task_id')})")
                        return True

                    elif response.status_code == 401:
                        logger.error(f"❌ Authentication failed (401). Check JAVA_API_KEY")
                        return False

                    elif response.status_code == 404:
                        logger.error(f"❌ API endpoint not found (404): {url}")
                        return False

                    elif response.status_code >= 500:
                        # Server error - retry
                        logger.warning(
                            f"Server error ({response.status_code}). "
                            f"Retry {retry_count + 1}/{self.max_retries}"
                        )

                    else:
                        logger.error(
                            f"❌ Unexpected response ({response.status_code}): "
                            f"{response.text[:200]}"
                        )
                        return False

            except httpx.TimeoutException:
                logger.warning(
                    f"Request timeout. Retry {retry_count + 1}/{self.max_retries}"
                )

            except httpx.ConnectError as e:
                logger.warning(
                    f"Connection error: {e}. Retry {retry_count + 1}/{self.max_retries}"
                )

            except Exception as e:
                logger.error(f"Unexpected error during request: {e}", exc_info=True)
                return False

            # Exponential backoff
            if retry_count < self.max_retries:
                wait_time = min(
                    base_delay * (2 ** retry_count),
                    settings.RETRY_MAX_DELAY
                )
                logger.debug(f"Waiting {wait_time}s before retry...")
                await asyncio.sleep(wait_time)

            retry_count += 1

        logger.error(
            f"❌ Failed to send response after {self.max_retries} retries"
        )
        return False

    async def verify_connectivity(self) -> bool:
        """
        Check if Java Bot API is accessible.

        Returns:
            True if accessible, False otherwise
        """
        url = f"{self.base_url}/api/health"

        try:
            async with httpx.AsyncClient(timeout=5) as client:
                response = await client.get(url)
                return response.status_code == 200

        except Exception as e:
            logger.error(f"Failed to connect to Java Bot API: {e}")
            return False


async def create_java_client() -> JavaBotClient:
    """
    Factory function to create and verify Java Bot client.

    Returns:
        JavaBotClient instance

    Raises:
        RuntimeError: If API is not accessible
    """
    client = JavaBotClient()

    if not await client.verify_connectivity():
        raise RuntimeError(
            f"Cannot reach Java Bot API at {settings.JAVA_API_BASE_URL}"
        )

    return client
