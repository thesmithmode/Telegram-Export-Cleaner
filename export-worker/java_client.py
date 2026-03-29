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

    def __init__(self, timeout: int = 1800, max_retries: int = 3):
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
            sent = await self._send_file_to_user(user_chat_id, task_id, cleaned_text)
            if not sent:
                await self._notify_user_failure(
                    user_chat_id, task_id,
                    "Не удалось отправить файл. Попробуйте снова."
                )
                return False
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

        Transforms text_entities from Telegram Bot API format (offset/length)
        to Telegram Desktop export format (type/text) so Java MarkdownParser can render them.
        """
        transformed_messages = []

        for msg in messages:
            msg_dict = msg.model_dump(exclude_none=True)

            # Transform text_entities from Bot API format to Desktop export format
            if msg_dict.get("text_entities"):
                msg_dict["text_entities"] = self._transform_entities(
                    msg_dict["text"],
                    msg_dict["text_entities"]
                )

            transformed_messages.append(msg_dict)

        return {
            "type": "personal_chat",
            "name": "Telegram Export",
            "message_count": len(messages),
            "messages": transformed_messages,
        }

    @staticmethod
    def _transform_entities(text: str, entities: list[dict]) -> list[dict]:
        """
        Transform text_entities from Telegram Bot API format (offset/length)
        to Telegram Desktop export format (type/text).

        CRITICAL: Telegram Bot API offsets are in UTF-16 code units, but Python
        strings use Unicode code points (UTF-32). For messages with emoji or
        characters outside the Basic Multilingual Plane (U+FFFF), direct slicing
        text[offset:offset+length] produces misaligned text.

        Solution: Encode text to UTF-16-LE, extract by byte offsets (offset*2 to
        (offset+length)*2), decode back to Unicode.

        Args:
            text: Full message text
            entities: List of entities with {type, offset, length, ...}

        Returns:
            List of entities with {type, text, ...} suitable for Java MarkdownParser
        """
        if not entities or not text:
            return entities

        transformed = []
        try:
            # Pre-encode text to UTF-16-LE for all entity extractions
            text_utf16 = text.encode('utf-16-le')

            for entity in entities:
                offset = entity.get("offset", 0)
                length = entity.get("length", 0)

                # Extract entity text using UTF-16 byte offsets
                # Telegram offset/length are in UTF-16 code units, not bytes
                entity_bytes = text_utf16[offset * 2 : (offset + length) * 2]
                entity_text = entity_bytes.decode('utf-16-le')

                # Create new entity dict with type + text instead of offset + length
                new_entity = {
                    "type": entity.get("type", "plain"),
                    "text": entity_text
                }

                # Preserve additional fields (url, user_id, etc)
                if "url" in entity:
                    new_entity["href"] = entity["url"]  # Desktop format uses "href"
                if "user_id" in entity:
                    new_entity["user_id"] = entity["user_id"]

                transformed.append(new_entity)

        except Exception as e:
            logger.warning(f"Error transforming entities: {e}. Falling back to original format.")
            return entities

        return transformed if transformed else entities

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

    # Telegram Bot API file size limit: 50MB, use 45MB as safe threshold
    MAX_FILE_SIZE_BYTES = 45 * 1024 * 1024

    async def _send_file_to_user(
        self, user_chat_id: int, task_id: str, cleaned_text: str
    ) -> bool:
        """Send cleaned text as .txt file(s) to user via Telegram Bot API.

        If the file exceeds 45MB, splits into multiple parts by lines.
        """
        text_bytes = cleaned_text.encode("utf-8")
        file_size = len(text_bytes)

        if file_size <= self.MAX_FILE_SIZE_BYTES:
            return await self._send_single_file(
                user_chat_id, task_id, text_bytes,
                f"export_{task_id}.txt",
                f"✅ Экспорт завершён ({task_id})",
            )

        # Split into parts
        logger.info(
            f"File size {file_size / 1024 / 1024:.1f}MB exceeds limit, splitting..."
        )
        parts = self._split_text_by_size(cleaned_text, self.MAX_FILE_SIZE_BYTES)
        total_parts = len(parts)

        for i, part in enumerate(parts, 1):
            part_bytes = part.encode("utf-8")
            filename = f"export_{task_id}_part{i}.txt"
            caption = f"✅ Экспорт завершён — часть {i}/{total_parts}"
            success = await self._send_single_file(
                user_chat_id, task_id, part_bytes, filename, caption
            )
            if not success:
                logger.error(f"Failed to send part {i}/{total_parts}")
                return False

        logger.info(f"✅ Sent {total_parts} parts to user {user_chat_id}")
        return True

    async def _send_single_file(
        self, user_chat_id: int, task_id: str,
        file_bytes: bytes, filename: str, caption: str
    ) -> bool:
        """Send a single file via Telegram Bot API sendDocument."""
        url = f"https://api.telegram.org/bot{self.bot_token}/sendDocument"

        try:
            response = await self._http_client.post(
                url,
                data={"chat_id": user_chat_id, "caption": caption},
                files={"document": (filename, file_bytes, "text/plain")},
            )

            if response.status_code == 200:
                logger.info(
                    f"✅ Sent {filename} to user {user_chat_id} (task {task_id})"
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

    @staticmethod
    def _split_text_by_size(text: str, max_bytes: int) -> list[str]:
        """Split text into parts, each under max_bytes when UTF-8 encoded.

        Splits on line boundaries to avoid breaking messages.
        """
        parts = []
        lines = text.split("\n")
        current_part: list[str] = []
        current_size = 0

        for line in lines:
            line_bytes = len(line.encode("utf-8")) + 1  # +1 for \n
            if current_size + line_bytes > max_bytes and current_part:
                parts.append("\n".join(current_part))
                current_part = []
                current_size = 0
            current_part.append(line)
            current_size += line_bytes

        if current_part:
            parts.append("\n".join(current_part))

        return parts

    async def send_progress_update(
        self,
        user_chat_id: int,
        task_id: str,
        message_count: int,
        total: Optional[int] = None,
        started: bool = False,
    ) -> bool:
        """
        Send progress update to user during long export.

        Args:
            user_chat_id: User's Telegram chat ID
            task_id: Export task ID for reference
            message_count: Number of messages exported so far
            total: Total messages expected (if known)
            started: True if this is the initial "started" notification

        Returns:
            True if message sent, False otherwise
        """
        url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"

        if started:
            if total:
                text = f"⏳ Экспорт начался — будет обработано до {total} сообщений"
            else:
                text = "⏳ Экспорт начался, ожидайте..."
        elif total:
            percentage = message_count * 100 // total
            progress_bar = self._build_progress_bar(percentage)
            text = f"📊 {progress_bar} {percentage}% ({message_count}/{total})"
        else:
            text = f"📊 Экспортировано {message_count} сообщений..."

        try:
            response = await self._http_client.post(
                url, data={"chat_id": user_chat_id, "text": text}
            )
            return response.status_code == 200
        except Exception as e:
            logger.warning(f"Could not send progress update to user: {e}")
            return False

    @staticmethod
    def _build_progress_bar(percentage: int, width: int = 10) -> str:
        """Build a text progress bar for display."""
        filled = int(width * percentage / 100)
        empty = width - filled
        return "▓" * filled + "░" * empty

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
