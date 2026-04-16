# Python Worker Guide

## Overview

Python Worker — сердце экспорта. Обрабатывает задачи из Redis очереди, взаимодействует с Telegram API через Pyrogram, кэширует результаты и возвращает их через REST API.

**Основные функции:**
- Потребление задач из Redis очереди (BLPOP)
- Экспорт сообщений через async Pyrogram API
- 3-path кэширование (date, id, fallback)
- Отслеживание прогресса в real-time
- Поддержка отмены экспорта во время обработки
- Graceful shutdown на SIGTERM

## Architecture

```
Redis Queue (BLPOP)
      ↓
ExportWorker (job processing)
      ↓
┌────────────────────────────────────────────┐
│ 1. Cache Check (date/id range)            │
│ 2. Pyrogram getMessages() if not cached   │
│ 3. Store in Redis sorted sets             │
└────────────────────────────────────────────┘
      ↓
JavaBotClient (POST /api/convert)
      ↓
BotMessenger (sends file to user)
```

## Components

### ExportWorker

Основной класс для обработки экспорта.

**Job Lifecycle:**
```
BLPOP (wait for job)
  ↓
_process_job(job)
  ├─ SetActive(job.task_id)
  ├─ _check_cancel_and_save() [every 100 messages]
  ├─ _export_with_caching(job)  [3-path strategy]
  ├─ JavaBotClient.send_response()
  └─ Cleanup (delete temp files, clear locks)
```

### 3-Path Caching Strategy

**Path 1: Date-Range Cache Hit**
```
If job.chat_id has cached messages in date range [start_date, end_date]:
  → Fetch from cache (Redis sorted sets by date)
  → POST to /api/convert
  → Return to user (fastest, < 1 second)
```

**Path 2: Full Chat Cache Hit**
```
If job.chat_id has cached messages (different date range):
  → Fetch ALL cached messages for chat
  → Filter by job.start_date, job.end_date
  → POST to /api/convert
  → Cache new date range
  → Return to user (fast, few seconds)
```

**Path 3: Fallback to Telegram API**
```
If cache miss:
  → Get messages from Telegram via Pyrogram
  → Store in cache
  → POST to /api/convert
  → Return to user (slow, 10-60+ seconds depending on chat size)
```

### TelegramClient

Async wrapper around Pyrogram для сообщения с Telegram API.

**Key Features:**
1. **FloodWait Backoff** — exponential retry при rate limiting
   ```python
   try:
       async for message in get_chat_history(...):
           ...
   except FloodWait as e:
       await asyncio.sleep(e.value)  # wait, then retry
   ```

2. **Canonical ID Resolver** — конвертирование username/link в numeric ID
   ```python
   # Convert different input formats to same ID
   "@durov" → 123456
   "https://t.me/durov" → 123456
   "durov" → 123456
   123456 → 123456
   ```

3. **Deduplication** — avoid re-yielding messages after FloodWait retry
   ```python
   seen_message_ids: set[int] = set()
   if message.id not in seen_message_ids:
       yield message
       seen_message_ids.add(message.id)
   ```

**Methods:**
```python
async def connect() -> bool
    # Authenticate to Telegram API
    # Returns True if successful

async def disconnect() -> None
    # Graceful disconnect

async def get_chat_history(
    chat_id: Union[int, str],
    limit: int = 0,
    offset_id: int = 0,
    min_id: int = 0,
    from_date: Optional[datetime] = None,
    to_date: Optional[datetime] = None,
) -> AsyncGenerator[ExportedMessage, None]
    # Get messages from chat with backoff retry
    # Args:
    #   chat_id: Telegram chat ID or username
    #   limit: Max messages (0 = all)
    #   offset_id: Pagination marker
    #   from_date, to_date: Date filtering
    # Yields: ExportedMessage objects
```

### MessageCache

Redis-based caching layer с sorted sets для быстрого поиска по датам и ID.

**Data Structures:**

```redis
ZADD cache:msgs:{chat_id} msg_id msgpack_bytes
    # Score = msg_id, Value = serialized message
    # Used for quick lookup by message ID

ZADD cache:dates:{chat_id} unix_timestamp msg_id
    # Score = unix_timestamp, Value = msg_id
    # Used for range queries by date
    # ZRANGEBYSCORE to get messages in date range

ZADD cache:ranges:{chat_id} start_ts end_ts "1"
    # Track what date ranges are fully cached
    # Detect gaps in cached data
```

**Methods:**
```python
async def store_messages(chat_id: int, messages: list[ExportedMessage]) -> int
    # Store messages in Redis
    # Returns: count of stored messages

async def get_messages_by_date_range(
    chat_id: int,
    from_date: datetime,
    to_date: datetime
) -> Optional[list[ExportedMessage]]
    # Get cached messages for date range
    # Returns: list of messages or None if gap detected

async def get_all_messages(chat_id: int) -> Optional[list[ExportedMessage]]
    # Get ALL cached messages for chat
    # Returns: list of messages or None
```

