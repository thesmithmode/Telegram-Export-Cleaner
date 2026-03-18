# 📡 Export Worker Integration Guide

**Document Version:** 1.0
**Date:** 2026-03-18
**Status:** Implementation Ready

---

## 🎯 Overview

Export Worker is a Python service that handles Telegram message export jobs from the Redis queue. It:

1. **Consumes** export requests from Redis queue
2. **Connects** to Telegram API via Pyrogram
3. **Exports** messages in Telegram Desktop format
4. **Sends** results back to Java Bot API via HTTP

---

## 🏗️ System Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         Java Bot (Spring)                        │
│                                                                  │
│  1. User requests export via /export command                     │
│  2. Bot creates ExportRequest and pushes to Redis queue          │
│  3. Bot returns task_id to user                                  │
│  4. Bot waits for callback with results                          │
└──────────────────────────────────────────────────────────────────┘
              │
              │ RPUSH (Redis)
              ↓
┌──────────────────────────────────────────────────────────────────┐
│                      Redis Queue (RQ)                            │
│                                                                  │
│  Queue: "telegram_export"                                        │
│  Format: JSON (ExportRequest)                                    │
└──────────────────────────────────────────────────────────────────┘
              │
              │ BLPOP (Redis)
              ↓
┌──────────────────────────────────────────────────────────────────┐
│                  Export Worker (Python)                          │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ main.py: Main Loop                                      │   │
│  │  - Get job from queue                                   │   │
│  │  - Process job                                          │   │
│  │  - Send response                                        │   │
│  └─────────────────────────────────────────────────────────┘   │
│          │           │            │                             │
│          ↓           ↓            ↓                             │
│   ┌──────────┐ ┌──────────┐ ┌──────────────┐                   │
│   │Pyrogram  │ │Message   │ │Java Client   │                   │
│   │Client    │ │Converter │ │(HTTP POST)   │                   │
│   └──────────┘ └──────────┘ └──────────────┘                   │
│        │                            │                           │
│        ↓ Telegram API              ↓ HTTP                      │
└────────────────────────────────────────────────────────────────┘
         │                            │
         ↓                            ↓
    Telegram             ┌──────────────────────────────────┐
    Servers             │     Java Bot (Callback)          │
                        │ POST /api/export/callback        │
                        │ Response: ExportResponse         │
                        └──────────────────────────────────┘
```

---

## 📋 Data Models

### ExportRequest (Queue → Worker)

```python
{
  "task_id": "export_12345",        # Unique task ID
  "user_id": 123456789,              # Telegram user ID
  "chat_id": -1001234567890,         # Telegram chat ID
  "limit": 1000,                     # Max messages (0 = all)
  "offset_id": 0,                    # Start from message ID
  "from_date": "2025-01-01T00:00:00", # Filter from date
  "to_date": "2025-12-31T23:59:59"    # Filter to date
}
```

### ExportResponse (Worker → Queue → Java Bot)

```python
{
  "task_id": "export_12345",
  "status": "completed",             # completed | failed | in_progress
  "message_count": 100,
  "exported_at": "2025-06-24T15:30:00",
  "messages": [                      # List of ExportedMessage
    {
      "id": 1,
      "type": "message",
      "date": "2025-06-24T15:29:46",
      "text": "Hello world",
      "from": "John Doe",
      "from_id": {"peer_type": "user", "peer_id": 456},
      "text_entities": [
        {"type": "bold", "offset": 0, "length": 5}
      ],
      "media_type": "photo",
      "photo": "photo_123.jpg",
      "width": 1024,
      "height": 768
    }
  ],
  "error": null,
  "error_code": null
}
```

---

## 🚀 Quick Start

### 1. Copy Configuration

```bash
# Copy environment template
cp .env.example .env

# Edit with your credentials
nano .env
```

**Required settings:**
```env
TELEGRAM_API_ID=your_api_id
TELEGRAM_API_HASH=your_api_hash
TELEGRAM_PHONE=+1234567890
JAVA_API_KEY=your_shared_secret
```

### 2. Start Services

```bash
# Start Redis + Export Worker
docker-compose up -d

# Check logs
docker-compose logs -f export-worker
```

### 3. First Run (Authentication)

On first run, Pyrogram will:
1. Ask for your Telegram account credentials
2. Ask for 2FA code (if enabled)
3. Create session file in `export-worker/session/`

**This session file is persistent** and will be reused for subsequent runs.

```bash
# Watch for 2FA prompt
docker-compose logs -f export-worker

