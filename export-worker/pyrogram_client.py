"""
Pyrogram Client: Async Telegram API wrapper with session management.

Handles:
- Authentication (2FA support)
- Chat message export
- Exponential backoff + retry logic for rate limiting (FloodWait)
- Session persistence in Docker volume
- Graceful shutdown
"""

import asyncio
import logging
import os
from typing import List, Optional, AsyncGenerator, Union
from pathlib import Path
from datetime import datetime, timedelta

from pyrogram import Client, types as pyrogram_types
from pyrogram.errors import (
    FloodWait, Unauthorized, BadRequest, ChannelPrivate, ChatAdminRequired,
    UserDeactivated, AuthKeyUnregistered, SessionExpired, PeerFlood,
)

from config import settings
from json_converter import MessageConverter
from models import ExportedMessage

try:
    import redis.asyncio as redis
except ImportError:
    redis = None  # type: ignore

logger = logging.getLogger(__name__)


class TelegramClient:
    """Async Telegram client for message export."""

    def __init__(self):
        """Initialize Telegram client with Pyrogram."""
        self.session_path = Path("session")
        self.session_path.mkdir(exist_ok=True)

        # Create Pyrogram client
        # Production: use string session from env for stateless auth
        # Development: use file-based session with phone number
        if settings.TELEGRAM_SESSION_STRING:
            logger.info("Using Pyrogram string session (stateless auth)")
            self.client = Client(
                name="export_worker",
                session_string=settings.TELEGRAM_SESSION_STRING,
                api_id=settings.TELEGRAM_API_ID,
                api_hash=settings.TELEGRAM_API_HASH,
                workers=settings.MAX_WORKERS,
            )
        else:
            logger.info("Using Pyrogram file-based session (requires first-time auth)")
            self.client = Client(
                name=settings.SESSION_NAME,
                api_id=settings.TELEGRAM_API_ID,
                api_hash=settings.TELEGRAM_API_HASH,
                workdir=str(self.session_path),
                phone_number=settings.TELEGRAM_PHONE_NUMBER,
                workers=settings.MAX_WORKERS,
            )

        self.is_connected = False
        self.redis_client: Optional[redis.Redis] = None  # type: ignore
        logger.info(f"Pyrogram client initialized (session: {settings.SESSION_NAME})")

    async def connect(self) -> bool:
        """
        Connect to Telegram API.

        Returns:
            True if connected successfully, False otherwise.
        """
        try:
            if self.is_connected:
                logger.debug("Already connected to Telegram")
                return True

            logger.info("Connecting to Telegram API...")
            await self.client.start()
            self.is_connected = True

            # Get user info for verification
            me = await self.client.get_me()
            logger.info(f"✅ Connected as {me.first_name} (@{me.username})")

            return True

        except Unauthorized as e:
            logger.error(f"❌ Authorization failed: {e}")
            logger.error("Please check TELEGRAM_API_ID, TELEGRAM_API_HASH, and TELEGRAM_PHONE")
            return False

        except Exception as e:
            logger.error(f"❌ Connection failed: {e}", exc_info=True)
            return False

    async def disconnect(self):
        """Disconnect from Telegram API."""
        try:
            if self.is_connected and self.client.is_connected:
                logger.info("Disconnecting from Telegram...")
                await self.client.stop()
                self.is_connected = False
                logger.info("✅ Disconnected")
        except Exception as e:
            logger.error(f"Error during disconnect: {e}")

    async def get_chat_history(
        self,
        chat_id: Union[int, str],
        limit: int = 0,
        offset_id: int = 0,
        min_id: int = 0,
        from_date: Optional[datetime] = None,
        to_date: Optional[datetime] = None,
    ) -> AsyncGenerator[ExportedMessage, None]:
        """
        Get chat message history with exponential backoff for rate limiting.

        DEDUPLICATION NOTE: When FloodWait occurs, we restart the iterator from
        last_offset_id. Since Pyrogram iterates newest-to-oldest and we update
        last_offset_id on every message, restarting can re-yield previously seen
        messages. We track seen_message_ids to deduplicate.

        Args:
            chat_id: Telegram chat ID
            limit: Max messages (0 = all)
            offset_id: Start from message ID (pagination, fetches messages OLDER than this)
            min_id: Stop when message.id <= min_id (for incremental: fetch only NEW messages)
            from_date: Filter messages from date
            to_date: Filter messages to date

        Yields:
            ExportedMessage objects (no duplicates guaranteed)

        Raises:
            BadRequest: Invalid chat ID or access denied
            ChannelPrivate: Private channel
            ChatAdminRequired: Need admin rights
        """
        if not self.is_connected:
            raise RuntimeError("Not connected to Telegram")

        try:
            logger.info(f"Fetching history for chat {chat_id} (limit: {limit})")

            message_count = 0
            last_offset_id = offset_id
            max_retries = settings.MAX_RETRIES
            seen_message_ids: set[int] = set()  # Track seen messages to avoid FloodWait dups

            while True:
                retry_count = 0

                try:
                    async for message in self.client.get_chat_history(
                        chat_id=chat_id,
                        limit=limit,
                        offset_id=last_offset_id,
                    ):
                        try:
                            # Incremental export: stop when we reach already-exported messages
                            if min_id and message.id <= min_id:
                                logger.debug(f"Reached already-exported message {message.id} (min_id={min_id}), stopping")
                                return

                            # Skip duplicates from FloodWait retry
                            if message.id in seen_message_ids:
                                logger.debug(f"Skipping duplicate message {message.id}")
                                continue

                            # Date filtering
                            if from_date and message.date < from_date:
                                continue
                            if to_date and message.date > to_date:
                                continue

                            # Track this message ID and update last offset for restart-on-FloodWait
                            seen_message_ids.add(message.id)
                            last_offset_id = message.id

                            # Convert to export format
                            exported = MessageConverter.convert_message(message)
                            yield exported

                            message_count += 1

                            if message_count % 100 == 0:
                                logger.debug(f"Exported {message_count} messages...")

                        except Exception as e:
                            logger.error(f"Error processing message {message.id}: {e}")
                            # Skip problematic message and continue
                            continue

                    # Iterator exhausted successfully
                    break

                except FloodWait as e:
                    # Rate limited by Telegram API - respect their wait time
                    if retry_count >= max_retries:
                        logger.error(
                            f"Max retries ({max_retries}) exceeded due to rate limiting"
                        )
                        raise

                    # Use Telegram's suggested wait as minimum
                    wait_time = min(
                        max(e.value, settings.RETRY_BASE_DELAY * (2 ** retry_count)),
                        settings.RETRY_MAX_DELAY
                    )

                    retry_count += 1
                    logger.warning(
                        f"Rate limited (FloodWait {e.value}s). "
                        f"Retry {retry_count}/{max_retries} after {wait_time}s. "
                        f"Will resume from message {last_offset_id} "
                        f"(have seen {len(seen_message_ids)} unique messages)"
                    )

                    await asyncio.sleep(wait_time)
                    # Restart get_chat_history from last_offset_id with deduplication active

            logger.info(f"✅ Exported {message_count} messages from chat {chat_id}")

        except ChannelPrivate:
            logger.error(f"❌ Channel {chat_id} is private")
            raise

        except ChatAdminRequired:
            logger.error(f"❌ Admin rights required for chat {chat_id}")
            raise

        except BadRequest as e:
            logger.error(f"❌ Invalid request for chat {chat_id}: {e}")
            raise

        except Exception as e:
            logger.error(f"❌ Error fetching history for chat {chat_id}: {e}", exc_info=True)
            raise

    async def verify_and_get_info(self, chat_id: Union[int, str]) -> tuple[bool, Optional[dict], Optional[str]]:
        """
        Check access and get chat info in single API call.

        FALLBACK CACHE SYNC: For numeric IDs, Pyrogram caches entity access_hash
        locally. If the ID isn't cached, get_chat() fails with PeerIdInvalidError.
        Solution: call get_dialogs() to sync the cache, then retry.
        For string usernames this fallback is skipped — Pyrogram resolves them
        via contacts.resolveUsername API directly.

        Args:
            chat_id: Telegram chat ID or username

        Returns:
            Tuple of (is_accessible, chat_info_dict_or_None, error_reason_or_None)
            error_reason values: CHANNEL_PRIVATE, USERNAME_NOT_FOUND,
            ADMIN_REQUIRED, UNKNOWN
        """
        if not self.is_connected:
            return (False, None, "UNKNOWN")

        try:
            chat = await self.client.get_chat(chat_id)

            info = {
                "id": chat.id,
                "title": getattr(chat, "title", "") or "",
                "username": getattr(chat, "username", "") or "",
                "type": str(chat.type),
                "is_bot": getattr(chat, "is_bot", False),
                "is_self": getattr(chat, "is_self", False),
                "is_contact": getattr(chat, "is_contact", False),
                "members_count": getattr(chat, "members_count", 0) or 0,
                "description": getattr(chat, "description", "") or "",
            }
            return (True, info, None)

        except BadRequest as e:
            error_str = str(e)
            logger.error(
                f"BadRequest for chat {chat_id}: {type(e).__name__}: {error_str}"
            )

            # Username not found
            if "USERNAME_NOT_OCCUPIED" in error_str or "USERNAME_INVALID" in error_str:
                return (False, None, "USERNAME_NOT_FOUND")

            # Numeric ID not in cache — sync dialogs and retry (only for numeric IDs)
            if isinstance(chat_id, int) and (
                "Could not find the input entity" in error_str
                or "PeerIdInvalid" in error_str
            ):
                logger.warning(
                    f"Chat {chat_id} not in cache. Syncing dialog list and retrying..."
                )
                try:
                    await self.client.get_dialogs()
                    chat = await self.client.get_chat(chat_id)
                    info = {
                        "id": chat.id,
                        "title": getattr(chat, "title", "") or "",
                        "username": getattr(chat, "username", "") or "",
                        "type": str(chat.type),
                        "is_bot": getattr(chat, "is_bot", False),
                        "is_self": getattr(chat, "is_self", False),
                        "is_contact": getattr(chat, "is_contact", False),
                        "members_count": getattr(chat, "members_count", 0) or 0,
                        "description": getattr(chat, "description", "") or "",
                    }
                    logger.info(f"✅ Successfully resolved chat {chat_id} after cache sync")
                    return (True, info, None)

                except Exception as retry_error:
                    logger.error(f"Cache sync retry failed for chat {chat_id}: {retry_error}")
                    return (False, None, "CHAT_NOT_ACCESSIBLE")

            return (False, None, "CHAT_NOT_ACCESSIBLE")

        except ChannelPrivate:
            logger.error(f"❌ Channel {chat_id} is private")
            return (False, None, "CHANNEL_PRIVATE")

        except ChatAdminRequired:
            logger.error(f"❌ Admin rights required for chat {chat_id}")
            return (False, None, "ADMIN_REQUIRED")

        except (Unauthorized, UserDeactivated, AuthKeyUnregistered, SessionExpired) as e:
            logger.error(f"❌ Session/auth error for chat {chat_id}: {type(e).__name__}: {e}")
            return (False, None, "SESSION_INVALID")

        except PeerFlood as e:
            logger.error(f"❌ Account flood-restricted, cannot access chat {chat_id}: {e}")
            return (False, None, "FLOOD_RESTRICTED")

        except Exception as e:
            logger.error(
                f"Error accessing chat {chat_id}: {type(e).__name__}: {e}"
            )
            return (False, None, "UNKNOWN")

    async def set_incremental_state(self, chat_id: Union[int, str], newest_message_id: int, user_id: Union[int, str]) -> None:
        """
        Save incremental export state to Redis for resumption on re-export.

        Stores the NEWEST exported message ID (messages[0]) so next export
        fetches only messages newer than this point.

        Args:
            chat_id: Telegram chat ID
            newest_message_id: ID of the NEWEST exported message
            user_id: Telegram user ID (state is per-user-per-chat)
        """
        if not redis:
            logger.debug("Redis not available, skipping state persistence")
            return

        try:
            if not self.redis_client:
                self.redis_client = redis.Redis(
                    host=settings.REDIS_HOST,
                    port=settings.REDIS_PORT,
                    decode_responses=True,
                )

            key = f"state:export:{user_id}:{chat_id}:last_message_id"
            await self.redis_client.set(key, newest_message_id, ex=30 * 24 * 3600)  # 30 days
            logger.debug(f"Saved incremental state for user {user_id} chat {chat_id}: newest_message_id={newest_message_id}")

        except Exception as e:
            logger.warning(f"Could not save incremental state to Redis: {e}")

    async def get_incremental_state(self, chat_id: Union[int, str], user_id: Union[int, str]) -> Optional[int]:
        """
        Get newest exported message ID for incremental export.

        Args:
            chat_id: Telegram chat ID
            user_id: Telegram user ID (state is per-user-per-chat)

        Returns:
            Newest message ID from last export if available, None otherwise
        """
        if not redis:
            return None

        try:
            if not self.redis_client:
                self.redis_client = redis.Redis(
                    host=settings.REDIS_HOST,
                    port=settings.REDIS_PORT,
                    decode_responses=True,
                )

            key = f"state:export:{user_id}:{chat_id}:last_message_id"
            value = await self.redis_client.get(key)
            if value:
                message_id = int(value)
                logger.info(f"Found incremental state for user {user_id} chat {chat_id}: newest_message_id={message_id}")
                return message_id
            return None

        except Exception as e:
            logger.warning(f"Could not read incremental state from Redis: {e}")
            return None

    async def __aenter__(self):
        """Async context manager entry."""
        await self.connect()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        """Async context manager exit."""
        await self.disconnect()


async def create_client() -> TelegramClient:
    """
    Factory function to create and verify Telegram client.

    Returns:
        Connected TelegramClient instance

    Raises:
        RuntimeError: If connection fails
    """
    client = TelegramClient()

    if not await client.connect():
        raise RuntimeError("Failed to connect to Telegram API")

    return client
