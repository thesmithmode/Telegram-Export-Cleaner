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
from typing import List, Optional, AsyncGenerator
from pathlib import Path
from datetime import datetime, timedelta

from pyrogram import Client, types as pyrogram_types
from pyrogram.errors import FloodWait, Unauthorized, BadRequest, ChannelPrivate, ChatAdminRequired

from config import settings
from json_converter import MessageConverter
from models import ExportedMessage

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
        chat_id: int,
        limit: int = 0,
        offset_id: int = 0,
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
            offset_id: Start from message ID
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

    async def verify_and_get_info(self, chat_id: int) -> tuple[bool, Optional[dict]]:
        """
        Check access and get chat info in single API call.

        FALLBACK CACHE SYNC: Pyrogram caches entity access_hash locally. If you
        pass a raw numeric ID that isn't cached, get_chat() fails with
        PeerIdInvalidError. This is the most common production error when users
        copy-paste chat IDs. Solution: call get_dialogs() to sync the cache,
        then retry get_chat().

        Args:
            chat_id: Telegram chat ID

        Returns:
            Tuple of (is_accessible, chat_info_dict_or_None)
        """
        if not self.is_connected:
            return (False, None)

        try:
            chat = await self.client.get_chat(chat_id)

            info = {
                "id": chat.id,
                "title": chat.title or "",
                "username": chat.username or "",
                "type": str(chat.type),
                "is_bot": chat.is_bot,
                "is_self": chat.is_self,
                "is_contact": chat.is_contact,
                "members_count": chat.members_count or 0,
                "description": chat.description or "",
            }
            return (True, info)

        except BadRequest as e:
            # Common case: numeric ID not in cache. Sync dialogs and retry.
            if "Could not find the input entity" in str(e) or "PeerIdInvalid" in str(e):
                logger.warning(
                    f"Chat {chat_id} not in cache. Syncing dialog list and retrying..."
                )
                try:
                    # Sync user's full dialog list to populate access_hash cache
                    await self.client.get_dialogs()

                    # Retry get_chat
                    chat = await self.client.get_chat(chat_id)
                    info = {
                        "id": chat.id,
                        "title": chat.title or "",
                        "username": chat.username or "",
                        "type": str(chat.type),
                        "is_bot": chat.is_bot,
                        "is_self": chat.is_self,
                        "is_contact": chat.is_contact,
                        "members_count": chat.members_count or 0,
                        "description": chat.description or "",
                    }
                    logger.info(f"✅ Successfully resolved chat {chat_id} after cache sync")
                    return (True, info)

                except Exception as retry_error:
                    logger.error(f"Cache sync retry failed for chat {chat_id}: {retry_error}")
                    return (False, None)
            else:
                logger.error(f"Cannot access chat {chat_id}: {e}")
                return (False, None)

        except (ChannelPrivate, ChatAdminRequired):
            logger.error(f"Cannot access chat {chat_id}")
            return (False, None)

        except Exception as e:
            logger.error(f"Error accessing chat {chat_id}: {e}")
            return (False, None)

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
