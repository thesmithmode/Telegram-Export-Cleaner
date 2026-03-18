# 📤 Export Worker

Async Telegram message export service that processes export jobs from Redis queue and returns results to Java Bot API.

## 🏗️ Architecture

```
Java Bot (Spring)
    ↓ (POST job to Redis)
Redis Queue (RQ)
    ↓ (BLPOP)
Export Worker (Python + Pyrogram)
    ├─ TelegramClient: Pyrogram connection + message export
    ├─ MessageConverter: Pyrogram Message → JSON
    ├─ QueueConsumer: Redis queue handling
    ├─ JavaBotClient: HTTP callback to Java Bot
    └─ Main loop: Get job → Export → Send response

Java Bot (Spring)
    ↑ (POST response with messages)
Export Worker (HTTP callback)
```

## 📋 Features

- ✅ **Async I/O**: High concurrency using asyncio
- ✅ **Session Management**: Pyrogram session persistence in Docker volume
- ✅ **Rate Limiting**: Exponential backoff for FloodWait errors
- ✅ **Error Handling**: Categorized errors with retry logic
- ✅ **Message Conversion**: Pyrogram → Telegram Desktop export format
- ✅ **Media Metadata**: Photo, video, audio, document handling
- ✅ **Text Entities**: Bold, italic, links, mentions, etc.
- ✅ **Forwarded Messages**: Preserve forward info
- ✅ **Edited Messages**: Track edits
- ✅ **Graceful Shutdown**: SIGTERM/SIGINT handling

## 🚀 Quick Start

### Environment Variables

```bash
# Telegram API
TELEGRAM_API_ID=123456
TELEGRAM_API_HASH=abcdef123456
TELEGRAM_PHONE=+1234567890

# Redis
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_DB=0
REDIS_PASSWORD=  # optional

# Java Bot API
JAVA_API_BASE_URL=http://java-bot:8080
JAVA_API_KEY=your-secret-key

# Worker
WORKER_NAME=export-worker-1
MAX_WORKERS=4
JOB_TIMEOUT=3600
LOG_LEVEL=INFO
```

### Docker Compose

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

  export-worker:
    build: ./export-worker
    environment:
      - TELEGRAM_API_ID=123456
      - TELEGRAM_API_HASH=abcdef
      - TELEGRAM_PHONE=+1234567890
      - REDIS_HOST=redis
      - JAVA_API_BASE_URL=http://java-bot:8080
      - JAVA_API_KEY=secret
      - LOG_LEVEL=INFO
    volumes:
      - ./export-worker/session:/app/session
      - ./export-worker/logs:/app/logs
    depends_on:
      - redis
      - java-bot

volumes:
  redis_data:
```

### Run Locally

```bash
# Install dependencies
pip install -r requirements.txt

# Set environment variables
export TELEGRAM_API_ID=123456
export TELEGRAM_API_HASH=abcdef
export TELEGRAM_PHONE=+1234567890
export REDIS_HOST=localhost
export JAVA_API_BASE_URL=http://localhost:8080
export JAVA_API_KEY=secret

# Run worker
python main.py
```

## 📁 Project Structure

```
export-worker/
├── config.py              # Configuration from env vars
├── models.py              # Pydantic data models
├── json_converter.py      # Pyrogram → JSON conversion
├── pyrogram_client.py     # Telegram API client
├── queue_consumer.py      # Redis queue handling
├── java_client.py         # Java Bot API client
├── main.py                # Entry point and main loop
├── requirements.txt       # Python dependencies
├── Dockerfile             # Container image
├── .dockerignore          # Docker build ignore
├── README.md              # This file
├── session/               # Pyrogram session (volume)
│   └── export_worker.session
├── logs/                  # Application logs (optional)
├── research/              # Research and docs
│   ├── MESSAGE_STRUCTURE_ANALYSIS.md
│   └── analyze_message_structure.py
└── tests/                 # Unit tests
    ├── __init__.py
    ├── test_models.py
    ├── test_json_converter.py
    └── test_queue_consumer.py