### QueueConsumer

BLPOP pattern для потребления задач из Redis очереди.

**Features:**
- Non-blocking consumer (timeout=1 second)
- Job state tracking (processing, completed, failed)
- Orphaned job recovery (if worker crashed, restart job)

**Methods:**
```python
async def get_job() -> Optional[ExportRequest]
    # BLPOP from queue (with timeout)
    # Returns: next job or None if queue empty

async def mark_processing(task_id: str) -> None
    # Mark job as "being processed"

async def mark_completed(task_id: str) -> None
    # Mark job as completed

async def mark_failed(task_id: str, error: str) -> None
    # Mark job as failed with error details
```

### JavaBotClient

HTTP client для отправки результатов на Java API.

**Methods:**
```python
async def send_response(
    user_chat_id: int,
    task_id: str,
    result: ExportedMessagesContainer
) -> bool
    # POST /api/convert with results
    # Streams JSON response to Java Bot
    # Returns: True if successful

def create_progress_tracker(
    user_chat_id: int,
    task_id: str
) -> ProgressTracker
    # Create tracker for real-time progress updates
    # Sends updates via Java Bot to user.
    # Worker starts tracker immediately ("Экспорт начался..."),
    # then updates the same message to progress bar when total is known.
```

## Cancel Support

Пользователь может отменить экспорт во время обработки.

**How it works:**

```
User clicks "❌ Cancel" in Telegram
      ↓
Java Bot sets Redis: cancel_export:{task_id}
      ↓
Python Worker checks every 100 messages:
  is_cancelled(task_id) → reads cancel_export:{task_id}
      ↓
If cancelled:
  → Save accumulated messages to cache
  → Return partial result
  → Clear active_export:{user_id} lock
  → User gets partial export
```

**Implementation:**
```python
async def _check_cancel_and_save(job, all_messages, count):
    if count % 100 != 0:  # Check every 100 messages
        return False
    
    if not await is_cancelled(job.task_id):
        return False
    
    # Save to cache before returning
    await message_cache.store_messages(job.chat_id, all_messages)
    await clear_active_export(job.user_id)
    
    return True  # Signal cancellation
```

## Configuration

**config.py** (Pydantic settings):
```python
# Telegram API
TELEGRAM_API_ID: int                    # from my.telegram.org/apps
TELEGRAM_API_HASH: str
TELEGRAM_PHONE_NUMBER: str              # for file-based session
TELEGRAM_SESSION_STRING: str = ""       # for string session

# Redis
REDIS_HOST: str = "redis"
REDIS_PORT: int = 6379
REDIS_DB: int = 0
REDIS_PASSWORD: str = ""

# Caching
CACHE_ENABLED: bool = True
CACHE_TTL_DAYS: int = 30

# Java Bot API
JAVA_API_URL: str = "http://java:8080"
JAVA_API_KEY: str

# Processing
MAX_WORKERS: int = 4
MAX_RETRIES: int = 3
RETRY_DELAY_SECONDS: int = 5
LOG_LEVEL: str = "INFO"
```

## Performance

| Operation | Time | Notes |
|-----------|------|-------|
| Export from cache | ~1-2s | Redis read only |
| Export first time (100 msgs) | ~5-10s | Depends on Pyrogram rate |
| Export large chat (10K msgs) | ~30-60s | Multiple requests with FloodWait |
| Date-range cache hit | ~1s | Fastest path |

## Monitoring

**Memory Usage:**
```python
def log_memory_usage(stage: str):
    mem = psutil.virtual_memory()
    cpu_percent = psutil.cpu_percent()
    logger.info(f"Memory {mem.percent}%, CPU {cpu_percent}%")
```

**Job Processing:**
```python
# Tracked metrics:
self.jobs_processed   # Total succeeded
self.jobs_failed      # Total failed
```

## Testing

Tests in `tests/` directory:
- `test_pyrogram_client.py` — async Pyrogram client mocks
- `test_message_cache.py` — Redis sorted sets operations
- `test_queue_consumer.py` — BLPOP consumer logic
- `test_export_worker.py` — full job processing flow
- `test_end_to_end.py` — real Redis + API integration
- `test_performance.py` — performance benchmarks

All tests use mocks for Redis and Pyrogram.

## Troubleshooting

**Worker stuck in loop**
```bash
docker logs st_python | tail -50
```

**High memory usage**
```python
# Check if cache is full
redis-cli
> DBSIZE
> KEYS cache:*
```

**Slow exports**
- Check Telegram API rate limits (FloodWait)
- Check Redis connection speed
- Check if caching is enabled

---

See also:
- [ARCHITECTURE.md](ARCHITECTURE.md) — system design
- [DEVELOPMENT.md](DEVELOPMENT.md) — contributing guidelines
- [README.md](../README.md) — project overview & quick start
