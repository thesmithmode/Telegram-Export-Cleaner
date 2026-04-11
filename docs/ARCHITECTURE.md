# System Architecture

## High-Level Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      TELEGRAM-EXPORT-CLEANER                     │
└─────────────────────────────────────────────────────────────────┘

  ┌──────────────┐         ┌─────────────┐         ┌─────────────┐
  │ Telegram Bot │         │   Redis     │         │   Python    │
  │  (Java Bot)  │────────▶│   Queues    │────────▶│   Worker    │
  │   Port 8080  │         │  (in-mem)   │         │  Pyrogram   │
  └──────────────┘         └─────────────┘         └─────────────┘
        ▲                                                 ▼
        │                                          (Telegram API)
        │                    ┌────────────────┐         │
        │◀───────────────────│  REST API      │◀────────┘
        │                    │ /api/convert   │
        │                    └────────────────┘
        │
   (Personal Chat)
```

## Component Breakdown

### 1. Java Bot (port 8080)

**Responsibility:** Telegram interaction, user session management, queue distribution

**Classes:**
- `ExportBot` — Long-polling для команд, wizard UI, session states
- `ExportJobProducer` — Создание задач в Redis, SET NX protection, express queue routing
- `BotMessenger` — Отправка сообщений, редактирование, keyboard responses
- `UserSession` — Потокобезопасное управление состоянием диалога (IDLE → WAITING_CHAT → WAITING_DATE → etc)

**Key Flow:**
```
User Input (chat/username)
    ↓
ExportBot.processUpdate()
    ↓
UserSession.setState(WAITING_DATE)
    ↓
BotMessenger.sendWithKeyboard()
    ↓
ExportJobProducer.enqueue() — Redis LPUSH + SET NX
    ↓
"Экспорт в очереди" сообщение пользователю
```

**Redis Usage:**
- `telegram_export` — основная очередь (LPUSH/BLPOP)
- `telegram_export_express` — приоритетная для кэшированных данных
- `active_export:{userId}` — SET NX блокировка от параллельных экспортов
- `cancel_export:{taskId}` — флаг для отмены во время обработки

---

### 2. Python Worker

**Responsibility:** Экспорт через Pyrogram API, кэширование, обработка файлов

**Classes:**
- `ExportWorker` (main.py) — Основной класс, 3-path caching strategy
- `TelegramClient` (pyrogram_client.py) — Async Pyrogram wrapper, canonical ID resolver
- `MessageCache` (message_cache.py) — Redis sorted sets для кэширования по ID и датам
- `QueueConsumer` (queue_consumer.py) — BLPOP consumer, job lifecycle
- `JavaBotClient` (java_client.py) — HTTP POST к /api/convert для форматирования

**3 Caching Paths:**

```
┌─────────────────────────────────────────────────┐
│ Job: chat_id, start_date, end_date             │
└─────────────────────────────────────────────────┘
         │
         ├──→ PATH 1: Date-Range Cache Hit?
         │    (Redis sorted sets by date)
         │    ✓ YES → fetch from cache → /api/convert
         │    ✗ NO  → go to PATH 2
         │
         ├──→ PATH 2: Full Chat Cache Hit?
         │    (Redis by msg_id)
         │    ✓ YES → fetch all, filter, /api/convert
         │    ✗ NO  → go to PATH 3
         │
         └──→ PATH 3: Fallback — Fetch from Telegram API
              (Pyrogram getMessages())
              → save to cache → /api/convert
```

**Cancel Mechanism:**
```
User clicks "❌ Cancel" in Telegram
    ↓
ExportBot sets Redis key: cancel_export:{taskId}
    ↓
Python worker checks every 100 messages
    ↓
If cancelled → save accumulated messages to cache + return partial result
```

**Canonical ID Resolver:**
- Конвертирует различные форматы входа в numeric ID:
  - `@username` → 123456789
  - `https://t.me/username` → 123456789
  - `https://t.me/c/123456789/1` → -100123456789 (group link to ID)
- Кэширует маппинги в Redis для повторного использования

---

### 3. REST API (/api/convert)

**Responsibility:** Синхронное форматирование файлов, фильтрация

**Endpoint:**
- `POST /api/convert` (multipart: file, startDate, endDate, keywords, excludeKeywords)
- `GET /api/health` (public, no auth)

**Processing:**

