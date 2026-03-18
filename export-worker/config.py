"""
Configuration management for Export Worker.

Loads settings from environment variables with defaults.
"""

import os
from typing import Optional
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Export Worker configuration from environment variables."""

    # Redis Queue
    REDIS_HOST: str = os.getenv("REDIS_HOST", "redis")
    REDIS_PORT: int = int(os.getenv("REDIS_PORT", "6379"))
    REDIS_DB: int = int(os.getenv("REDIS_DB", "0"))
    REDIS_PASSWORD: Optional[str] = os.getenv("REDIS_PASSWORD", None)
    REDIS_QUEUE_NAME: str = os.getenv("REDIS_QUEUE_NAME", "telegram_export")

    # Java Bot API
    JAVA_API_BASE_URL: str = os.getenv("JAVA_API_BASE_URL", "http://java-bot:8080")
    JAVA_API_KEY: str = os.getenv("JAVA_API_KEY", "")

    # Telegram API
    TELEGRAM_API_ID: int = int(os.getenv("TELEGRAM_API_ID", "0"))
    TELEGRAM_API_HASH: str = os.getenv("TELEGRAM_API_HASH", "")
    TELEGRAM_PHONE: str = os.getenv("TELEGRAM_PHONE", "")

    # Pyrogram
    SESSION_NAME: str = "export_worker"  # Will be saved in session/ folder
    PYROGRAM_LOG_LEVEL: str = os.getenv("PYROGRAM_LOG_LEVEL", "ERROR")

    # Worker
    WORKER_NAME: str = os.getenv("WORKER_NAME", "export-worker-1")
    MAX_WORKERS: int = int(os.getenv("MAX_WORKERS", "1"))
    JOB_TIMEOUT: int = int(os.getenv("JOB_TIMEOUT", "3600"))  # 1 hour

    # Retry policy
    MAX_RETRIES: int = int(os.getenv("MAX_RETRIES", "3"))
    RETRY_BASE_DELAY: float = float(os.getenv("RETRY_BASE_DELAY", "1.0"))  # seconds
    RETRY_MAX_DELAY: float = float(os.getenv("RETRY_MAX_DELAY", "32.0"))  # seconds

    # Logging
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    LOG_FORMAT: str = "json"  # "json" or "text"

    class Config:
        """Pydantic configuration for Settings class.

        Specifies that environment variables should be loaded from .env file
        and that configuration keys are case-sensitive (TELEGRAM_API_ID != telegram_api_id).
        """
        env_file = ".env"
        case_sensitive = True


# Global settings instance
settings = Settings()
