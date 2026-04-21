
from typing import Optional
from pydantic import ConfigDict, field_validator
from pydantic_settings import BaseSettings

class Settings(BaseSettings):

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

    # Message Cache (SQLite on disk)
    CACHE_ENABLED: bool = True
    CACHE_TTL_SECONDS: int = 30 * 86400          # 30 days
    CACHE_DB_PATH: str = "/data/cache/messages.db"
    CACHE_MAX_DISK_GB: float = 25.0               # ~400 full chats of 250k msgs
    CACHE_MAX_MESSAGES_PER_CHAT: int = 100_000
    CACHE_STATS_INTERVAL_SECONDS: int = 60         # admin dashboard snapshot period
    CACHE_STATS_TOP_N: int = 50                    # chats per snapshot

    # Logging
    LOG_LEVEL: str = "INFO"

    # Redis Stream для статистики (dashboard)
    STATS_STREAM_KEY: str = "stats:events"

    @field_validator("TELEGRAM_API_ID")
    @classmethod
    def validate_api_id(cls, v: int) -> int:
        if v == 0:
            raise ValueError(
                "TELEGRAM_API_ID must be set. "
                "Get it from https://my.telegram.org/apps"
            )
        return v

    @field_validator("TELEGRAM_API_HASH")
    @classmethod
    def validate_api_hash(cls, v: str) -> str:
        if not v:
            raise ValueError(
                "TELEGRAM_API_HASH must be set. "
                "Get it from https://my.telegram.org/apps"
            )
        return v

    @field_validator("TELEGRAM_PHONE_NUMBER", mode="after")
    @classmethod
    def validate_phone(cls, v: str) -> str:
        # Phone number is optional when SESSION_STRING is used (production)
        # Only required for file-based session (development)
        # Return empty string if not provided - will use SESSION_STRING instead
        return v or ""

# Global settings instance
settings = Settings()