```
┌─────────────────┐
│ Multipart Form  │
│ (result.json)   │
└────────┬────────┘
         ↓
┌─────────────────────────────────────────────────┐
│ TelegramController.convert()                    │
│ - Validate form (multipart, dates)              │
│ - Create MessageFilter                          │
│ - StreamingResponseBody                         │
└────────┬────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────┐
│ TelegramExporter (2 modes)                      │
│                                                 │
│ TREE MODE:  JsonNode → List<String>            │
│ STREAMING:  JsonParser → Writer (memory O(1))  │
└────────┬────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────┐
│ MessageProcessor + MarkdownParser               │
│ - Format entities (bold, italic, code, links)  │
│ - Validate URLs (XSS protection)               │
│ - Filter by date range + keywords              │
└────────┬────────────────────────────────────────┘
         ↓
┌─────────────────┐
│ output.txt      │
└─────────────────┘
```

**Security:**
- `ApiKeyFilter` — X-API-Key header validation (SET or no auth)
- `SecurityConfig` — CSRF disabled (stateless API), CSP headers, HTTPS enforcement
- `UrlValidator` — Whitelist URL schemes (http, https, tg, mailto, etc), block javascript:, data:, etc

---

## Data Flow: User Request → Export Complete

### **Scenario: User exports @durov channel (1 Jan - 31 Dec)**

**T=0s: User sends `/start`**
```
User → Java Bot (ExportBot.processUpdate)
  ↓
ExportBot: "Choose action" with buttons
  ↓
User selects: "📦 Весь чат" (export all)
  ↓
BotMessenger: "Обрабатываю..." inline message
```

**T=2s: User sends chat ID**
```
User → @durov
  ↓
ExportBot: "Выбери диапазон дат" with calendar buttons
  ↓
User: "⏮ С начала" + "⏭ До сегодня"
  ↓
ExportJobProducer.enqueue():
  - Create JSON task: {chat_id: 123, start_date: null, end_date: null}
  - Check SET NX active_export:{userId}
  - LPUSH to telegram_export_express (assume cached)
  - Return taskId to user
```

**T=5s: Python worker gets job**
```
QueueConsumer.BLPOP(telegram_export_express)
  ↓
ExportWorker._process_job(task):
  - canonical_id = resolve_canonical_id("durov") → 123456789
  - Check cache: DATE_RANGE_CACHE for this ID + date range?
  - YES! Found full history in Redis sorted sets
  - Fetch messages from cache
  - POST to /api/convert with cached JSON
```

**T=6s: /api/convert processes**
```
TelegramController.convert(multipartFile):
  - MessageFilter.fromRequestParams() → no date filters
  - TelegramExporter.processFileStreaming(filter)
  - MarkdownParser.parseTextEntity() for each message
  - UrlValidator.sanitizeUrl() for links (XSS prevention)
  - Stream to outputStream (no buffering)
```

**T=7s: Result sent to user**
```
JavaBotClient.send_response(user_chat_id, result.txt):
  - POST /api/convert returns full output
  - BotMessenger.sendFile("output.txt")
  - User receives file in Telegram
  - Progress cleared, state reset to IDLE
```

---

## Redis Usage

Redis используется **только как очередь задач и координатор состояния** между Java-ботом и Python-воркером. **Кэш сообщений хранится в SQLite** (см. раздел ниже) — раньше кэш жил в Redis Sorted Sets, но был перенесён на диск для экономии памяти и поддержки больших чатов.

### Data Structures

| Key | Type | TTL | Producer → Consumer | Purpose |
|-----|------|-----|--------------------|----|
| `telegram_export` | List | ∞ | Java `ExportJobProducer.LPUSH` → Python `BLPOP` | Основная очередь задач |
| `telegram_export_express` | List | ∞ | Java (для кэш-hit задач) → Python `BLPOP` | Приоритетная очередь |
| `telegram_export_processing` | List | ∞ | `BLMOVE` staging area | In-flight jobs для крашей |
| `telegram_export_express_processing` | List | ∞ | `BLMOVE` staging area | In-flight express jobs |
| `active_export:{userId}` | String (`SET NX`) | 1h | Java `ExportJobProducer` | Дедупликация параллельных экспортов |
| `cancel_export:{taskId}` | String (`SETEX`) | 30m | Java ← Python `GET` | Флаг отмены работающей задачи |
| `staging:jobs` | Set | ∞ | Python `SADD/SREM` | Трекинг in-flight задач (recovery после краша) |
| `staging:meta:{taskId}` | String | 1h | Python `SETEX/GET/DEL` | Метаданные задачи для recovery |
| `staging:acked` | Set | ∞ | Python `SREM` | Acknowledgement tracking |
| `canonical:{input}` | String (`SETEX`) | 30d | Python `SET` ← `GET` | Кэш canonical ID (`@user` / `t.me/...` → numeric) |
| `canonical:{numericId}` | String (`SETEX`) | 30d | Python `SET` | Обратный маппинг numeric → username |

