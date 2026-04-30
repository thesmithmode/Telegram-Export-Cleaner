
from typing import Optional
from pydantic import ConfigDict, field_validator, model_validator
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
    REDIS_SUBSCRIPTION_QUEUE_SUFFIX: str = "_subscription"

    # Java Bot API
    JAVA_API_BASE_URL: str = "http://java-bot:8080"
    # Внутренний API ключ (X-API-Key header). Должен совпадать с api.key у java-bot.
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

    # Main loop circuit breaker: после стольких подряд неудачных итераций
    # ExportWorker.run() делает sys.exit(1), супервизор контейнера перезапускает.
    # Без этого Redis/SQLite outage превращается в тихий бесконечный sleep-loop
    # с heartbeat'ом, выглядящим живым.
    MAIN_LOOP_MAX_CONSECUTIVE_ERRORS: int = 10

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
    CACHE_FETCH_CHUNK_SIZE: int = 1_000           # rows per cursor.fetchmany
    CACHE_STORE_BATCH_SIZE: int = 1_000           # rows per executemany INSERT

    # Единая политика таймаутов: heartbeat-loop в worker, job-execution,
    # сетевые операции к Java, BLPOP socket. Хотя сейчас разнесены по
    # отдельным полям выше — собранный mapping для introspect/доков.
    @property
    def TIMEOUTS(self) -> dict:
        return {
            "heartbeat_ttl": 120,
            "job_execution": self.JOB_TIMEOUT,
            "java_upload": 3600,
            "redis_socket": 10,
        }

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

    @model_validator(mode="after")
    def validate_auth_mode(self) -> "Settings":
        # В Docker нет stdin — fallback на file-based session с phone_number
        # упрётся в interactive 2FA prompt и worker упадёт. Явно требуем
        # один из двух режимов: SESSION_STRING (prod) или PHONE_NUMBER (dev).
        if not self.TELEGRAM_SESSION_STRING and not self.TELEGRAM_PHONE_NUMBER:
            raise ValueError(
                "Either TELEGRAM_SESSION_STRING (stateless, production) or "
                "TELEGRAM_PHONE_NUMBER (interactive, local dev only) must be set. "
                "Docker containers have no stdin for 2FA — prod MUST use SESSION_STRING."
            )
        return self

# Global settings instance
settings = Settings()
