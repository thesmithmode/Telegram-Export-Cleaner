"""
Configuration management for Export Worker.

Loads settings from environment variables with defaults.
"""

from typing import Optional
from pydantic import ConfigDict
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Export Worker configuration from environment variables."""

    model_config = ConfigDict(
        env_file=".env",
        case_sensitive=True,
        extra="ignore",  # Ignore unknown env vars like TELEGRAM_BOT_TOKEN
    )

    # Redis Queue
    REDIS_HOST: str = "redis"
    REDIS_PORT: int = 6379
    REDIS_DB: int = 0
    REDIS_PASSWORD: Optional[str] = None
    REDIS_QUEUE_NAME: str = "telegram_export"

    # Java Bot API
    JAVA_API_BASE_URL: str = "http://java-bot:8080"
    JAVA_API_KEY: str = ""

    # Telegram API (MTProto - for Pyrogram export)
    TELEGRAM_API_ID: int = 0
    TELEGRAM_API_HASH: str = ""
    TELEGRAM_PHONE_NUMBER: str = ""

    # Telegram Bot API (for sending results back to users)
    TELEGRAM_BOT_TOKEN: Optional[str] = None

    # Pyrogram
    SESSION_NAME: str = "export_worker"  # Will be saved in session/ folder
    TELEGRAM_SESSION_STRING: Optional[str] = None  # Production: string session for stateless auth
    PYROGRAM_LOG_LEVEL: str = "ERROR"

    # Worker
    WORKER_NAME: str = "export-worker-1"
    MAX_WORKERS: int = 1
    JOB_TIMEOUT: int = 1800  # 30 minutes (optimized for weak server)

    # Retry policy
    MAX_RETRIES: int = 3
    RETRY_BASE_DELAY: float = 1.0  # seconds
    RETRY_MAX_DELAY: float = 32.0  # seconds

    # Logging
    LOG_LEVEL: str = "INFO"
    LOG_FORMAT: str = "json"  # "json" or "text"


# Global settings instance
settings = Settings()