**Memory policy:** `--maxmemory 128mb --maxmemory-policy noeviction`. `noeviction` обязательна — `allkeys-lru` в прошлом молча выкидывала ключи очереди, что приводило к потерянным задачам.

### Пример: постановка задачи

```java
// Java: ExportJobProducer
redisTemplate.opsForValue().setIfAbsent(
    "active_export:" + userId,
    taskId,
    Duration.ofHours(1)
);
redisTemplate.opsForList().leftPush("telegram_export", jobJson);
```

```python
# Python: queue_consumer.py
queue, raw = await self.redis_client.blmove(
    "telegram_export",
    "telegram_export_processing",
    timeout=5,
)
```

---

## Message Cache (SQLite)

Кэш экспортированных сообщений — **локальный SQLite-файл** на диске контейнера Python-воркера (`/data/cache/messages.db`, volume `cache-data`). WAL-режим, размер ограничен `CACHE_MAX_DISK_GB` (по умолчанию 25 ГБ), LRU-эвикция по чатам.

### Зачем SQLite, а не Redis

- **Память:** 300k+ сообщений в чате — это сотни МБ. Держать их в RAM Redis на сервере 2-4 ГБ нецелесообразно.
- **Persistence:** SQLite WAL переживает рестарт контейнера без AOF-fsync накладных расходов.
- **Query flexibility:** нужны range-запросы по id **и** по дате. В Redis пришлось бы держать два Sorted Set и синхронизировать их.
- **Атомарность:** транзакции SQLite дают консистентный `messages + chat_id_ranges + chat_date_ranges + chat_meta` без MULTI/EXEC оркестрации.

### Схема

| Таблица | Назначение |
|---------|------------|
| `messages` | Сами сообщения (id, chat_id, date, json blob) |
| `chat_id_ranges` | Интервалы непрерывно закэшированных msg_id на чат (для детекции gap) |
| `chat_date_ranges` | Интервалы непрерывно закэшированных дат на чат |
| `chat_meta` | Размер кэша на чат, `last_access_ts` для LRU |

### 3-path caching (см. `ExportWorker.process_job`)

1. **date-hit** — запрошенный диапазон дат полностью в `chat_date_ranges` → читаем из SQLite, Telegram не дёргаем.
2. **id-hit** — запрошенный `offset_id + limit` полностью в `chat_id_ranges` → SQLite.
3. **fallback-miss** — тянем из Pyrogram, складываем результат в SQLite, отмечаем диапазон.

Реализация: `export-worker/message_cache.py`.

---

## Key Design Decisions

### 1. O(1) Memory Streaming Strategy

**Why:** System handles 100MB+ exports and must work on 2-4GB RAM servers

**Solution:** Sквозной streaming на каждом слое:
- **Python Worker**: Messages не накапливаются в `all_fetched` list, а пишут батчами (1000 шт) прямо в SQLite cache
- **Multipart Upload**: Spring Boot `file-size-threshold=1MB` выбрасывает файлы > 1МБ с RAM на диск автоматически
- **Jackson Streaming**: `JsonParser` не строит DOM дерево, а идет по токенам. Memory = одно текущее сообщение (~200 bytes)
- **StreamingResponseBody**: Java response пишет результат прямо в сокет, не буферизуя

**Result:** Peak memory usage O(1) regardless of chat size (including 300k+ message chats)

---

### 2. UTF-16 Entity Offsets ("The Emoji Bug")

**Problem:** Telegram Bot API возвращает offsets сущностей (bold, link, code) в UTF-16 кодировке (2 байта/символ, суррогатные пары для эмодзи). Python использует Unicode code points (UTF-32). Прямое применение смещений приводит к "съезжанию" текста на сообщениях с эмодзи.

**Solution:** В методе `_transform_entities` текст кодируется в `utf-16-le`, смещения применяются к байтам (`offset * 2`), затем декодируются обратно.

**Result:** 100% точность Markdown-разметки на любых эмодзи и символах.

---

### 3. Asynchronous Timeout Handling in Spring Boot 3.4

**Problem:** `StreamingResponseBody` переводит обработку запроса в асинхронный режим. Дефолтный таймаут Spring — 30 секунд. На файлах > 30MB это приводит к `ReadError` (Service Unavailable).