# If stuck on auth, restart:
docker-compose restart export-worker
```

### 4. Test Export Job

Use Java Bot to request an export:

```
/export chat_id=@mychannel limit=100
```

Export Worker will:
1. Get job from Redis
2. Verify access to chat
3. Stream messages from Telegram
4. POST results to Java Bot API endpoint
5. Mark job as completed

---

## 🔌 Integration with Java Bot

### Java Bot → Export Worker

**Job Queue (Redis RPUSH):**

```java
// In Java Bot controller
@PostMapping("/export")
public ResponseEntity<?> startExport(@RequestBody ExportRequest request) {
    String jobJson = objectMapper.writeValueAsString(request);
    redisTemplate.opsForList().rightPush("telegram_export", jobJson);

    return ResponseEntity.ok(new TaskResponse(request.getTaskId()));
}
```

### Export Worker → Java Bot

**HTTP Callback (POST):**

```python
# In export-worker/java_client.py
async def send_response(self, response: ExportResponse) -> bool:
    url = f"{settings.JAVA_API_BASE_URL}/api/export/callback"

    async with httpx.AsyncClient() as client:
        response = await client.post(
            url,
            json=response.model_dump(exclude_none=True),
            headers={"Authorization": f"Bearer {settings.JAVA_API_KEY}"}
        )

    return response.status_code == 200
