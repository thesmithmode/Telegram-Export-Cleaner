#!/usr/bin/env python3
"""
Export Worker: Main entry point.

Architecture:
1. Connect to Redis queue
2. Connect to Telegram API (Pyrogram)
3. Connect to Java Bot API
4. Loop: Get job → Export → Send response
5. Graceful shutdown on SIGTERM/SIGINT

Error handling:
- Temp errors (network, rate limit): Retry with exponential backoff
- Perm errors (invalid chat, no access): Mark as failed, continue
- Critical errors (auth, config): Exit with error
"""

import logging
import asyncio
import signal
import sys
import shutil
import psutil
from pathlib import Path
from typing import Optional

from config import settings
from pyrogram_client import TelegramClient, create_client as create_telegram_client
from queue_consumer import QueueConsumer, create_queue_consumer
from java_client import JavaBotClient, create_java_client
from models import ExportRequest, ExportedMessage

# Setup logging
logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)

logger = logging.getLogger(__name__)


class ExportWorker:
    """Main worker that processes export jobs."""

    def __init__(self):
        """Initialize worker."""
        self.telegram_client: Optional[TelegramClient] = None
        self.queue_consumer: Optional[QueueConsumer] = None
        self.java_client: Optional[JavaBotClient] = None
        self.running = False
        self.jobs_processed = 0
        self.jobs_failed = 0

    def log_memory_usage(self, stage: str):
        """Log current memory usage for monitoring weak server resources."""
        try:
            mem = psutil.virtual_memory()
            cpu_percent = psutil.cpu_percent(interval=0.1)
            logger.info(
                f"📊 Resource usage [{stage}]: "
                f"Memory {mem.percent}% ({mem.available/1024/1024:.0f}MB free), "
                f"CPU {cpu_percent}%"
            )
        except Exception as e:
            logger.warning(f"Could not get resource stats: {e}")

    async def cleanup_temp_files(self, task_id: str):
        """Delete temporary files for a task to prevent disk fill."""
        try:
            temp_dir = Path(f"/tmp/export_{task_id}")
            if temp_dir.exists():
                shutil.rmtree(temp_dir)
                logger.debug(f"Cleaned up temp files for task {task_id}")
        except Exception as e:
            logger.warning(f"Failed to cleanup temp files for {task_id}: {e}")

    async def initialize(self) -> bool:
        """
        Initialize all components.

        Returns:
            True if all components initialized successfully
        """
        try:
            logger.info("🚀 Initializing Export Worker...")

            # 1. Connect to Redis queue
            logger.info("1️⃣  Connecting to Redis queue...")
            self.queue_consumer = await create_queue_consumer()

            # 2. Connect to Telegram API
            logger.info("2️⃣  Connecting to Telegram API...")
            self.telegram_client = await create_telegram_client()

            # 3. Connect to Java Bot API
            logger.info("3️⃣  Connecting to Java Bot API...")
            self.java_client = await create_java_client()

            logger.info("✅ All components initialized successfully")
            return True

        except Exception as e:
            logger.error(f"❌ Initialization failed: {e}")
            await self.cleanup()
            return False

    async def process_job(self, job: ExportRequest) -> bool:
        """
        Process single export job.

        Args:
            job: Export request from queue

        Returns:
            True if processed successfully (even if no messages)
        """
        try:
            logger.info(f"📝 Processing job {job.task_id} (chat {job.chat_id})")
            self.log_memory_usage("JOB_START")

            # Mark job as processing
            await self.queue_consumer.mark_job_processing(job.task_id)

            # Verify access to chat
            if not await self.telegram_client.verify_access(job.chat_id):
                error = f"No access to chat {job.chat_id}"
                logger.error(f"❌ {error}")
                await self.java_client.send_response(
                    task_id=job.task_id,
                    status="failed",
                    messages=[],
                    error=error,
                    error_code="CHAT_NOT_ACCESSIBLE"
                )
                await self.queue_consumer.mark_job_failed(job.task_id, error)
                return True

            # Get chat info
            chat_info = await self.telegram_client.get_chat_info(job.chat_id)
            if chat_info:
                logger.info(f"  Chat: {chat_info.get('title')} (type: {chat_info.get('type')})")

            # Export messages
            messages: list[ExportedMessage] = []
            try:
                async for message in self.telegram_client.get_chat_history(
                    chat_id=job.chat_id,
                    limit=job.limit,
                    offset_id=job.offset_id,
                ):
                    messages.append(message)

                    # Log progress every 500 messages
                    if len(messages) % 500 == 0:
                        logger.info(f"  Exported {len(messages)} messages...")

            except Exception as e:
                error = f"Export failed: {str(e)}"
                logger.error(f"❌ {error}")
                await self.java_client.send_response(
                    task_id=job.task_id,
                    status="failed",
                    messages=messages,  # Send all messages exported so far
                    error=error,
                    error_code="EXPORT_ERROR"
                )
                await self.queue_consumer.mark_job_failed(job.task_id, error)
                return True

            # Send results to Java Bot
            success = await self.java_client.send_response(
                task_id=job.task_id,
                status="completed",
                messages=messages,
            )

            if success:
                logger.info(f"✅ Job {job.task_id} completed ({len(messages)} messages)")
                await self.queue_consumer.mark_job_completed(job.task_id)
                self.jobs_processed += 1
                self.log_memory_usage("JOB_DONE")
                await self.cleanup_temp_files(job.task_id)
                return True

            else:
                error = "Failed to send response to Java Bot"
                logger.error(f"❌ {error}")
                await self.queue_consumer.mark_job_failed(job.task_id, error)
                self.jobs_failed += 1
                self.log_memory_usage("JOB_FAILED")
                await self.cleanup_temp_files(job.task_id)
                return True

        except Exception as e:
            logger.error(f"❌ Unexpected error in job {job.task_id}: {e}", exc_info=True)
            await self.queue_consumer.mark_job_failed(job.task_id, str(e))
            self.jobs_failed += 1
            self.log_memory_usage("JOB_ERROR")
            await self.cleanup_temp_files(job.task_id)
            return True

    async def run(self):
        """
        Main worker loop.

        Process jobs from queue until shutdown signal received.
        """
        if not await self.initialize():
            sys.exit(1)

        self.running = True
        logger.info("🔄 Worker ready, waiting for jobs...")

        try:
            while self.running:
                try:
                    # Get next job (blocking)
                    job = await self.queue_consumer.get_job()

                    if job:
                        await self.process_job(job)
                    else:
                        # No job - should not happen with BLPOP timeout=0
                        await asyncio.sleep(1)

                except asyncio.CancelledError:
                    logger.info("Worker cancelled")
                    break

                except KeyboardInterrupt:
                    logger.info("Worker interrupted")
                    break

                except Exception as e:
                    logger.error(f"Error in main loop: {e}", exc_info=True)
                    await asyncio.sleep(5)

        finally:
            await self.cleanup()

    async def cleanup(self):
        """Clean shutdown."""
        logger.info("🛑 Shutting down worker...")

        self.running = False

        if self.telegram_client:
            await self.telegram_client.disconnect()

        if self.queue_consumer:
            await self.queue_consumer.disconnect()

        logger.info(
            f"📊 Final stats: {self.jobs_processed} processed, "
            f"{self.jobs_failed} failed"
        )
        logger.info("✅ Worker stopped")

    def handle_signal(self, signum, frame):
        """Handle SIGTERM/SIGINT."""
        logger.info(f"Received signal {signum}")
        self.running = False


async def main():
    """Main entry point."""
    worker = ExportWorker()

    # Setup signal handlers
    signal.signal(signal.SIGTERM, worker.handle_signal)
    signal.signal(signal.SIGINT, worker.handle_signal)

    try:
        await worker.run()

    except KeyboardInterrupt:
        logger.info("Interrupted by user")

    except Exception as e:
        logger.error(f"Fatal error: {e}", exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
