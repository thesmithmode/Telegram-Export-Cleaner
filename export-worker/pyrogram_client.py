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
from datetime import datetime, timedelta, timezone

from pyrogram import Client, types as pyrogram_types
from pyrogram.raw import functions, types as raw_types
from pyrogram.errors import (
    FloodWait, Unauthorized, BadRequest, ChannelPrivate, ChatAdminRequired,
    UserDeactivated, AuthKeyUnregistered, SessionExpired, PeerFlood, PeerIdInvalid,
)

from config import settings
from json_converter import MessageConverter
from models import ExportedMessage

try:
    import redis.asyncio as redis
except ImportError:
    redis = None  # type: ignore

logger = logging.getLogger(__name__)


def ensure_utc(dt: Optional[datetime]) -> Optional[datetime]:
    """Нормализовать datetime к UTC-aware. Pyrogram 2.x возвращает aware UTC,
    поэтому все сравнения дат должны быть aware."""
    if dt is None:
        return None
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt


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

        from_date = ensure_utc(from_date)
        to_date = ensure_utc(to_date)

        try:
            logger.info(f"Fetching history for chat {chat_id} (limit: {limit})")

            message_count = 0
            last_offset_id = offset_id
            max_retries = settings.MAX_RETRIES
            # Dedup set is only needed after a FloodWait retry — Pyrogram's iterator
            # does not yield duplicates during a normal sequential pass.
            seen_message_ids: Optional[set[int]] = None

            retry_count = 0
            while True:
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

                            # Skip duplicates from FloodWait retry (only relevant after retry)
                            if seen_message_ids is not None and message.id in seen_message_ids:
                                logger.debug(f"Skipping duplicate message {message.id}")
                                continue

                            # Date filtering (Pyrogram iterates newest→oldest)
                            if from_date and message.date < from_date:
                                logger.debug(f"Reached message older than from_date ({message.date} < {from_date}), stopping")
                                return
                            if to_date and message.date > to_date:
                                continue

                            # Track this message ID and update last offset for restart-on-FloodWait
                            if seen_message_ids is not None:
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

                    # Create dedup set on first FloodWait
                    if seen_message_ids is None:
                        seen_message_ids = set()

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

    async def get_chat_messages_count(self, chat_id: Union[int, str]) -> Optional[int]:
        """Get total message count in a chat. Returns None on failure."""
        try:
            count = await self.client.get_chat_history_count(chat_id)
            return count if count > 0 else None
        except Exception as e:
            logger.warning(f"Could not get message count for chat {chat_id}: {e}")
            return None

    async def get_date_range_count(
        self,
        chat_id: Union[int, str],
        from_date: datetime,
        to_date: datetime,
    ) -> Optional[int]:
        """
        Get message count in a date range using GetHistory with offset_date.

        Strategy: two lightweight GetHistory calls (limit=1) to get the total
        message count before each boundary date, then subtract.

        count_before_to - count_before_from = messages in [from_date, to_date]

        This is more reliable than messages.Search which may return the total
        chat count regardless of date filters for certain chat types.

        Returns None on failure.
        """
        try:
            peer = await self.client.resolve_peer(chat_id)

            # Count messages with date <= to_date (i.e. offset_date = to_date + 1s)
            result_to = await self.client.invoke(
                functions.messages.GetHistory(
                    peer=peer,
                    offset_id=0,
                    offset_date=int(to_date.timestamp()) + 1,
                    add_offset=0,
                    limit=1,
                    max_id=0,
                    min_id=0,
                    hash=0,
                )
            )
            count_to = getattr(result_to, "count", None)
            if count_to is None:
                return None

            # Count messages with date < from_date (i.e. offset_date = from_date)
            result_from = await self.client.invoke(
                functions.messages.GetHistory(
                    peer=peer,
                    offset_id=0,
                    offset_date=int(from_date.timestamp()),
                    add_offset=0,
                    limit=1,
                    max_id=0,
                    min_id=0,
                    hash=0,
                )
            )
            count_from = getattr(result_from, "count", None)
            if count_from is None:
                return None

            date_range_count = count_to - count_from
            return max(date_range_count, 0) if date_range_count is not None else None
        except Exception as e:
            logger.warning(f"Could not get date range count for chat {chat_id}: {e}")
            return None

    async def get_messages_count(
        self,
        chat_id: Union[int, str],
        from_date: Optional[datetime] = None,
        to_date: Optional[datetime] = None,
    ) -> Optional[int]:
        """
        Get message count — universal method.

        Without dates: fast get_chat_history_count (1 API call).
        With dates: raw MTProto messages.Search with min_date/max_date (1 API call).
        """
        if from_date or to_date:
            effective_from = ensure_utc(from_date) or datetime(2000, 1, 1, tzinfo=timezone.utc)
            effective_to = ensure_utc(to_date) or datetime.now(timezone.utc)
            return await self.get_date_range_count(chat_id, effective_from, effective_to)
        return await self.get_chat_messages_count(chat_id)

    @staticmethod
    def _build_chat_info(chat) -> dict:
        """Extract chat metadata into a dict."""
        return {
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

    async def _resolve_numeric_chat_id(
        self, chat_id: int
    ) -> tuple[bool, Optional[dict], Optional[str]]:
        """
        Resolve a numeric chat_id that is not in the Pyrogram local peer cache.

        Strategy:
          1. Sync dialog list (get_dialogs) — works if worker is a member of the chat.
          2. Raw MTProto channels.GetChannels with access_hash=0 — works for public
             channels even when the worker is not a member.
          3. Redis canonical reverse mapping — lookup username by numeric ID,
             then resolve via contacts.resolveUsername.

        Returns the same (is_accessible, chat_info, error_reason) tuple as verify_and_get_info.
        """
        # Fallback 1: sync dialog list
        logger.warning(f"Chat {chat_id} not in cache. Syncing dialog list and retrying...")
        try:
            async for _ in self.client.get_dialogs():
                pass
            chat = await self.client.get_chat(chat_id)
            logger.info(f"Successfully resolved chat {chat_id} after cache sync")
            chat_info = self._build_chat_info(chat)
            # Сохранить canonical mapping если у чата есть username
            username = chat_info.get("username")
            if username and self.redis_client:
                try:
                    await self.redis_client.set(
                        f"canonical:{chat_id}", username, ex=86400 * 30
                    )
                    logger.info(f"Saved canonical mapping: canonical:{chat_id} → {username}")
                except Exception as redis_err:
                    logger.warning(f"Failed to save canonical mapping: {redis_err}")
            return (True, chat_info, None)

        except Exception as retry_error:
            logger.error(f"Cache sync retry failed for chat {chat_id}: {retry_error}")

        # Fallback 2: raw MTProto с access_hash=0 для публичных каналов.
        # Используем ответ GetChannels напрямую (username из ответа) вместо
        # повторного get_chat(numeric_id) — это надёжнее, т.к. не зависит
        # от сайд-эффекта кэша Pyrogram.
        if chat_id < -1000000000000:
            try:
                channel_id = abs(chat_id) - 1000000000000
                logger.warning(
                    f"Trying raw MTProto access_hash=0 for channel {channel_id}..."
                )
                result = await self.client.invoke(
                    functions.channels.GetChannels(
                        id=[raw_types.InputChannel(
                            channel_id=channel_id, access_hash=0
                        )]
                    )
                )
                # Извлечь username из ответа — надёжнее чем get_chat(numeric_id)
                raw_channel = result.chats[0] if result.chats else None
                raw_username = getattr(raw_channel, "username", None)
                if raw_username:
                    logger.info(f"Got username from GetChannels: @{raw_username}")
                    chat = await self.client.get_chat(raw_username)
                else:
                    # Нет username (приватный канал без имени) — попробуем numeric
                    chat = await self.client.get_chat(chat_id)
                logger.info(f"Resolved public channel {chat_id} via raw MTProto")
                chat_info = self._build_chat_info(chat)
                # Сохранить canonical mapping
                username = chat_info.get("username") or raw_username
                if username and self.redis_client:
                    try:
                        await self.redis_client.set(
                            f"canonical:{chat_id}", username, ex=86400 * 30
                        )
                        logger.info(f"Saved canonical mapping: canonical:{chat_id} → {username}")
                    except Exception as redis_err:
                        logger.warning(f"Failed to save canonical mapping: {redis_err}")
                return (True, chat_info, None)
            except ChannelPrivate:
                logger.error(f"❌ Channel {chat_id} is private (raw MTProto)")
                # Не возвращаем сразу — попробуем fallback 3
            except Exception as raw_error:
                logger.error(
                    f"Raw MTProto fallback failed for channel {chat_id}: {raw_error}"
                )

        # Fallback 3: Redis canonical reverse mapping (username → numeric_id)
        result = await self._resolve_via_canonical_mapping(chat_id)
        if result is not None:
            return result

        return (False, None, "CHAT_NOT_ACCESSIBLE")

    async def _resolve_via_canonical_mapping(
        self, chat_id: int
    ) -> Optional[tuple[bool, Optional[dict], Optional[str]]]:
        """
        Ищет username через Redis canonical mapping и пробует резолвить через Pyrogram.

        Python-воркер сохраняет canonical:<numeric_id> → <username> при каждом
        успешном резолве. Если маппинг найден — пробуем get_chat(username).
        """
        if not self.redis_client:
            return None
        try:
            username = await self.redis_client.get(f"canonical:{chat_id}")
            if not username:
                return None
            if isinstance(username, bytes):
                username = username.decode("utf-8")
            logger.info(
                f"Found canonical mapping for {chat_id}: @{username}, "
                f"attempting resolveUsername..."
            )
            chat = await self.client.get_chat(username)
            logger.info(f"Resolved chat {chat_id} via canonical mapping → @{username}")
            return (True, self._build_chat_info(chat), None)
        except Exception as e:
            logger.warning(
                f"Canonical mapping fallback failed for {chat_id}: {e}"
            )
        return None

    async def verify_and_get_info(self, chat_id: Union[int, str]) -> tuple[bool, Optional[dict], Optional[str]]:
        """
        Check access and get chat info in single API call.

        Args:
            chat_id: Telegram chat ID or username

        Returns:
            Tuple of (is_accessible, chat_info_dict_or_None, error_reason_or_None)
        """
        if not self.is_connected:
            return (False, None, "UNKNOWN")

        try:
            logger.debug(f"Attempting to get chat info for: {chat_id!r} (type: {type(chat_id)})")
            chat = await self.client.get_chat(chat_id)
            return (True, self._build_chat_info(chat), None)

        except (BadRequest, PeerIdInvalid) as e:
            error_str = str(e)
            logger.warning(
                f"BadRequest/PeerIdInvalid for chat {chat_id}: {type(e).__name__}: {error_str}"
            )

            # Username not found
            if "USERNAME_NOT_OCCUPIED" in error_str or "USERNAME_INVALID" in error_str:
                return (False, None, "USERNAME_NOT_FOUND")

            # Numeric ID not in cache — sync dialogs and retry
            if isinstance(chat_id, int):
                logger.info(f"Numeric ID {chat_id} not in cache, starting resolution fallback...")
                return await self._resolve_numeric_chat_id(chat_id)

            return (False, None, "CHAT_NOT_ACCESSIBLE")

        except ValueError as e:
            # Pyrogram raises ValueError("Peer id invalid: ...") before making any API call.
            error_str = str(e)
            if isinstance(chat_id, int) and "Peer id invalid" in error_str:
                logger.info(
                    f"Chat {chat_id} not in Pyrogram cache (ValueError). "
                    f"Attempting cache sync + raw MTProto fallback..."
                )
                return await self._resolve_numeric_chat_id(chat_id)
            logger.error(f"ValueError accessing chat {chat_id}: {error_str}")
            return (False, None, "UNKNOWN")

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
