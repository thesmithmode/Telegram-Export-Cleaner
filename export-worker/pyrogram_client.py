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
        self.client = Client(
            name=settings.SESSION_NAME,
            api_id=settings.TELEGRAM_API_ID,
            api_hash=settings.TELEGRAM_API_HASH,
            workdir=str(self.session_path),
            phone_number=settings.TELEGRAM_PHONE,
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

        Args:
            chat_id: Telegram chat ID
            limit: Max messages (0 = all)
            offset_id: Start from message ID
            from_date: Filter messages from date
            to_date: Filter messages to date

        Yields:
            ExportedMessage objects

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
            retry_count = 0
            max_retries = settings.MAX_RETRIES

            async for message in self.client.get_chat_history(
                chat_id=chat_id,
                limit=limit,
                offset_id=offset_id,
            ):
                try:
                    # Date filtering
                    if from_date and message.date < from_date:
                        continue
                    if to_date and message.date > to_date:
                        continue

                    # Convert to export format
                    exported = MessageConverter.convert_message(message)
                    yield exported

                    message_count += 1
                    retry_count = 0  # Reset retry count on success

                    if message_count % 100 == 0:
                        logger.debug(f"Exported {message_count} messages...")

                except FloodWait as e:
                    # Rate limited - exponential backoff
                    if retry_count >= max_retries:
                        logger.error(f"Max retries exceeded for message {message.id}")
                        raise

                    wait_time = min(
                        settings.RETRY_BASE_DELAY * (2 ** retry_count),
                        settings.RETRY_MAX_DELAY
                    )

                    retry_count += 1
                    logger.warning(
                        f"Rate limited (flood wait). Retry {retry_count}/{max_retries} "
                        f"after {wait_time}s"
                    )

                    await asyncio.sleep(wait_time)
                    # Continue with same message after delay

                except Exception as e:
                    logger.error(f"Error processing message {message.id}: {e}")
                    # Skip problematic message and continue
                    continue

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

    async def get_chat_info(self, chat_id: int) -> Optional[dict]:
        """
        Get chat metadata (name, type, members, etc).

        Args:
            chat_id: Telegram chat ID

        Returns:
            Dict with chat info or None if error
        """
        if not self.is_connected:
            raise RuntimeError("Not connected to Telegram")

        try:
            chat = await self.client.get_chat(chat_id)

            return {
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

        except ChannelPrivate:
            logger.error(f"Channel {chat_id} is private")
            return None

        except BadRequest as e:
            logger.error(f"Invalid chat {chat_id}: {e}")
            return None

        except Exception as e:
            logger.error(f"Error getting chat info for {chat_id}: {e}")
            return None

    async def verify_access(self, chat_id: int) -> bool:
        """
        Check if we have access to chat (can read messages).

        Args:
            chat_id: Telegram chat ID

        Returns:
            True if accessible, False otherwise
        """
        if not self.is_connected:
            return False

        try:
            await self.client.get_chat(chat_id)
            return True

        except (ChannelPrivate, ChatAdminRequired, BadRequest):
            return False

        except Exception as e:
            logger.error(f"Error verifying access to {chat_id}: {e}")
            return False

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
