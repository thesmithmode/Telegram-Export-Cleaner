# Architecture & Design

## Package Structure

Java code is organized into logical sub-packages under `com.tcleaner`:

```
com.tcleaner
├── api/                    # REST & Telegram Bot API
│   ├── TelegramController  # REST endpoint for /api/convert
│   └── FileController      # REST endpoint for file download
├── core/                   # Core export & filtering logic
│   ├── TelegramExporter    # Main Telegram JSON processor
│   ├── MessageProcessor    # Message formatting
│   ├── MessageFilter       # Filtering by date, keywords, type
│   ├── MessageFilterFactory # Filter builder from CLI/HTTP params
│   └── TelegramExporterException
├── format/                 # Text formatting & parsing
│   ├── MarkdownParser      # Parse markdown/formatting entities
│   ├── MessageFormatter    # Format message for output
│   ├── DateFormatter       # Date parsing & formatting
│   ├── StringListConverter # Spring converter for CSV lists
│   └── StringUtils         # Shared utility methods
├── storage/                # File & directory management
│   ├── FileStorageService  # Import/Export dir management
│   ├── FileStorageServiceInterface  # Storage contract
│   ├── StorageConfig       # Configuration & TTL
│   └── StorageCleanupScheduler
├── status/                 # Processing status tracking
│   ├── ProcessingStatusService  # Redis status store
│   ├── StatusRepository    # Status storage contract
│   ├── ProcessingStatus    # enum
│   └── ProcessingResult
├── bot/                    # Telegram Bot (long polling)
│   ├── ExportBot           # Command handler
│   ├── BotInitializer      # Spring Bot registration
│   └── ExportJobProducer   # Job queue publisher
└── cli/                    # Command line interface
    └── Main                # CLI entry point
```

## Design Principles

### 1. Separation of Concerns

Each package has a single responsibility:
- **api**: External communication (HTTP, Bot)
- **core**: Business logic (export, filtering)
- **format**: Text transformation
- **storage**: File I/O
- **status**: State management
- **bot**: Async job coordination
- **cli**: Terminal interface

### 2. Dependency Inversion (SOLID)

Interfaces define contracts, implementations are replaceable:

#### Java Interfaces
- `FileStorageServiceInterface` - Abstract file storage
- `StatusRepository` - Abstract status persistence (Redis, DB, etc.)
- `TelegramExporterInterface` - Abstract export contract
- `TelegramFileExporterInterface` - Extended export with file output

**Benefits:**
- Controllers depend on interfaces, not implementations
- Easy to mock for testing
- Can replace Redis with PostgreSQL without changing business logic
- Supports multiple storage backends

#### Python Protocols (Type Hints)
- `TelegramClientProtocol` - Interface for `TelegramClient` (Pyrogram wrapper)
- `QueueConsumerProtocol` - Interface for Redis queue consumer
- `JavaClientProtocol` - Interface for Java Bot HTTP client

**Benefits:**
- Type safety without runtime overhead
- Clear API contracts
- Duck typing support
- IDE autocomplete

### 3. Code Reuse

#### String Utility Consolidation
**Problem**: CSV parsing logic duplicated in two places
- `MessageFilterFactory.build()` - Split comma-separated keywords
- `StringListConverter.convert()` - Convert HTTP param strings to List

**Solution**: Extract `StringUtils.splitCsv()`
```java
public static List<String> splitCsv(String csv) {
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
}
```

**Impact**:
- 8 lines removed from MessageFilterFactory
- 12 lines reduced to 1 in StringListConverter
- Single source of truth

### 4. Entity Format Transformation

#### Problem: Format Mismatch Between Python & Java

Python (Telegram Bot API) provides entities as:
```json
{
  "entities": [
    {
      "type": "bold",
      "offset": 5,
      "length": 3
    }
  ]
}
```

Java expected (Telegram Desktop export format):
```json
{
  "entities": [
    {
      "type": "bold",
      "text": "text"
    }
  ]
}
```

#### Solution: Transformation Layer
`java_client.py` → `_transform_entities()` converts at the boundary:

```python
def _transform_entities(self, message, entities):
    """Convert Bot API format (offset/length) to Desktop format (type/text)"""
    transformed = []
    for entity in entities:
        if entity["type"] == "url":
            entity_text = message.text[entity["offset"]:entity["offset"]+entity["length"]]
            transformed.append({
                "type": "link",
                "text": entity_text,
                "href": entity_text
            })
        else:
            entity_text = message.text[entity["offset"]:entity["offset"]+entity["length"]]
            transformed.append({
                "type": entity["type"],
                "text": entity_text
            })
    return transformed
```

**Benefits:**
- Clear separation: Python provides Bot API format, transformation → Desktop format
- Java code unaware of entity encoding differences
- Testable in isolation (11 new tests)
- Future: Easy to add other entity types (pre, code, text_link)

## Data Flow

