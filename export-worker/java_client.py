"""
Java API Client: Upload result.json and deliver cleaned text to user.

Flow:
1. Convert exported messages to result.json format
2. POST multipart to Java /api/convert → get cleaned markdown text
3. Send the cleaned text file to user via Telegram Bot API

Java API endpoints used:
- POST /api/convert  - upload result.json, returns cleaned text/plain
- GET  /api/health   - connectivity check
"""

import json
import logging
import asyncio
from typing import Optional

import httpx

from config import settings
from models import ExportedMessage

logger = logging.getLogger(__name__)


class JavaBotClient:
    """Uploads exported messages to Java API and delivers result to user."""

    def __init__(self, timeout: int = 30, max_retries: int = 3):
        self.base_url = settings.JAVA_API_BASE_URL.rstrip("/")
        self.timeout = timeout
        self.max_retries = max_retries
        self.bot_token = settings.TELEGRAM_BOT_TOKEN
        self._http_client = httpx.AsyncClient(timeout=self.timeout)
        logger.info(f"Java API Client initialized (URL: {self.base_url})")

    async def send_response(
        self,
        task_id: str,
        status: str,
        messages: list[ExportedMessage],
        error: Optional[str] = None,
        error_code: Optional[str] = None,
        user_chat_id: Optional[int] = None,
    ) -> bool:
        """
        Process export result:
        - On failure: log the error (nothing to send to user)
        - On success with messages: upload to Java, deliver cleaned text to user

        Returns True if the job finished cleanly (even partial/failed),
        False only on unexpected processing errors.
        """
        if status == "failed" or not messages:
            if error:
                logger.warning(
                    f"Task {task_id} ended with status={status}: {error} [{error_code}]"
                )
                # Notify user about failure if we know their chat
                if user_chat_id and self.bot_token:
                    await self._notify_user_failure(user_chat_id, task_id, error)
            return True

        # Build result.json payload (Telegram Desktop export format)
        result_json = self._build_result_json(messages)
        result_bytes = json.dumps(result_json, ensure_ascii=False).encode("utf-8")

        # Upload to Java /api/convert and get cleaned text
        cleaned_text = await self._upload_to_java(result_bytes)

        if cleaned_text is None:
            logger.error(f"❌ Java API processing failed for task {task_id}")
            if user_chat_id and self.bot_token:
                await self._notify_user_failure(
                    user_chat_id, task_id, "Processing service unavailable"
                )
            return False

        logger.info(
            f"✅ Java processed task {task_id}: "
            f"{len(messages)} messages → {len(cleaned_text)} chars"
        )

        # Deliver cleaned text to user via Telegram Bot API
        if user_chat_id and self.bot_token:
            await self._send_file_to_user(user_chat_id, task_id, cleaned_text)
        else:
            logger.warning(
                f"No user_chat_id or bot token — skipping Telegram delivery "
                f"(task {task_id})"
            )

        return True

    def _build_result_json(self, messages: list[ExportedMessage]) -> dict:
        """
        Wrap ExportedMessage list into Telegram Desktop result.json format.

        Java TelegramExporter expects:
        {
          "type": "personal_chat",
          "name": "Export",
          "message_count": 123,
          "messages": [ { "id": ..., "type": "message", "date": ..., "text": ... }, ... ]
        }

        Entity conversion: Python uses offset/length, Java expects type/text.
        This adapter extracts text using offset/length and creates type/text format.
        """
        converted_messages = []
        for msg in messages:
            msg_dict = msg.model_dump(exclude_none=True)

            if msg_dict.get("text_entities"):
                msg_dict["text_entities"] = self._convert_entities_for_java(
                    msg_dict["text"],
                    msg_dict["text_entities"]
                )

            converted_messages.append(msg_dict)

        return {
            "type": "personal_chat",
            "name": "Telegram Export",
            "message_count": len(messages),
            "messages": converted_messages,
        }

    def _convert_entities_for_java(self, text: str, entities: list[dict]) -> list[dict]:
        """
        Convert entities from Python format (offset, length) to Java format (type, text).

        Java MarkdownParser expects: {"type": "bold", "text": "hello"}
        Python provides: {"type": "bold", "offset": 0, "length": 5}
        """
        if not text or not entities:
            return entities

        converted = []
        for entity in entities:
            offset = entity.get("offset", 0)
            length = entity.get("length", 0)

            try:
                entity_text = text[offset:offset + length]
            except (IndexError, TypeError):
                entity_text = ""

            converted_entity = {
                "type": entity.get("type", "plain"),
                "text": entity_text
            }

            if entity.get("url"):
                converted_entity["href"] = entity["url"]
            if entity.get("user_id"):
                converted_entity["user_id"] = entity["user_id"]

            converted.append(converted_entity)

        return converted

    async def _upload_to_java(self, result_json_bytes: bytes) -> Optional[str]:
        """
        POST result.json to /api/convert as multipart, return cleaned text.

        Returns cleaned text string on success, None on failure.
        """
        url = f"{self.base_url}/api/convert"
        retry_count = 0
        base_delay = settings.RETRY_BASE_DELAY

        while retry_count <= self.max_retries:
            try:
                response = await self._http_client.post(
                    url,
                    files={"file": ("result.json", result_json_bytes, "application/json")},
                )

                if response.status_code == 200:
                    return response.text

                elif response.status_code == 400:
                    logger.error(
                        f"❌ Java API rejected payload (400): {response.text[:200]}"
                    )
                    return None  # Bad data — no point retrying

                elif response.status_code == 401:
                    logger.error("❌ Java API authentication failed (401)")
                    return None

                elif response.status_code >= 500:
                    logger.warning(
                        f"Java API server error ({response.status_code}). "
                        f"Retry {retry_count + 1}/{self.max_retries}"
                    )

                else:
                    logger.error(
                        f"❌ Java API unexpected response ({response.status_code}): "
                        f"{response.text[:200]}"
                    )
                    return None

            except httpx.TimeoutException:
                logger.warning(
                    f"Java API timeout. Retry {retry_count + 1}/{self.max_retries}"
                )

            except httpx.ConnectError as e:
                logger.warning(
                    f"Java API connection error: {e}. "
                    f"Retry {retry_count + 1}/{self.max_retries}"
                )

            except Exception as e:
                logger.error(f"Unexpected error calling Java API: {e}", exc_info=True)
                return None

            # Exponential backoff before retry
            if retry_count < self.max_retries:
                wait = min(
                    base_delay * (2 ** retry_count),
                    settings.RETRY_MAX_DELAY,
                )
                logger.debug(f"Waiting {wait}s before retry...")
                await asyncio.sleep(wait)

            retry_count += 1

        logger.error(f"❌ Java API failed after {self.max_retries} retries")
        return None

    async def _send_file_to_user(
        self, user_chat_id: int, task_id: str, cleaned_text: str
    ) -> bool:
        """Send cleaned text as a .txt file to user via Telegram Bot API."""
        url = f"https://api.telegram.org/bot{self.bot_token}/sendDocument"
        filename = f"export_{task_id}.txt"

        try:
            response = await self._http_client.post(
                url,
                data={
                    "chat_id": user_chat_id,
                    "caption": f"✅ Export complete ({task_id})",
                },
                files={
                    "document": (filename, cleaned_text.encode("utf-8"), "text/plain")
                },
            )

            if response.status_code == 200:
                logger.info(
                    f"✅ Sent export file to user {user_chat_id} (task {task_id})"
                )
                return True
            else:
                logger.error(
                    f"❌ Telegram sendDocument failed ({response.status_code}): "
                    f"{response.text[:200]}"
                )
                return False

        except Exception as e:
            logger.error(f"Error sending file to user: {e}", exc_info=True)
            return False

    async def _notify_user_failure(
        self, user_chat_id: int, task_id: str, error: str
    ) -> None:
        """Send failure notification to user via Telegram Bot API."""
        url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
        text = f"❌ Export failed (task {task_id})\n\nReason: {error}"

        try:
            await self._http_client.post(url, data={"chat_id": user_chat_id, "text": text})
        except Exception as e:
            logger.warning(f"Could not notify user of failure: {e}")

    async def aclose(self):
        """Close HTTP client connection."""
        try:
            await self._http_client.aclose()
        except Exception as e:
            logger.warning(f"Error closing HTTP client: {e}")

    async def verify_connectivity(self) -> bool:
        """Check if Java API is accessible."""
        url = f"{self.base_url}/api/health"
        try:
            response = await self._http_client.get(url)
            return response.status_code == 200
        except Exception as e:
            logger.error(f"Failed to connect to Java API: {e}")
            return False


async def create_java_client() -> JavaBotClient:
    """
    Factory: create and verify Java API client.

    Raises RuntimeError if Java API is not accessible.
    """
    client = JavaBotClient()

    if not await client.verify_connectivity():
        raise RuntimeError(
            f"Cannot reach Java API at {settings.JAVA_API_BASE_URL}"
        )

    return client