**Solution:** `WebConfig.java` устанавливает `setDefaultTimeout(-1)` (без таймаута) и создает `ThreadPoolTaskExecutor` для асинхронных задач, чтобы не блокировать потоки Tomcat.

**Result:** Надежная обработка файлов любого размера.

---

### 4. Streaming JSON Processing

**Why:** Telegram exports can be 100MB+ files. Loading into memory → OOM.

**Solution:** `JsonParser` (Jackson) + `StreamingResponseBody`
```java
// Read token by token, write output on-the-fly
while (parser.nextToken() != JsonToken.END_ARRAY) {
    Message msg = objectMapper.readValue(parser, Message.class);
    processMessage(msg);  // Write to output stream immediately
}
```

**Benefit:** Memory usage O(1) regardless of file size.

---

### 5. 3-Path Caching (Python Worker)

**Why:** Minimize Telegram API calls (FloodWait limits)

**Paths:**
1. **Date-Range Cache:** Repeat same date range? → Redis sorted sets
2. **Full Chat Cache:** Different date range but same chat? → Filter from cache
3. **Fallback:** New chat or not cached → Pyrogram API

**Benefit:** Subsequent exports 10x faster.

---

### 6. SET NX for Duplicate Protection

**Why:** User can click "export" multiple times (network lag, double-tap)

**Solution:**
```java
Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent(ACTIVE_EXPORT + userId, taskId, Duration.ofHours(1));
if (!acquired) {
    throw new IllegalStateException("Экспорт уже активен");
}
```

**Benefit:** No duplicate tasks in queue.

---

### 7. Express Queue for Cached Exports

**Why:** Cached exports should complete faster than full exports

**Solution:** Route to `telegram_export_express` if likelihood high
```java
boolean cached = cache.exists(chatId);
String queue = cached ? "telegram_export_express" : "telegram_export";
redis.opsForList().rightPush(queue, taskJson);
```

**Benefit:** Cached exports prioritized, users get results faster.

---

## Thread Safety & Concurrency

### Java
- `UserSession` uses `ConcurrentHashMap` for thread-safe session storage
- `ExportJobProducer` uses Redis SET NX (atomic operation)
- Spring `@Async` for non-blocking bot updates

### Python
- `asyncio` for concurrent message processing
- `redis.asyncio` for async Redis operations
- `TelegramClient` async Pyrogram wrapper

---

## Error Handling

### Java
```java
@ControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<?> handleDateError(...) {
        return new ErrorResponse(400, "INVALID_DATE");
    }
    
    @ExceptionHandler(TelegramExporterException.class)
    public ResponseEntity<?> handleExportError(TelegramExporterException ex) {
        return new ErrorResponse(400, ex.getErrorCode());
    }
}
```

### Python
```python
try:
    await client.get_messages(chat_id)
except PeerIdInvalid:
    logger.error(f"Chat not found: {chat_id}")
except FloodWait as e:
    logger.warning(f"Rate limited, retry after {e.value}s")
    await asyncio.sleep(e.value)
```

---

## Performance Notes

| Operation | Time | Notes |
|-----------|------|-------|
| Export full chat (cached) | ~1-2s | Redis read only |
| Export full chat (first time) | ~30-60s | Depends on chat size & API rate limits |
| /api/convert (1GB file) | ~5-10s | Streaming, no OOM |
| Canonical ID resolution | ~100ms | Redis cached |

---

## Deployment

### Docker Compose

```yaml
services:
  java:
    image: telegram-cleaner:java
    ports:
      - "8080:8080"
    environment:
      - SPRING_REDIS_HOST=redis
      - TELEGRAM_BOT_TOKEN=...
    depends_on:
      - redis
  
  python:
    image: telegram-cleaner:python
    environment:
      - REDIS_URL=redis://redis:6379
      - TELEGRAM_SESSION_STRING=...
    depends_on:
      - redis
  
  redis:
    image: redis:7-alpine
    command: redis-server --maxmemory 256mb --maxmemory-policy volatile-lru
```

### Production Considerations

- HTTPS enforcement in `SecurityConfig`
- API Key rotation in environment variables
- Redis persistence + replication
- Monitor `/api/health` for alerting
- Logs aggregation for debugging

---

## Related Documentation

- 🐍 [PYTHON_WORKER.md](PYTHON_WORKER.md) — Python Worker detailed guide
- 📡 [API.md](API.md) — REST API reference
- 🔧 [SETUP.md](SETUP.md) — Deployment & Configuration
- 📖 [DEVELOPMENT.md](DEVELOPMENT.md) — Contributing guidelines
- 📘 [README.md](../README.md) — Primary project documentation & quick start