### Web API Flow
```
1. User POST /api/convert (multipart/form-data: file)
   ↓
2. FileController.uploadFile()
   - Generates UUID for file
   - Stores in /import/{uuid}.json
   ↓
3. Async processing starts (FileStorageService.processFileAsync)
   ↓
4. TelegramExporter.processFile()
   - Reads /import/{uuid}.json
   - Parses JSON (Jackson Tree Model)
   - Applies MessageFilter if needed
   - Delegates to MessageProcessor
   ↓
5. MessageProcessor.processMessages()
   - Iterates messages
   - MarkdownParser transforms entities to markdown
   - DateFormatter formats dates
   - MessageFormatter produces "YYYYMMDD message" format
   ↓
6. FileStorageService writes result to /export/{uuid}.md
   ↓
7. User calls GET /api/download/{uuid}
   - FileController checks ProcessingStatusService
   - If COMPLETED: Stream /export/{uuid}.md
   - If FAILED: Return 409 with error
```

### Bot Flow
```
1. Telegram user: /export -100123456789
   ↓
2. ExportBot.onUpdateReceived()
   - Parses command
   - Extracts chat_id
   ↓
3. ExportJobProducer.enqueue()
   - Creates JSON task
   - RPUSH to Redis queue: telegram_export
   ↓
4. Python ExportWorker gets job (BLPOP, timeout=5)
   ↓
5. ExportWorker.process_job()
   - Verify access: verify_and_get_info(chat_id)
   - Export: get_chat_history(chat_id)
   - Parse: json_converter (MessageConverter)
   - Transform: _transform_entities()
   - Format: MessageProcessor (via JavaBotClient)
   ↓
6. JavaBotClient.send_response()
   - POST to /api/convert
   - Java returns cleaned text
```

## Resilience

### Error Handling

#### File Processing Errors
- **Outcome**: Input file deleted, output file NOT created (prevents serving corrupted files)
- **Location**: FileStorageService.processFile() try/catch/finally
- **Flow**:
  1. Mark status: PENDING
  2. Try: Process file
  3. Catch: Mark status FAILED, delete partial output
  4. Finally: Always delete input file

#### Bot Job Errors
- **Outcome**: Mark job FAILED in Redis, notify user
- **Types**:
  - **Temp error** (rate limit): Retry with exponential backoff
  - **Perm error** (no access): Fail immediately, message user
  - **Critical** (auth): Exit worker

#### Test Coverage
- `MessageFilterTest` (30+ scenarios)
- `MessageProcessorTest`, `MessageProcessorEdgeCasesTest`
- `DateFormatterTest` (time zones, formats)
- `MarkdownParserTest` (entity types)
- `FileStorageServiceTest` (disk I/O, cleanup)
- `ProcessingStatusServiceTest` (Redis, TTL)
- `ExportBotTest` (command parsing, job queueing)
- `TelegramClientTest` (connection, history export, errors)
- `ExportWorkerTest` (job processing, cleanup)

## Configuration

### Java (Spring Boot)

`application.properties`:
- `telegram.bot.token` - Bot Token (or via ENV)
- `telegram.bot.username` - Bot Username
- `telegram.queue.name` - Redis queue name
- `app.storage.import-path` - Import directory
- `app.storage.export-path` - Export directory
- `app.storage.export-ttl-minutes` - Cleanup TTL (24h default)
- `spring.servlet.multipart.max-file-size` - Upload limit (50MB)

### Python

`.env`:
- `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` - Telegram App credentials
- `TELEGRAM_PHONE` - Account phone number
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_DB` - Queue connection
- `JAVA_BOT_API_URL` - Java API endpoint
- `LOG_LEVEL` - Logging verbosity

## Testing Strategy

### Unit Tests (Isolated)
- No external dependencies (Redis, Telegram)
- Fast execution (<1ms each)
- 100+ tests total

### Integration Tests
- Docker: Redis, Java API
- Slow but comprehensive
- `IntegrationTest`, `IntegrationTest.java`

### End-to-End Tests (e2e)
- Full system with real credentials
- Manual testing or CI/CD with real account
- `test_end_to_end.py`

## Performance Considerations

### Memory
- **Tree Model**: Jackson `readTree()` loads JSON into memory
- **Limit**: 50MB max file upload (configurable)
- **Warning**: CLI has no limit (potential OutOfMemoryError)
- **Streaming**: Write output line-by-line (no `String.join` duplication)

### Concurrency
- **Java**: Thread-safe ObjectMapper, stateless MessageProcessor
- **Python**: Async/await for I/O (Telegram API, Redis, HTTP)
- **Bot**: Long polling (single-threaded by design)

### Storage
- **Import cleanup**: Never (temporary only during processing)
- **Export cleanup**: TTL-based (24h default, configurable)
- **Scheduler**: Every 1 hour non-blocking attempt

## Future Enhancements

1. **Database Backend**: Replace Redis StatusRepository with PostgreSQL
2. **S3 Storage**: Replace file-based storage with cloud object storage
3. **Streaming Export**: Process large chats without loading into memory
4. **Batch API**: Export multiple chats in one request
5. **Advanced Filtering**: Date ranges, regex, entity types
6. **Export Formats**: JSON, CSV, XML output options