```

## 🔄 Job Flow

### 1. Job Arrives from Java Bot

Java Bot pushes export request to Redis:

```json
{
  "task_id": "export_12345",
  "user_id": 123456789,
  "chat_id": -1001234567890,
  "limit": 0,
  "offset_id": 0,
  "from_date": "2025-01-01T00:00:00",
  "to_date": "2025-12-31T23:59:59"
}
```

### 2. Worker Processes Job

1. **Get job from queue** (blocking BLPOP)
2. **Verify access** to chat
3. **Export messages** from Telegram (async generator)
4. **Convert to JSON** format (Pyrogram → result.json)
5. **Send response** to Java Bot HTTP API
6. **Mark as complete** or failed in Redis

### 3. Response Sent to Java Bot

Worker POSTs response:

```json
{
  "task_id": "export_12345",
  "status": "completed",
  "message_count": 100,
  "exported_at": "2025-06-24T15:30:00",
  "messages": [
    {
      "id": 123,
      "type": "message",
      "date": "2025-06-24T15:29:46",
      "text": "Hello world",
      "from": "John Doe",
      "from_id": {"peer_type": "user", "peer_id": 456}
    },
    ...
  ]
}
```

## 🛡️ Error Handling

### Temporary Errors (Retryable)

- **FloodWait** (rate limiting): Exponential backoff (1s, 2s, 4s, 8s, 16s, 32s)
- **Network errors**: Connection timeout, socket errors
- **Redis unavailable**: Retry until connected

### Permanent Errors (Non-retryable)

- **Invalid chat ID** (BadRequest): Mark as failed
- **No access** (ChannelPrivate): Mark as failed
- **Admin required** (ChatAdminRequired): Mark as failed
- **Auth error** (Unauthorized): Exit worker

## 🔐 Session Management

Pyrogram stores authentication session in `session/export_worker.session` file:

- Contains encrypted auth token
- Persistent across worker restarts
- **NEVER commit to git** (add to .gitignore)
- **Keep in Docker volume** for durability
- Delete to force re-authentication

## 📊 Monitoring

### Log Levels

- `INFO`: Job start/end, connection status
- `DEBUG`: Message processing, queue operations
- `ERROR`: Exceptions and failures

### Redis Keys

```
job:processing:{task_id}  # Job being processed (expires after JOB_TIMEOUT)
job:completed:{task_id}   # Job completed (expires after 1 hour)
job:failed:{task_id}      # Job failed (expires after 1 hour)
```

### Metrics

- Jobs processed
- Jobs failed
- Messages exported per job
- Export rate (msg/sec)
- Queue length

## 🧪 Testing

```bash
# Run all tests
pytest

# Run with coverage
pytest --cov=. --cov-report=html

# Run specific test
pytest tests/test_json_converter.py -v
```

## 🔧 Troubleshooting

### Session expires / Auth errors

```
ERROR - Authorization failed
```

**Solution**: Delete `session/export_worker.session` and restart worker. Will ask for 2FA code.

### Rate limiting (FloodWait)

```
WARNING - Rate limited (flood wait). Retry 1/3 after 1.0s
```

**Solution**: Normal behavior. Worker will retry automatically.

### Cannot reach Redis

```
ERROR - Failed to connect to Redis
```

**Solution**: Check `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`

### Cannot reach Java Bot API

```
ERROR - Cannot reach Java Bot API
```

**Solution**: Check `JAVA_API_BASE_URL` and `JAVA_API_KEY`

## 📚 References

- [Pyrogram Documentation](https://docs.pyrogram.org/)
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Redis Stream Documentation](https://redis.io/commands/)
- [asyncio Documentation](https://docs.python.org/3/library/asyncio.html)

## 📝 License

Same as parent project (Telegram Export Cleaner)
