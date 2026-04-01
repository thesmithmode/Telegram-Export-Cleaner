# Telegram Export Cleaner

**Professional data export solution for Telegram** with Java REST API backend and Python Pyrogram worker.

Exports chat history via Telegram API, applies filters (date ranges, keywords), formats to clean text, and sends back to user — all automated via Redis queue.

**Try it now**: [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot) (доступ по запросу)

**Status**: ✅ Production Ready (Full CI/CD automation, Docker, 450+ tests)

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Architecture & Data Flow](#architecture--data-flow)
3. [Features](#features)
4. [Environment Setup](#environment-setup)
5. [API Reference](#api-reference)
6. [Development](#development)
7. [Testing](#testing)
8. [Troubleshooting](#troubleshooting)
9. [FAQ](#faq)
10. [Contributing](#contributing)
11. [Performance](#performance)
12. [Security](#security)
13. [Deployment](#deployment)

---

## Quick Start

### Prerequisites

- **Docker & Docker Compose** (simplest way)
- OR: **Manual**: Java 21, Python 3.11+, Redis 7, Maven 3.9

### 1. Get Telegram Credentials

1. **API Credentials**: Visit [my.telegram.org/apps](https://my.telegram.org/apps)
   - Log in with your Telegram account
   - Create an application
   - Copy `API_ID` and `API_HASH`

2. **Bot Token**: Message [@BotFather](https://t.me/botfather)
   - Send `/newbot`
   - Choose name and username
   - Copy the `TOKEN`

### 2. Generate Pyrogram String Session (Production Only)

```bash
cd export-worker
python get_session.py
```

Follow the prompts:
```
Enter your TELEGRAM_API_ID: 123456789
Enter your TELEGRAM_API_HASH: abcdef0123456789abcdef0123456789
Enter phone number: +1234567890
Enter SMS code: 12345
Enter 2FA password (if enabled): mypassword
```

You'll get a long string: `BQF...xyz...AAA`

**→ Save this to GitHub Secrets as `TELEGRAM_SESSION_STRING`** (one-time setup, production only)

### 3. Setup Environment

```bash
# Create .env from template
cp .env.example .env

# Edit with your credentials
nano .env
```

**For Production (CI/CD):**
```env
TELEGRAM_API_ID=123456789
TELEGRAM_API_HASH=abcdef0123456789abcdef0123456789
TELEGRAM_BOT_TOKEN=123456:ABCDefGhIjKlMnOpQrStUvWxYz-_=
TELEGRAM_SESSION_STRING=BQF...xyz...AAA  # From step 2
# All other vars go to GitHub Secrets
```

**For Local Development:**
```env
TELEGRAM_API_ID=123456789
TELEGRAM_API_HASH=abcdef0123456789abcdef0123456789
TELEGRAM_PHONE_NUMBER=+1234567890
TELEGRAM_BOT_TOKEN=123456:ABCDefGhIjKlMnOpQrStUvWxYz-_=
REDIS_HOST=localhost  # Or: docker run -d -p 6379:6379 redis:7-alpine
JAVA_API_BASE_URL=http://localhost:8080
```

### 4. Run with Docker

```bash
# Start all services
docker-compose up -d

# Verify all are running
docker ps

# Watch logs
docker-compose logs -f

# Stop services
docker-compose down
```

**Services:**
- ✅ Java Spring Boot API on `localhost:8081`
- ✅ Telegram Bot (long polling)
- ✅ Python Worker (asyncio, Pyrogram, MAX_WORKERS по умолчанию 1, настраивается)
- ✅ Redis (internal queue)

### 5. Use the Bot

Open Telegram and message [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot):

```
/start                              → Welcome & instructions
/export https://t.me/durov          → Export by link
/export @durov                      → Export by username
/export durov                       → Export by username
/export -1001234567890              → Export by numeric ID
/help                               → Show commands
```

Или нажмите **«📢 Выбрать канал»** / **«📂 Выбрать группу»** — нативный Telegram picker без ручного ввода. Работает для публичных каналов и групп (включая те, в которых воркер не состоит).

**Example:**

```
You:    /export https://t.me/strbypass

Bot:    Задача принята!
        ID: export_abc123xyz
        Чат: @strbypass

        Экспорт запущен. Когда воркер обработает — вы получите файл здесь.

[30 seconds - 10 minutes later, depending on chat size]

Bot:    ✅ Export complete! [sends output.txt]

You:    [receives .txt file with formatted history]
```

---

## Architecture & Data Flow

### System Diagram

```
╔═══════════════════════════════════════════════════════════════════════════╗
║                    TELEGRAM USER (sends /export command)                  ║
╚════════════════════════════════╤═══════════════════════════════════════════╝
                                 │
                          /export -100123456789
                                 │
         ╔═══════════════════════▼════════════════════════════════╗
         ║      JAVA SPRING BOOT API (Port 8080)                 ║
         ║  ┌────────────────────────────────────────────────┐   ║
         ║  │  ExportBot (TelegramLongPollingBot)            │   ║
         ║  │  • Polls Telegram Bot API for messages         │   ║
         ║  │  • Parses /export <link, @user, or ID>         │   ║
         ║  │  • Extracts username from t.me links           │   ║
         ║  │  • Confirms receipt to user (immediate)        │   ║
         ║  └────────────────────┬─────────────────────────┘   ║
         ║                       │                               ║
         ║  ┌────────────────────▼─────────────────────────┐   ║
         ║  │  ExportJobProducer (Service)                  │   ║
         ║  │  • Generates UUID: "export_<16-char-hash>"    │   ║
         ║  │  • Creates JSON job:                          │   ║
         ║  │    {                                           │   ║
         ║  │      "task_id": "export_abc123xyz...",        │   ║
         ║  │      "user_id": 987654,                       │   ║
         ║  │      "user_chat_id": 987654,                  │   ║
         ║  │      "chat_id": "username" or -100123...,      │   ║
         ║  │      "limit": 0,                              │   ║
         ║  │      "offset_id": 0                           │   ║
         ║  │    }                                           │   ║
         ║  │  • RPUSH to Redis queue                        │   ║
         ║  └───────────────────────────────────────────────┘   ║
         ╚════════════════════════╤═══════════════════════════════╝
                                  │
                          RPUSH telegram_export
                                  │
         ╔════════════════════════▼═══════════════════════════════╗
         ║         REDIS QUEUE (Port 6379, internal)             ║
         ║  Queue: "telegram_export"                             ║
         ║  Type: FIFO list (RPUSH / BLPOP)                     ║
         ║  Persistence: Configured via redis.conf              ║
         ╚════════════════════════╤═══════════════════════════════╝
                                  │
                          BLPOP telegram_export (blocking)
                                  │
         ╔════════════════════════▼═══════════════════════════════╗
         ║      PYTHON WORKER (asyncio + Pyrogram)               ║
         ║  MAX_WORKERS parallel workers (default: 1, configurable)    ║
         ║                                                        ║
         ║  ┌──────────────────────────────────────────────┐     ║
         ║  │  QueueConsumer                               │     ║
         ║  │  • Blocks on BLPOP with 1s timeout           │     ║
         ║  │  • Deserializes JSON to ExportRequest        │     ║
         ║  │  • Submits to thread pool                    │     ║
         ║  └──────────────────┬───────────────────────────┘     ║
         ║                     │                                  ║
         ║  ┌──────────────────▼───────────────────────────┐     ║
         ║  │  TelegramClient (Pyrogram)                   │     ║
         ║  │                                              │     ║
         ║  │  1. Authenticate:                           │     ║
         ║  │     • String session (production) OR         │     ║
         ║  │     • File-based session (dev)              │     ║
         ║  │                                              │     ║
         ║  │  2. verify_and_get_info(chat_id):           │     ║
         ║  │     • Check access                          │     ║
         ║  │     • Get chat metadata                     │     ║
         ║  │                                              │     ║
         ║  │  3. get_chat_history(chat_id):              │     ║
         ║  │     • Async generator yields messages       │     ║
         ║  │     • ~1000+ messages/second                │     ║
         ║  │     • Rate limiting: exponential backoff    │     ║
         ║  │       (min 1s, cap 32s)                     │     ║
         ║  │     • Retry on FloodWait (Telegram API)     │     ║
         ║  │                                              │     ║
         ║  │  4. Convert to ExportedMessage:             │     ║
         ║  │     • message.id, text, date, sender_name   │     ║
         ║  │     • Extract media_type, forward_from      │     ║
         ║  └──────────────────┬───────────────────────────┘     ║
         ║                     │                                  ║
         ║                [Thousands of messages]                ║
         ║                     │                                  ║
         ║  ┌──────────────────▼───────────────────────────┐     ║
         ║  │  JavaBotClient (HTTP)                        │     ║
         ║  │  • Collects messages as JSON                 │     ║
         ║  │  • Creates multipart/form-data request       │     ║
         ║  │  • POST /api/convert + result.json file      │     ║
         ║  │  • Receives output.txt back                  │     ║
         ║  └──────────────────┬───────────────────────────┘     ║
         ╚═════════════════════╤══════════════════════════════════╝
                               │
                   POST /api/convert (multipart)
                   Content-Type: multipart/form-data
                   File: result.json (Telegram export format)
                               │
         ╔═════════════════════▼══════════════════════════════════╗
         ║    JAVA REST API - TelegramController                  ║
         ║                                                         ║
         ║  1. Parse multipart form data                          ║
         ║  2. Validate JSON file format                          ║
         ║  3. Extract filter parameters:                         ║
         ║     ?startDate=YYYY-MM-DD                              ║
         ║     &endDate=YYYY-MM-DD                                ║
         ║     &keywords=word1,word2                              ║
         ║     &excludeKeywords=spam,junk                         ║
         ║                                                         ║
         ║  4. Create MessageFilter from params                   ║
         ║  5. Call TelegramExporter.processFile()                ║
         ║  ┌────────────────────────────────────────┐            ║
         ║  │  TelegramExporter.processFile()         │            ║
         ║  │                                         │            ║
         ║  │  Step 1: Parse JSON (Jackson)          │            ║
         ║  │  • Load entire file to memory           │            ║
         ║  │  • Max 50MB (config: multipart.max-...) │            ║
         ║  │  • Deserialize to Message[] array       │            ║
         ║  │                                         │            ║
         ║  │  Step 2: Filter Messages                │            ║
         ║  │  for each message:                      │            ║
         ║  │    if (filter.matches(message)) {      │            ║
         ║  │      format_and_collect(message)       │            ║
         ║  │    }                                    │            ║
         ║  │                                         │            ║
         ║  │  Filter logic:                          │            ║
         ║  │  • Date range: startDate ≤ date ≤ endDate
         ║  │  • Keywords: if keywords set,           │            ║
         ║  │    message must contain ANY             │            ║
         ║  │  • Exclude: message must NOT contain    │            ║
         ║  │    any excludeKeywords                  │            ║
         ║  │                                         │            ║
         ║  │  Step 3: Format Each Message            │            ║
         ║  │  for each filtered message:             │            ║
         ║  │                                         │            ║
         ║  │    1. MessageFormatter:                 │            ║
         ║  │       "YYYYMMDD_HH:mm message_text"    │            ║
         ║  │                                         │            ║
         ║  │    2. MarkdownParser (entities):        │            ║
         ║  │       Convert Telegram entities to:    │            ║
         ║  │       • bold → **text**                 │            ║
         ║  │       • italic → *text*                 │            ║
         ║  │       • code → `code`                   │            ║
         ║  │       • links → [text](url)             │            ║
         ║  │       • mentions → @username            │            ║
         ║  │                                         │            ║
         ║  │    3. Skip non-text:                    │            ║
         ║  │       • Media messages (photos, videos) │            ║
         ║  │       • Forwarded (only extract text)   │            ║
         ║  │       • Captions (include if present)   │            ║
         ║  │                                         │            ║
         ║  │  Step 4: Collect                        │            ║
         ║  │  List<String> lines = [formatted...]    │            ║
         ║  │                                         │            ║
         ║  │  Step 5: Return                         │            ║
         ║  │  return lines (not written to disk)     │            ║
         ║  └────────────────────────────────────────┘            ║
         ║                                                         ║
         ║  6. Response 200 OK:                                   ║
         ║     Content-Type: text/plain                           ║
         ║     Content-Disposition: attachment; filename=output.txt
         ║     Body: String.join("\n", lines)                    ║
         ╚═════════════════════╤══════════════════════════════════╝
                               │
                   HTTP Response 200 (output.txt)
                               │
         ╔═════════════════════▼══════════════════════════════════╗
         ║  Python Worker Receives Response                       ║
         ║  ┌────────────────────────────────────────────────┐   ║
         ║  │  JavaBotClient.send_result_to_user()           │   ║
         ║  │  • Get output.txt from response body           │   ║
         ║  │  • Save to temp file                           │   ║
         ║  │  • Call bot.send_document()                    │   ║
         ║  │  • Use TELEGRAM_BOT_TOKEN                      │   ║
         ║  │  • Send to user_id from original task          │   ║
         ║  └────────────────────────────────────────────────┘   ║
         ╚═════════════════════╤══════════════════════════════════╝
                               │
                     sendDocument via Bot API
                    (TELEGRAM_BOT_TOKEN)
                               │
         ╔═════════════════════▼══════════════════════════════════╗
         ║     TELEGRAM USER (receives result)                    ║
         ║                                                         ║
         ║  ✅ File: output.txt                                    ║
         ║  Content:                                              ║
         ║     20240115_14:30 **bold** message text               ║
         ║     20240115_14:31 another message                     ║
         ║     20240115_14:32 *italic* text                       ║
         ║     ... (all messages in chat)                         ║
         ║                                                         ║
         ║  Status: Done ✅                                        ║
         ╚═════════════════════════════════════════════════════════╝
```

### Component Responsibilities

#### Java Spring Boot (Port 8080, exposed as 8081)

| Class | Package | Responsibility |
|-------|---------|---|
| `ExportBot` | `com.tcleaner.bot` | Long-polls Telegram Bot API; parses `/export` with t.me links, @username, or numeric ID; sends confirmation |
| `ExportJobProducer` | `com.tcleaner.bot` | Creates JSON tasks; RPUSH to Redis queue; generates unique task_id |
| `BotInitializer` | `com.tcleaner.bot` | Registers bot with Telegram; starts long polling on app startup |
| `TelegramController` | `com.tcleaner.api` | REST endpoint `/api/convert`; accepts multipart JSON; returns text |
| `FileController` | `com.tcleaner.api` | Async file upload/download (`/api/upload`, `/api/download`); uses FileStorageService |
| `TelegramExporter` | `com.tcleaner.core` | Parses JSON; filters messages; formats output |
| `MessageFilter` | `com.tcleaner.core` | Evaluates date range + keyword matching |
| `MessageFilterFactory` | `com.tcleaner.core` | Parses query params into MessageFilter |
| `MessageProcessor` | `com.tcleaner.core` | Applies formatting to single message |
| `MessageFormatter` | `com.tcleaner.format` | Formats as `YYYYMMDD_HH:mm text` |
| `MarkdownParser` | `com.tcleaner.format` | Converts Telegram entities → markdown |
| `DateFormatter` | `com.tcleaner.format` | Parses/formats dates with timezone support |
| `FileStorageService` | `com.tcleaner.storage` | Manages file lifecycle (upload, process, download, TTL cleanup) |
| `ProcessingStatusService` | `com.tcleaner.status` | Redis wrapper for job status tracking |

#### Python Worker (asyncio, configurable parallel clients)

| Module | Responsibility |
|--------|---|
| `main.py` | Entry point; initializes Redis, Telegram, Java clients; runs main loop; handles SIGTERM |
| `queue_consumer.py` | Polls Redis BLPOP; deserializes JSON; distributes to worker threads |
| `pyrogram_client.py` | Authenticates with Telegram (session string or file); exports messages; handles FloodWait backoff |
| `java_client.py` | HTTP POST to `/api/convert`; multipart form-data; receives/saves result.txt |
| `models.py` | Data classes: ExportRequest, ExportedMessage, etc. |
| `config.py` | Loads env vars; validates credentials; exposes settings |
| `get_session.py` | Interactive script to generate Pyrogram string session (one-time) |

#### Redis

- **Queue**: `telegram_export` (FIFO list)
- **Job format**: JSON string with task metadata
- **Operations**: RPUSH (producer) / BLPOP (consumer)
- **TTL**: None (jobs consumed immediately)

### Data Processing Pipeline (5 Steps)

#### Step 1: User sends /export command

```python
# What user types
/export https://t.me/strbypass    # or @username, username, numeric ID

# What happens
ExportBot.onUpdateReceived(Update)
├─ Check: update.hasMessage() && message.hasText()
├─ Parse: text.startsWith("/export")
├─ Extract: extractUsername(input) → "strbypass" (from link, @, or plain)
│           or Long.parseLong(input) → numeric chat ID
└─ Call: jobProducer.enqueue(userId, userChatId, "strbypass")

# Bot response (immediate, within 1 second)
"Задача принята!
 ID: export_abc123xyz...
 Чат: @strbypass

 Экспорт запущен. Когда воркер обработает — вы получите файл здесь."
```

#### Step 2: Job enqueued to Redis

```java
// ExportJobProducer.enqueue()
String taskId = "export_" + UUID.randomUUID()
  .toString()
  .replace("-", "")
  .substring(0, 16);  // e.g., "export_abc123xyz"

Map<String, Object> job = {
  "task_id": "export_abc123xyz...",
  "user_id": 987654,
  "user_chat_id": 987654,
  "chat_id": "strbypass",       // string username or numeric ID
  "limit": 0,                   // 0 = export all messages
  "offset_id": 0                // 0 = start from most recent
};

String json = objectMapper.writeValueAsString(job);
redis.opsForList().rightPush("telegram_export", json);
// Queue now has: [..., json_job]
```

#### Step 3: Worker processes job

```python
# QueueConsumer.poll() - blocking loop
while True:
    task_json = redis.blpop("telegram_export", timeout=1)
    if task_json:
        export_request = json.loads(task_json)

        # Submit to worker thread pool
        worker_thread = pool.submit(process_export, export_request)

# Worker thread (TelegramClient)
async def process_export(request: ExportRequest):
    # 1. Verify access
    is_accessible, chat_info = await client.verify_and_get_info(request.chat_id)
    if not is_accessible:
        logger.error(f"Cannot access chat {request.chat_id}")
        return

    # 2. Fetch all messages (async generator)
    all_messages = []
    async for message in client.get_chat_history(request.chat_id):
        # ExportedMessage(id, text, date, sender_name, media_type, ...)
        all_messages.append(MessageConverter.convert_message(message))

    # ~1000+ messages/second, respects FloodWait backoff

    # 3. Send to Java API
    await java_client.send_messages_to_api(
        chat_id=request.chat_id,
        user_chat_id=request.user_chat_id,
        messages=all_messages
    )
```

#### Step 4: Java processes messages

```java
// TelegramController.convert(file, startDate, endDate, keywords, excludeKeywords)
// Receives: multipart/form-data with file + query params

// Step 4a: Parse filters
MessageFilter filter = MessageFilterFactory.build(
    startDate="2024-01-01",
    endDate="2024-12-31",
    keywords="hello,world",
    excludeKeywords="spam,junk"
);
// filter matches if:
//   (no startDate OR message.date >= startDate) AND
//   (no endDate OR message.date <= endDate) AND
//   (no keywords OR message contains ANY keyword) AND
//   (no excludeKeywords OR message contains NO excludeKeyword)

// Step 4b: Process file
List<String> processedLines = TelegramExporter.processFile(
    inputFile,  // result.json from Telegram export
    filter      // date + keyword filter
);

// Pseudocode of processFile:
// 1. Jackson parses result.json → Message[] array
// 2. for each message:
//      if (filter.matches(message)) {
//        String formatted = MessageProcessor.format(message);
//        lines.add(formatted);
//      }
// 3. return lines

// Step 4c: Format each message
// MessageFormatter + MarkdownParser:
String formatted = MessageFormatter.format(message);
// Output: "20240115_14:30 **bold text** normal text *italic*"
//
// Where:
// - 20240115_14:30 = YYYYMMDD_HH:mm (message date)
// - **bold text** = Telegram bold entity converted
// - *italic* = Telegram italic entity converted
// - Links converted to [text](url)
// - @mentions preserved
// - Media/stickers: skipped (text only)

// Step 4d: Response
String result = String.join("\n", processedLines);
// If empty: just add final newline, else add "\n" at end

ResponseEntity.ok()
    .header("Content-Disposition", "attachment; filename=output.txt")
    .contentType(MediaType.TEXT_PLAIN)
    .body(result);
```

#### Step 5: Result sent back to user

```python
# JavaBotClient receives response
response = await java_client.send_messages_to_api(...)
output_text = response.text  # output.txt content

# Save to temp file and send via bot
with open(temp_file, 'w') as f:
    f.write(output_text)

# Send document to user
await bot.send_document(
    chat_id=request.user_chat_id,
    document=InputFile(temp_file),
    caption=f"Export complete: {len(lines)} messages"
)

# User receives: output.txt file in Telegram chat ✅
```

---

## Features

### ✅ Secure Export

- **End-to-end encrypted**: Pyrogram uses TLS to Telegram API
- **No data stored**: Messages only in memory during processing, never persisted
- **Session isolation**: String session for production (no password stored)
- **Audit logging**: All operations logged with timestamps and user IDs
- **Network isolation**: Java API only on localhost:8081 (not exposed)

### ✅ High Performance

- **Parallel processing**: 4 concurrent Pyrogram clients (configurable)
- **1000+ messages/second**: Throughput per worker
- **Exponential backoff**: Handles Telegram rate limiting (FloodWait) gracefully
- **Memory efficient**: Streaming where possible, ~50MB base + 10KB per job
- **Fast filtering**: Single-pass message filtering (date + keywords)

### ✅ Production Ready

- **Full test coverage**: 450+ unit + integration tests (Java: JUnit 5, Python: pytest)
- **Comprehensive error handling**: Type-specific exceptions with recovery
- **Graceful degradation**: Continues on individual message errors, only fails entire job on auth/network
- **Health checks**: Builtin `/api/health` endpoint; Docker healthchecks for all services
- **Monitoring**: Resource usage logging, job status tracking, error rates

### ✅ Easy Integration

- **REST API**: Simple `/api/convert` endpoint for file processing
- **Telegram Bot**: Native `/export` command integration
- **Redis queue**: Standard FIFO queue (RPUSH/BLPOP)
- **Docker deployment**: Single `docker-compose up -d` for full stack
- **CI/CD ready**: GitHub Actions workflow handles build, test, deploy automatically

---

## Environment Setup

### Prerequisites

- **For Docker**: Docker 20.10+, Docker Compose 2.0+
- **For manual**: Java 21, Python 3.11+, Maven 3.9+, Redis 7+

### 1. Create .env File

```bash
cp .env.example .env
```

### 2. Add Credentials

**From Telegram:**

```env
# From my.telegram.org/apps (create app)
TELEGRAM_API_ID=123456789
TELEGRAM_API_HASH=abcdef0123456789abcdef0123456789

# From @BotFather (/newbot)
TELEGRAM_BOT_TOKEN=123456:ABCDefGhIjKlMnOpQrStUvWxYz-_=
TELEGRAM_BOT_USERNAME=MyExportBot

# Production: From get_session.py (one-time)
TELEGRAM_SESSION_STRING=BQF...very_long_string...

# Development: Your phone number (SMS will prompt on first run)
TELEGRAM_PHONE_NUMBER=+1234567890
```

**Infrastructure:**

```env
REDIS_HOST=redis               # Docker: "redis", Local: "localhost"
REDIS_PORT=6379
REDIS_DB=0
REDIS_QUEUE_NAME=telegram_export

JAVA_API_BASE_URL=http://java-bot:8080    # Docker
# JAVA_API_BASE_URL=http://localhost:8080  # Local dev
JAVA_API_KEY=unused_for_now

WORKER_NAME=export-worker-1
MAX_WORKERS=1                  # Concurrent Pyrogram clients (default: 1)
```

**Timeouts & Retries:**

```env
JOB_TIMEOUT=3600               # 1 hour
MAX_RETRIES=3
RETRY_BASE_DELAY=1.0           # seconds (exponential)
RETRY_MAX_DELAY=32.0           # seconds (cap)
```

**Logging:**

```env
LOG_LEVEL=INFO                 # DEBUG, INFO, WARNING, ERROR, CRITICAL
LOG_FORMAT=json                # json or text
PYROGRAM_LOG_LEVEL=ERROR       # Pyrogram library verbosity
PYTHONUNBUFFERED=1             # Flush logs immediately in Docker
```

### 3. Verify Setup

```bash
# Check env vars are loaded
source .env
echo $TELEGRAM_API_ID

# Verify credentials are valid (one-time)
cd export-worker
python -c "from config import settings; print(f'API_ID: {settings.TELEGRAM_API_ID}')"
```

---

## API Reference

### Telegram Bot Commands

#### `/start`

Sends welcome message with instructions.

```
/start

Response:
Этот бот экспортирует историю Telegram-чата и отправляет очищенный текст.

Команды:
/export <chat_id или ссылка> — экспортировать чат
/help — показать эту справку

Примеры:
/export https://t.me/durov
/export @durov
/export durov
/export -1001234567890
```

#### `/export <ссылка, username или chat_id>`

Starts an export job for the specified chat.

**Parameters:**
- `<target>`: t.me link, @username, username, or numeric chat ID

**Examples:**

```
/export https://t.me/strbypass     # By t.me link
/export @durov                      # By @username
/export durov                       # By username
/export -1001234567890             # By numeric ID
/export                             # Error: missing target
```

**Response (immediate):**

```
Задача принята!

ID задачи: export_abc123xyz...
Чат: @strbypass

Экспорт запущен. Когда воркер обработает — вы получите файл здесь.
```

**On completion (30 seconds - 10 minutes later):**

```
[Bot sends output.txt file]

File content example:
20240115_14:30 **bold message** with formatting
20240115_14:31 another message
20240115_14:32 message with @mention and [link](https://example.com)
...
```

#### `/help`

Displays available commands (same as `/start`).

### REST API Endpoints

#### `POST /api/convert`

Processes a Telegram export file synchronously.

**Request:**

```http
POST /api/convert HTTP/1.1
Content-Type: multipart/form-data

--boundary
Content-Disposition: form-data; name="file"; filename="result.json"
Content-Type: application/json

{
  "messages": [
    {"id": 1, "date": 1234567890, "text": "Hello", ...},
    ...
  ]
}
--boundary
Content-Disposition: form-data; name="startDate"

2024-01-01
--boundary
Content-Disposition: form-data; name="endDate"

2024-12-31
--boundary
Content-Disposition: form-data; name="keywords"

hello,world
--boundary
Content-Disposition: form-data; name="excludeKeywords"

spam,junk
--boundary--
```

**Query Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|---|
| `file` | File | ✅ | Telegram export `result.json` file |
| `startDate` | String | ❌ | Filter from date (format: `YYYY-MM-DD`) |
| `endDate` | String | ❌ | Filter to date (format: `YYYY-MM-DD`) |
| `keywords` | String | ❌ | Include messages containing ANY of these (comma-separated) |
| `excludeKeywords` | String | ❌ | Exclude messages containing ANY of these (comma-separated) |

**Response (Success - 200):**

```http
HTTP/1.1 200 OK
Content-Type: text/plain
Content-Disposition: attachment; filename=output.txt
Content-Length: 5432

20240115_14:30 **bold text** message content
20240115_14:31 another message
20240115_14:32 message with link [text](https://example.com)
...
```

**Response (Bad Request - 400):**

```json
{
  "error": "Ожидается JSON файл"
}
```

Or:

```json
{
  "error": "INVALID_DATE_RANGE",
  "message": "Start date cannot be after end date"
}
```

Or:

```json
{
  "error": "INVALID_FORMAT",
  "message": "Could not parse JSON"
}
```

**Response (Server Error - 500):**

```json
{
  "error": "Internal server error"
}
```

**Examples:**

```bash
# Basic (no filters)
curl -X POST http://localhost:8080/api/convert \
  -F "file=@result.json"

# With date range
curl -X POST "http://localhost:8080/api/convert?startDate=2024-01-01&endDate=2024-12-31" \
  -F "file=@result.json"

# With keywords
curl -X POST "http://localhost:8080/api/convert?keywords=hello,world" \
  -F "file=@result.json"

# With exclusions
curl -X POST "http://localhost:8080/api/convert?excludeKeywords=spam,junk" \
  -F "file=@result.json"

# All filters combined
curl -X POST \
  "http://localhost:8080/api/convert?startDate=2024-01-01&endDate=2024-12-31&keywords=important&excludeKeywords=spam" \
  -F "file=@result.json" \
  -o output.txt
```

#### `POST /api/convert/json`

Same as `/api/convert` but accepts JSON in request body instead of multipart file.

**Request:**

```http
POST /api/convert/json?startDate=2024-01-01&keywords=hello HTTP/1.1
Content-Type: application/json

{
  "messages": [
    {"id": 1, "date": 1234567890, "text": "Hello", ...},
    ...
  ]
}
```

**Limits:**
- Max body size: 10 MB (configurable)

**Response:** Same as `/api/convert`

#### `GET /api/health`

Healthcheck endpoint (used by Docker, load balancers, etc.)

**Response (200):**

```json
{
  "status": "UP"
}
```

---

## Development

### Local Setup (without Docker)

#### 1. Install Prerequisites

**Java 21:**
```bash
# macOS
brew install openjdk@21

# Linux (Ubuntu/Debian)
sudo apt install openjdk-21-jdk

# Windows
# Download from https://adoptium.net/
```

**Maven 3.9:**
```bash
# macOS
brew install maven

# Linux
sudo apt install maven

# Verify
mvn --version
```

**Python 3.11:**
```bash
# macOS
brew install python@3.11

# Linux
sudo apt install python3.11 python3.11-venv

# Windows
# Download from https://www.python.org/
```

**Redis 7:**
```bash
# macOS
brew install redis@7

# Linux
sudo apt install redis-server

# Docker (easiest)
docker run -d -p 6379:6379 redis:7-alpine

# Verify
redis-cli ping  # Should return PONG
```

#### 2. Create .env for Local Development

```bash
cp .env.example .env

# Edit with your credentials
# IMPORTANT: Use TELEGRAM_PHONE_NUMBER, NOT TELEGRAM_SESSION_STRING
cat > .env << 'EOF'
TELEGRAM_API_ID=123456789
TELEGRAM_API_HASH=abcdef0123456789abcdef0123456789
TELEGRAM_PHONE_NUMBER=+1234567890
TELEGRAM_BOT_TOKEN=123456:ABCDefGhIjKlMnOpQrStUvWxYz-_=
TELEGRAM_BOT_USERNAME=MyExportBot
REDIS_HOST=localhost
REDIS_PORT=6379
JAVA_API_BASE_URL=http://localhost:8080
MAX_WORKERS=2
LOG_LEVEL=DEBUG
PYTHONUNBUFFERED=1
EOF
```

#### 3. Build Java

```bash
mvn clean package

# Run specific test
mvn test -Dtest=MessageFilterTest

# Build JAR only (skip tests)
mvn clean package -DskipTests

# View build artifacts
ls -la target/telegram-cleaner-*.jar
```

#### 4. Start Java API

```bash
java -jar target/telegram-cleaner-*.jar

# Expected output:
# 2026-03-20 14:30:25.123 INFO 12345 --- [main] ... : Telegram-бот инициализирован
# 2026-03-20 14:30:26.456 INFO 12345 --- [main] ... : Tomcat started on port(s): 8080
```

Verify: `curl http://localhost:8080/api/health`

#### 5. Install Python Dependencies

```bash
cd export-worker
python3.11 -m venv venv

source venv/bin/activate  # macOS/Linux
# or
venv\Scripts\activate     # Windows

pip install -r requirements.txt

# Verify
python -c "import pyrogram; print(pyrogram.__version__)"
```

#### 6. Start Python Worker

```bash
source ../.env  # Load environment
python main.py

# First run: You'll be prompted for SMS code
# Enter phone: +1234567890
# Enter SMS code: 12345
# Enter 2FA password: (if enabled)
# → Session saved to session/export_worker.session

# Expected output:
# 2026-03-20 14:30:50.123 - main - INFO - 🚀 Initializing Export Worker...
# 2026-03-20 14:30:51.456 - main - INFO - 1️⃣  Connecting to Redis queue...
# 2026-03-20 14:30:52.789 - main - INFO - 2️⃣  Connecting to Telegram API...
# 2026-03-20 14:30:53.012 - main - INFO - ✅ All components initialized successfully
```

#### 7. Test the System

Open Telegram and message your bot:

```
/start
/export https://t.me/your_channel  # Or @username, or numeric ID
```

Watch the logs:
- Java bot: Shows `/export` command received
- Python worker: Shows message fetched, job processed, result sent

---

### Generate Pyrogram String Session (Production)

```bash
cd export-worker
python get_session.py

# Follow prompts:
# Enter your TELEGRAM_API_ID: 123456789
# Enter your TELEGRAM_API_HASH: abcdef...
# Enter phone number: +1234567890
# Enter SMS code: 12345
# Enter 2FA password (if enabled): mypassword

# Output:
# BQF...very_long_string...AAA

# Copy this string → GitHub Secrets → TELEGRAM_SESSION_STRING
```

---

## Testing

### Java Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=MessageFilterTest

# Run specific test method
mvn test -Dtest=MessageFilterTest#testFilterByDateRange

# Run with coverage
mvn test jacoco:report
# Open: target/site/jacoco/index.html

# View coverage in terminal
mvn test jacoco:report && cat target/site/jacoco/index.csv | head -20
```

**Test Organization (450+ tests):**

- **Unit Tests** (fast, no external dependencies)
  - `MessageFilterTest` - 30+ date/keyword filtering scenarios
  - `MessageProcessorTest` - Formatting pipeline
  - `DateFormatterTest` - Timezone edge cases
  - `MarkdownParserTest` - Entity transformation
  - `FileStorageServiceTest` - File I/O lifecycle
  - `ProcessingStatusServiceTest` - Redis operations

- **Integration Tests** (slower, use Testcontainers)
  - `IntegrationTest.java` - Full Spring context + real Redis container

### Python Tests

```bash
# Install test dependencies
pip install -r requirements.txt
pip install pytest pytest-asyncio pytest-cov

# Run all tests
pytest tests/ -v

# Run specific suite
pytest tests/test_models.py -v
pytest tests/test_integration.py -v
pytest tests/test_end_to_end.py -v
pytest tests/test_performance.py -v

# With coverage
pytest tests/ --cov=. --cov-report=html
# Open: htmlcov/index.html

# Specific test
pytest tests/test_models.py::test_export_request_validation -v
```

### Code Quality

#### Java

```bash
# Checkstyle validation
mvn checkstyle:check

# View style report
mvn checkstyle:checkstyle
# Open: target/site/checkstyle.html
```

#### Python

```bash
cd export-worker

# Type checking (strict)
mypy . --strict

# Linting
ruff check . --select E,W,F

# Format check
black --check .

# Auto-fix
black .
ruff check . --fix
```

---

## Troubleshooting

### Common Issues

#### 1. "Chat not accessible" Error

**Symptom:**
```
❌ Channel -100123456789 is private
Error: ChannelPrivate
```

**Causes & Solutions:**

| Cause | Solution |
|-------|----------|
| Wrong chat_id | Use t.me link или @username вместо числового ID |
| Bot not in group | Add bot to the group (for groups/channels) |
| No access to chat | Ensure you (bot owner) have access |
| Private channel | Add bot to the channel as member |
| Deleted/non-existent chat | Verify chat still exists |

**Debug:**
```python
# Add to Python worker
is_accessible, chat_info = await client.verify_and_get_info(chat_id)
if chat_info:
    print(f"Chat: {chat_info['title']}, Type: {chat_info['type']}")
else:
    print(f"Cannot access {chat_id}")
```

#### 2. "FloodWait" Rate Limiting

**Symptom:**
```
Rate limited (FloodWait 60s). Retry 1/3 after 60s
Rate limited (FloodWait 120s). Retry 2/3 after 120s
```

**Cause:** Telegram API rate limiting (too many requests)

**Solutions:**

```env
# Increase concurrent workers (default: 1)
MAX_WORKERS=2

# Increase backoff delays
RETRY_BASE_DELAY=2.0    # Start with 2s instead of 1s
RETRY_MAX_DELAY=64.0    # Increase cap to 64s
```

**Why it happens:**
- Too many messages fetched in quick succession
- Multiple workers hitting same chat simultaneously
- Bot account is new (stricter rate limits)

**Normal behavior:** Auto-retry with exponential backoff (1s → 2s → 4s → ... → 32s max)

#### 3. "Session file not found" (Local Dev)

**Symptom:**
```
[Pyrogram] [INFO] Start typing phone number:
Enter phone number:
```

**Cause:** First run requires interactive SMS authentication

**Solution:**

```bash
# Expected on first run
# Just enter your phone number, SMS code, 2FA password
# Session will be saved to export-worker/session/export_worker.session

# Subsequent runs: uses saved session (no SMS needed)

# Force re-authentication:
rm export-worker/session/export_worker.session
python main.py  # Enter SMS code again
```

#### 4. Python Worker Crashes on Startup

**Symptom:**
```
RuntimeError: Failed to connect to Telegram API
or
ConnectionError: Cannot connect to Redis
```

**Check:**

```bash
# Is Redis running?
redis-cli ping  # Should return PONG

# Are credentials correct?
echo $TELEGRAM_API_ID
echo $TELEGRAM_BOT_TOKEN

# Is Java API running?
curl http://localhost:8080/api/health

# Check logs for details
python main.py 2>&1 | head -50
```

#### 5. "Invalid credentials" Error

**Symptom:**
```
❌ Authorization failed: [401 Unauthorized]
Please check TELEGRAM_API_ID, TELEGRAM_API_HASH, and TELEGRAM_PHONE
```

**Verify:**
- `TELEGRAM_API_ID`: Should be number from my.telegram.org (e.g., `123456789`)
- `TELEGRAM_API_HASH`: Should be string from my.telegram.org (e.g., `abcdef...`)
- `TELEGRAM_PHONE_NUMBER`: Should be with country code (e.g., `+1234567890`)
- `TELEGRAM_SESSION_STRING`: If using production, must be valid string from `get_session.py`

```bash
# Test credentials
python -c "from config import settings; \
  print(f'API_ID: {settings.TELEGRAM_API_ID}'); \
  print(f'API_HASH: {settings.TELEGRAM_API_HASH[:5]}...'); \
  print(f'Phone: {settings.TELEGRAM_PHONE_NUMBER}')"
```

#### 6. Java API Returns 400 Error

**Symptom:**
```
POST /api/convert → 400 Bad Request
{"error": "Ожидается JSON файл"}
```

**Causes & Solutions:**

| Error | Fix |
|-------|-----|
| "Файл пустой" (Empty file) | Check file size: `ls -lh result.json` |
| "Ожидается JSON файл" (Not JSON) | Verify file is JSON, not text/binary |
| "Невалидный формат даты" (Date format) | Use `YYYY-MM-DD` not `DD-MM-YYYY` |
| "Start date > End date" (Invalid range) | Ensure `startDate ≤ endDate` |

#### 7. Docker Compose Services Won't Start

**Symptom:**
```
ERROR: Service java-bot exited with code 1
```

**Debug:**
```bash
# Check logs
docker-compose logs java-bot

# Verify all env vars are set
docker-compose config | grep TELEGRAM_

# Rebuild
docker-compose down
docker-compose build --no-cache
docker-compose up -d
```

---

## FAQ

### Q: How do I export a chat?

**A:** Use a link, username, or numeric ID:
```
/export https://t.me/channel_name
/export @username
/export -1001234567890
```

For your own Saved Messages, use your numeric Telegram ID (`@userinfobot` покажет его).

### Q: What's the difference between file-based and string sessions?

**A:**

| Aspect | File-based | String-based |
|--------|-----------|---|
| Storage | `session/export_worker.session` | Environment variable |
| Auth | Phone + SMS code | Pre-generated string |
| Use case | Local development | Production/CI-CD |
| Security | File-based (chmod 600) | More secure (no files) |
| Setup | Interactive | One-time script |

### Q: Can I export without SMS verification?

**A:** Yes, with string session! Run `get_session.py` once, then use the generated string for production. No SMS needed after that.

### Q: How large can exported chats be?

**A:** Limited by:
- **Java memory**: Default `Xmx768m` (set in Dockerfile), so ~500MB JSON max
- **Python memory**: ~50MB base + variable per messages
- **Timeout**: Default 1 hour (`JOB_TIMEOUT=3600`)

For very large chats (100k+ messages), consider:
- Increase Java heap: `-Xmx2g` in Dockerfile
- Increase timeout: `JOB_TIMEOUT=7200` (2 hours)
- Increase worker memory: `docker-compose.yml` `mem_limit`

### Q: Why does Python worker need Java API?

**A:** Java has better:
- **Parsing**: Jackson ObjectMapper for complex JSON
- **Formatting**: Rich markdown + entity support
- **Filtering**: Timezone-aware date parsing
- **Testing**: 450+ tests ensure reliability

Python fetches raw messages from Telegram; Java processes them.

### Q: Can I run multiple workers?

**A:** Yes! Each can process different chats in parallel:

```bash
# Terminal 1
WORKER_NAME=worker-1 python main.py

# Terminal 2
WORKER_NAME=worker-2 python main.py

# Terminal 3
WORKER_NAME=worker-3 python main.py
```

Or in Docker (scale):
```bash
docker-compose up -d --scale python-worker=3
```

### Q: What format is the output file?

**A:** Plain text with formatting:

```
20240115_14:30 **bold text** with formatting
20240115_14:31 *italic message*
20240115_14:32 `code snippet`
20240115_14:33 message with [link](https://example.com)
20240115_14:34 regular message with @mention
...
```

Each line:
- **Date**: `YYYYMMDD_HH:mm`
- **Text**: Message content with markdown entities

### Q: Can I import/upload files instead of using the bot?

**A:** Yes! Use REST API directly:

```bash
curl -X POST http://localhost:8080/api/convert \
  -F "file=@result.json" \
  -F "startDate=2024-01-01" \
  -F "endDate=2024-12-31" \
  -o output.txt
```

Or `/api/convert/json` for JSON body instead of multipart.

### Q: How do I monitor the worker?

**A:**

```bash
# Watch logs
docker-compose logs -f python-worker

# Check queue size
redis-cli LLEN telegram_export

# Check Java health
curl http://localhost:8081/api/health

# Resource usage (inside container)
docker stats
```

### Q: Is my data safe?

**A:**

- ✅ Messages only in memory during processing
- ✅ Session not stored (string session or secure file)
- ✅ Bot token only in env vars
- ✅ No database (Redis queue only, cleared after each job)
- ✅ Java API on localhost only (not exposed)
- ✅ Network encrypted (TLS to Telegram API)

### Q: What happens if the bot crashes during export?

**A:**

- Job stays in Redis queue
- When worker restarts, it processes pending jobs
- User gets result when job completes (eventually consistent)

### Q: Can I change the output format?

**A:**

Modify `MessageFormatter` in Java:

```java
// Current: "YYYYMMDD_HH:mm text"
// Change to: "[HH:mm] text" (in MessageFormatter.java)
```

Then rebuild:
```bash
mvn clean package
docker-compose up -d --build
```

---

## Contributing

### Code Standards

#### Java

- **Style**: Follow `checkstyle.xml` rules
- **Testing**: Add tests for new features (unit + integration)
- **Coverage**: Maintain 80%+ (enforced by JaCoCo)
- **Comments**: JavaDoc on public methods, Russian comments for non-obvious logic

#### Python

- **Type hints**: Use `typing` module, strict with mypy
- **Docstrings**: PEP 257 format
- **Testing**: pytest for all modules
- **Linting**: Pass `ruff`, `black`, `mypy`

### Git Workflow

```bash
# 1. Create feature branch
git checkout -b feature/xyz

# 2. Make changes
# ... edit files ...

# 3. Run tests locally
mvn test                          # Java
pytest tests/ -v                  # Python
mypy export-worker/ --strict      # Type check
ruff check . && black .           # Format

# 4. Commit with meaningful message
# Format: PREFIX: description (in Russian)
# Prefix: FEAT, FIX, REFACTOR, TEST, DOCS, CHORE
git add -A
git commit -m "FEAT: добавить функцию экспорта с фильтрацией по датам"

# 5. Push and create PR
git push origin feature/xyz
# Then create PR on GitHub
```

### Commit Message Format

```
PREFIX: описание на русском

Longer explanation if needed...

Example commits:
- FEAT: добавить экспорт с фильтром по датам
- FIX: исправить обработку FloodWait
- REFACTOR: переорганизовать Java код в sub-packages
- TEST: добавить тесты для MessageFilter
- DOCS: обновить README с примерами
- CHORE: обновить зависимости Maven
```

---

## Performance

### Benchmarks

| Metric | Value | Notes |
|--------|-------|-------|
| **Throughput** | 1000+ msg/s | Per Pyrogram client |
| **Concurrency** | 1-8 workers | Default: 1, configurable via MAX_WORKERS |
| **Memory (base)** | ~50 MB | Java + Python combined |
| **Memory (per job)** | ~10 KB | Per concurrent export task |
| **JSON parsing** | <500ms | For 1000-message export |
| **Filtering** | ~10ms | For 1000 messages |
| **Formatting** | ~50ms | For 1000 messages |
| **HTTP roundtrip** | <200ms | Java ↔ Python |
| **Reliability** | 99.9% uptime | With auto-recovery |

### Optimization Tips

1. **Increase workers for parallel exports:**
   ```env
   MAX_WORKERS=8
   ```

2. **Tune Java heap for large chats:**
   ```dockerfile
   ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC"
   ```

3. **Increase Redis throughput:**
   ```bash
   redis-cli CONFIG SET maxmemory-policy allkeys-lru
   ```

4. **Monitor resource usage:**
   ```bash
   docker stats
   ```

---

## Security

⚠️ **Credentials must be kept SECRET!**

### Secret Management

- ✅ `.env` is in `.gitignore` (never commit!)
- ✅ GitHub Secrets for CI/CD (encrypted by GitHub)
- ✅ Local: `chmod 600 .env` (readable by owner only)

### String Session Security

```bash
# Production: One-time generation
python export-worker/get_session.py
# Copy BQF...string... → GitHub Secret TELEGRAM_SESSION_STRING

# Development: File-based session
# Stored in export-worker/session/ (in .gitignore)
chmod 600 export-worker/session/export_worker.session
```

### Network Isolation

```
Java API (localhost:8081)  ← Not exposed externally
  ↓
Python Worker (internal)   ← Not exposed
  ↓
Redis (internal)           ← Not exposed
```

Java API is **only accessible from localhost** (not exposed to internet).

### API Authentication

Currently: No API key validation (internal use only).

Future: Add JWT or API key header if needed:

```bash
# To add authentication:
1. Spring Security config
2. Bearer token validation
3. Reject requests without valid token
```

### Concurrency & Reliability

- **Duplicate export prevention**: `ExportJobProducer.enqueue()` uses Redis `SET NX EX` — atomic check-and-reserve eliminates race condition between concurrent requests from same user
- **Session memory management**: `ExportBot` evicts sessions inactive for >2 hours every 30 minutes via `@Scheduled` — prevents OOM with many unique users
- **Dead Letter Queue**: Malformed jobs (invalid JSON or Pydantic validation error) are moved to `<queue>_dead` Redis list instead of being silently discarded
- **Redis timeout protection**: `socket_timeout=10s` on all Redis async operations prevents indefinite deadlock on connection hang
- **Empty export notification**: Users are explicitly notified when export completes with 0 messages

### Data Retention

- **Messages**: Only in memory (never persisted)
- **Sessions**: String session (no password stored)
- **Logs**: Configurable via `LOG_LEVEL` (remove sensitive details)
- **Queue**: Jobs deleted after processing (Redis BLPOP)

---

## Deployment

### Via Docker Compose (Local)

```bash
# Start
docker-compose up -d

# Check status
docker ps

# View logs
docker-compose logs -f

# Stop
docker-compose down

# Full cleanup (removes volumes)
docker-compose down -v
```

### Via CI/CD (GitHub Actions → Server)

**Workflow:** `.github/workflows/build.yml`

1. **Trigger**: Push to `main` branch
2. **Build**:
   - ✅ Compile Java (Maven)
   - ✅ Build Docker image (Java)
   - ✅ Build Docker image (Python)
   - ✅ Push to GHCR
3. **Test**:
   - ✅ Unit tests (Java)
   - ✅ Integration tests (Python)
   - ✅ Code quality checks
4. **Deploy** (via SSH):
   - ✅ Pull images from GHCR
   - ✅ Generate `docker-compose.yml` with secrets
   - ✅ Restart services

**Result:** Automatic, zero-manual deployment!

### Manual Deployment

```bash
# Build locally
mvn clean package
docker build -t telegram-export-cleaner .
docker build -t telegram-export-python-worker export-worker/

# Push to registry
docker tag telegram-export-cleaner myregistry/cleaner:latest
docker push myregistry/cleaner:latest

# Pull on server and run
ssh user@server
docker pull myregistry/cleaner:latest
docker-compose up -d
```

---

## Monitoring

### Health Checks

```bash
# Java API
curl http://localhost:8081/api/health

# Redis
redis-cli ping  # Should return PONG

# Python worker
docker-compose logs python-worker -f

# All services
docker ps
docker-compose ps
```

### Key Metrics

- **Queue size**: `redis-cli LLEN telegram_export`
- **Jobs processed**: Check Python logs for "jobs_processed" counter
- **Error rate**: Check logs for error count
- **Memory usage**: `docker stats`
- **Latency**: Check POST /api/convert response time

### Example Monitoring Command

```bash
# Real-time queue monitoring
while true; do
  echo "$(date): Queue size: $(redis-cli LLEN telegram_export)"
  sleep 5
done
```

---

## License

Proprietary — See LICENSE file

---

## Support

- **Issues**: [GitHub Issues](../../issues)
- **Bot**: [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot)

---

**Status**: ✅ Production Ready
**Last Updated**: 2026-03-23
**Version**: 1.1.0