```

### Java Bot Callback Handler

**Expected endpoint in Java Bot:**

```java
@PostMapping("/api/export/callback")
@PreAuthorize("hasRole('EXPORT_WORKER')")  // Verify API key
public ResponseEntity<?> exportCallback(@RequestBody ExportResponse response) {

    // 1. Find original task
    ExportTask task = taskService.findByTaskId(response.getTaskId());

    // 2. Handle response
    if ("completed".equals(response.getStatus())) {
        // Save results
        exportService.saveMessages(task, response.getMessages());

        // Notify user
        botService.sendToUser(
            task.getUserId(),
            String.format("✅ Export completed: %d messages", response.getMessageCount())
        );
    } else if ("failed".equals(response.getStatus())) {
        // Handle error
        botService.sendToUser(
            task.getUserId(),
            String.format("❌ Export failed: %s", response.getError())
        );
    }

    return ResponseEntity.ok().build();
}
```

---

## 🔒 Security

### API Key Authentication

Export Worker uses **Bearer token** for Java Bot authentication:

```
Authorization: Bearer ${JAVA_API_KEY}
```

**Java Bot must validate:**

```java
@Component
public class ExportWorkerAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (!token.equals(System.getenv("EXPORT_WORKER_API_KEY"))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

### Session Files

**NEVER commit these files:**

```bash
export-worker/session/
export-worker/*.session
.env
```

**Add to .gitignore:**

```gitignore
export-worker/session/*
export-worker/*.session
.env
.env.local
```

---

## 📊 Job States & Tracking

### Redis Keys

Export Worker uses Redis to track job state:

```
job:processing:{task_id}    # Job being processed (expires after JOB_TIMEOUT)
job:completed:{task_id}     # Successfully completed (expires after 1 hour)
job:failed:{task_id}        # Failed job (expires after 1 hour)
```

### Java Bot Can Check Status

```java
// Check if job is still processing
Boolean isProcessing = redisTemplate
    .hasKey("job:processing:" + taskId);

// Check if job completed
String completedTime = redisTemplate
    .opsForValue()
    .get("job:completed:" + taskId);

// Get failure info
String failureInfo = redisTemplate
    .opsForValue()
    .get("job:failed:" + taskId);
```

---

## ⚡ Performance Tuning

### Export Speed

**Factors affecting speed:**

1. **Message Count**: Linear - 1000 msgs ≈ 30-60 seconds
2. **Network Latency**: Telegram API roundtrip time
3. **Rate Limiting**: FloodWait errors cause exponential backoff
4. **Media**: Exporting metadata only (not downloading files)

**Benchmark:**

```
Messages  | Time    | Rate
----------|---------|----------
100       | 3-5s    | 20-30 msg/s
1000      | 30-60s  | 17-33 msg/s
10000     | 5-10m   | 17-33 msg/s
```

### Scaling

**Multiple workers:**

```bash
# Start 3 workers consuming from same queue
docker-compose up -d --scale export-worker=3

# Each worker processes jobs independently
# Load balancing via Redis BLPOP (fair distribution)
```

### Memory Usage

**Per worker:**

```
Base: 50-100 MB (Pyrogram, libraries)
+ Buffering: 10-50 MB (depends on message size)

Total with 4 workers: 200-600 MB
```

---

## 🐛 Error Handling

### Categorized Errors

| Error Code | Status | Retry | Handling |
|-----------|--------|-------|----------|
| CHAT_NOT_ACCESSIBLE | Failed | No | Inform user (chat deleted, kicked, etc) |
| CHAT_PRIVATE | Failed | No | User not authorized |
| CHAT_ADMIN_REQUIRED | Failed | No | Admin permissions needed |
| INVALID_CHAT_ID | Failed | No | Chat ID syntax error |
| EXPORT_ERROR | Failed | No | Unexpected error during export |
| TIMEOUT | Failed | No | Job took too long (>JOB_TIMEOUT) |
| NETWORK_ERROR | Failed | Yes | Retry with backoff |
| RATE_LIMIT | In Progress | Yes | Exponential backoff (1s-32s) |

### Exponential Backoff

```
Attempt 1: Immediate
Attempt 2: 1 second delay
Attempt 3: 2 second delay
Attempt 4: 4 second delay
Attempt 5: 8 second delay
Attempt 6: 16 second delay
Attempt 7: 32 second delay (max)

After 3 total attempts: Mark as failed
```

---

## 📝 Logging

### Log Format

Structured JSON logs with context:

```json
{
  "timestamp": "2025-06-24T15:30:00.000Z",
  "level": "INFO",
  "logger": "export-worker.main",
  "message": "Job export_12345 completed",
  "task_id": "export_12345",
  "chat_id": -1001234567890,
  "message_count": 100,
  "duration_seconds": 45.2
}
```

### Log Levels

```
DEBUG   - Detailed debugging info (entity conversion, retry logic)
INFO    - Job lifecycle (start, complete, status)
WARNING - Recoverable errors (rate limit, network retry)
ERROR   - Failures (job failed, auth error)
```

### Accessing Logs

```bash
# Follow real-time logs
docker-compose logs -f export-worker

# Last 100 lines
docker-compose logs --tail=100 export-worker

# Export to file
docker-compose logs export-worker > worker.log

# With timestamps
docker-compose logs -t export-worker
```

---

## 🔧 Troubleshooting

### Worker Won't Start

```bash
# Check logs
docker-compose logs export-worker

# Check Redis connection
docker-compose logs redis

# Verify .env is set
grep TELEGRAM_API_ID .env
```

### Auth Loop (2FA Stuck)

```bash
# Delete session file and restart
rm export-worker/session/*.session
docker-compose restart export-worker

# Watch for prompt
docker-compose logs -f export-worker
```

### Jobs Not Processing

```bash
# Check Redis queue length
docker exec telegram-export-redis redis-cli LLEN telegram_export

# Check Redis connectivity
docker exec telegram-export-redis redis-cli ping

# Check export-worker is running
docker-compose ps

# View worker logs
docker-compose logs export-worker
```

### Rate Limiting Errors

```
Normal behavior. Worker will automatically retry with backoff.

If too frequent:
- Reduce message limit per export
- Space out exports
- Contact Telegram for higher limits
```

---

## 📚 References

- [Pyrogram Documentation](https://docs.pyrogram.org/)
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Telegram Client API (TL Schema)](https://core.telegram.org/schema)
- [Redis Documentation](https://redis.io/documentation)

---

## ✅ Checklist Before Production

- [ ] TELEGRAM_API_ID and TELEGRAM_API_HASH obtained from my.telegram.org
- [ ] JAVA_API_KEY is secure random string (min 32 chars)
- [ ] Redis has persistent storage configured
- [ ] Export Worker session directory is a Docker volume
- [ ] .env file is not committed to git
- [ ] Java Bot has /api/export/callback endpoint implemented
- [ ] Java Bot validates EXPORT_WORKER_API_KEY header
- [ ] Error handling is comprehensive
- [ ] Logging is configured and monitored
- [ ] Load testing done (estimate daily message volume)
- [ ] Backup/recovery plan for session files
- [ ] Rate limiting understood and monitored

---

**Document End**
