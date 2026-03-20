# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Quick Start

### Build & Run

```bash
# Build the project
mvn clean package

# Run tests
mvn test

# Run specific test
mvn test -Dtest=MessageFilterTest

# Build Docker image & start services
docker-compose up -d

# Stop services
docker-compose down
```

### Local Development

```bash
# Quick feedback loop: compile + run single test
mvn compile test -Dtest=ClassName

# Check code quality (checkstyle)
mvn checkstyle:check

# Generate coverage report
mvn test jacoco:report
# Open: target/site/jacoco/index.html
```

## Project Overview

**Telegram Export Cleaner** is a Java Spring Boot application that processes Telegram chat exports. It provides:
- REST API for uploading JSON export files and downloading cleaned markdown
- Telegram bot integration for direct `/export` commands
- Async file processing with Redis job queue
- Python worker for Telegram API interaction

**Tech Stack**: Spring Boot 3.3, Java 21, Maven, Redis, JUnit 5, AssertJ, Testcontainers

## Architecture

### Package Structure

```
com.tcleaner
├── api/                    # REST & Bot API endpoints
│   ├── TelegramController  # POST /api/convert (multipart form / raw JSON)
│   └── FileController      # POST /api/upload, GET /api/download/{id}
├── core/                   # Core business logic
│   ├── TelegramExporter    # Main processor: reads JSON, returns formatted strings
│   ├── MessageProcessor    # Applies markdown/date formatting to messages
│   ├── MessageFilter       # Date range and keyword filtering
│   └── MessageFilterFactory # CLI/HTTP parameter parsing
├── format/                 # Text transformation
│   ├── MarkdownParser      # Entity (bold, italic, links) → markdown
│   ├── MessageFormatter    # Output format: "YYYYMMDD message_text"
│   └── DateFormatter       # Date parsing/formatting with timezone support
├── storage/                # File I/O and lifecycle
│   ├── FileStorageService  # Upload→process→download pipeline
│   ├── FileStorageServiceInterface # Storage abstraction (for mocking/alternate backends)
│   └── StorageCleanupScheduler # TTL-based cleanup of export files
├── status/                 # Request state tracking
│   ├── ProcessingStatusService # Redis wrapper for status tracking
│   └── StatusRepository    # Status persistence contract
├── bot/                    # Telegram bot (long polling)
│   ├── ExportBot           # Command handler (/export, /start, /help)
│   └── ExportJobProducer   # Enqueue jobs to Redis
└── cli/                    # Command-line interface
    └── Main                # Local file processing
```

### Design Principles

**1. Dependency Inversion (SOLID)**
- Controllers depend on interfaces, not implementations:
  - `FileStorageServiceInterface` (used by FileController, StorageCleanupScheduler)
  - `TelegramExporterInterface` (used by controllers)
  - `StatusRepository` (abstract persistence, Redis implementation)
- Benefits: Easy testing via mocks, can swap Redis→PostgreSQL without touching business logic

**2. Separation of Concerns**
- Each package has one responsibility (formatting, storage, status, bot, etc.)
- Clear data flow: upload → process (filter + format) → store → download
- Exception types (TelegramExporterException) signal specific error conditions

**3. Thread Safety**
- Jackson `ObjectMapper` is thread-safe after configuration
- `MessageProcessor` is stateless
- `FileStorageService` uses UUID naming to prevent race conditions

## Critical Design Pattern: Spring @Async and JDK Proxies

### The Problem

`FileStorageService` has an `@Async` method (`processFileAsync`). Spring wraps this in a **JDK dynamic proxy** that implements only `FileStorageServiceInterface`, not the concrete class.

When a component like `StorageCleanupScheduler` depended on the concrete `FileStorageService` class directly, Spring's ApplicationContext would fail:
```
Failed to load ApplicationContext
The bean 'fileStorageService' could not be injected because it is a JDK dynamic proxy...
```

### The Solution

**All components depending on FileStorageService must depend on `FileStorageServiceInterface` instead.**

```java
// ❌ WRONG - causes Spring proxy injection failures
private final FileStorageService fileStorageService;

// ✅ CORRECT
private final FileStorageServiceInterface fileStorageService;
```

**Files fixed**:
- `FileController.java` (lines 5, 52, 61)
- `StorageCleanupScheduler.java` (lines 7, 17, 24)

**Verification**: Grep entire `src/main/java` for `private.*FileStorageService[^I]` (excluding the interface) to ensure no concrete injections remain.

## Testing Strategy

### Test Organization

Tests follow **3-tier approach**:

**Unit Tests** (fast, isolated, no external dependencies)
- `MessageFilterTest` - 30+ scenarios for date/keyword filtering
- `MessageProcessorTest` - Message formatting pipeline
- `DateFormatterTest` - Time zone handling, edge cases
- `MarkdownParserTest` - Entity transformation to markdown
- `FileStorageServiceTest` - File I/O, cleanup TTL logic
- `ProcessingStatusServiceTest` - Redis operations with TTL
- `FileControllerTest` - Request validation, response format

**Integration Tests** (slower, use Testcontainers)
- `IntegrationTest.java` - Full Spring context + Redis container
- Validates end-to-end flow: upload → process → download

**Rate Limiting Tests** (specialized validation)
- `FileControllerRateLimitTest` - Ensures invalid files (400) don't consume rate limit quota

### Running Tests

```bash
# All tests (unit + integration)
mvn test

# Single test class
mvn test -Dtest=FileControllerTest

# Single test method
mvn test -Dtest=FileControllerTest#uploadFile_returns202OnSuccess

# With coverage report
mvn test jacoco:report
```

### Test Expectations

- **Code coverage minimum**: 80% (enforced by JaCoCo plugin)
- **No mocking of core logic**: Use real `MessageProcessor`, `TelegramExporter`, etc.
- **Mock external dependencies**: `FileStorageService`, `ProcessingStatusService`, Telegram API
- **Assertion style**: Use AssertJ (`assertThat(...)`) for readable assertions

## Common Development Tasks

### Adding a New Endpoint

1. Create handler method in `TelegramController` or `FileController`
2. Add validation early (400 errors don't consume rate limits in FileController)
3. Write unit test in `*ControllerTest` covering:
   - Invalid input → 400
   - Valid input → expected status
   - Error cases → appropriate HTTP status
4. Test any Spring integration in `IntegrationTest`

### Modifying Message Processing

1. Change logic in `MessageProcessor` or add a new formatter class
2. Update `MessageProcessorTest` with new scenarios
3. If adding date/markdown handling: extend `DateFormatterTest` or `MarkdownParserTest`
4. Ensure `FileStorageServiceTest` still passes (end-to-end file flow)

### Adding a New Filter Type

1. Add filter condition to `MessageFilter.filter()` method
2. Add test cases to `MessageFilterTest` (at least 3: positive, negative, edge case)
3. Update `MessageFilterFactory` to parse new CLI parameter
4. Add factory test to `MessageFilterFactoryTest`

### Debugging Tests

```bash
# Run with debug output (SLF4J)
mvn test -Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG

# Run single test in IDE for breakpoint debugging
# File → Open: src/test/java/com/tcleaner/YourTest.java
# Right-click test method → Run with debugger
```

## Build Configuration

### Maven Plugins

- **maven-surefire-plugin**: Test execution (JUnit 5 auto-detected)
- **maven-checkstyle-plugin**: Code style validation (`checkstyle.xml`)
  - Runs during `mvn validate` phase
  - Set to warning severity (fails only on explicit violations)
- **jacoco-maven-plugin**: Code coverage enforcement
  - Minimum 80% line coverage (BUNDLE level)
  - Report: `target/site/jacoco/index.html` after `mvn test`
- **spring-boot-maven-plugin**: JAR packaging with embedded Tomcat

### Properties

```properties
# Java version (Java 21)
maven.compiler.source=21
maven.compiler.target=21

# UTF-8 encoding for source files
project.build.sourceEncoding=UTF-8
```

## Important Conventions

### Commit Messages

Follow this format:
- **Prefix** (English): `FIX`, `FEAT`, `CHORE`, `REFACTOR`, `TEST`, `DOCS`
- **Description** (Russian): Detailed explanation of what changed and why

Example:
```
FIX: исправить мокирование в тестах - использовать verify_and_get_info вместо verify_access
```

### Code Style

- Follow `checkstyle.xml` rules (enforced on build)
- Use meaningful variable names in Russian/English mix (code comments in Russian)
- Prefer explicit types over `var` in Spring contexts (better IDE support)

## Configuration Files

### Runtime Configuration

**Java** (`application.properties`):
- `telegram.bot.token` - Bot token from @BotFather
- `app.storage.export-ttl-minutes` - File cleanup TTL (default 1440 = 24 hours)
- `spring.servlet.multipart.max-file-size` - Upload size limit (50MB)
- `spring.redis.host` / `spring.redis.port` - Redis connection

**Environment** (`.env` - DO NOT COMMIT):
```env
TELEGRAM_API_ID=your_id
TELEGRAM_API_HASH=your_hash
TELEGRAM_BOT_TOKEN=your_token
REDIS_HOST=localhost
REDIS_PORT=6379
```

### Docker Compose

**Services**:
- Java Spring Boot API: `localhost:8080`
- Redis: `localhost:6379` (internal networking in compose)
- Python worker: Polls Redis, calls Java API

Start with: `docker-compose up -d`

## Performance Considerations

### Memory

- **JSON Parsing**: Jackson Tree Model loads entire file into memory (max 50MB configured)
- **Message Output**: Written line-by-line (no `String.join` allocation)
- **Warning**: CLI has no file size limit (potential OutOfMemoryError on huge files)

### Concurrency

- **Java**: Thread-safe ObjectMapper, stateless formatters
- **Rate Limiting**: FileController uses `AtomicLong` for thread-safe timestamp tracking
- **Bot**: Single-threaded long polling by design

### Storage Cleanup

- `StorageCleanupScheduler` runs every 60 seconds (configurable)
- Non-blocking: Continues even if cleanup fails
- Files older than TTL are deleted, not just marked

## Troubleshooting

### Spring ApplicationContext Load Failure

**Symptom**: `Failed to load ApplicationContext` during `@SpringBootTest`

**Cause**: A component is trying to inject `FileStorageService` (concrete class) instead of `FileStorageServiceInterface`. The @Async proxy only implements the interface.

**Fix**: Change `private final FileStorageService` → `private final FileStorageServiceInterface` in the component's constructor and field.

### Test Failures on Windows

- Ensure `project.build.sourceEncoding=UTF-8` in pom.xml
- Check for CRLF line endings in test assertion strings
- Use `/` for path separators in assertions (not `\`)

### High CPU During Tests

- Testcontainers pulls Docker images on first run (slow)
- Subsequent runs reuse containers (fast)
- For local testing, run `mvn test` once to warm up

## Further Reading

- **ARCHITECTURE.md**: Detailed design decisions, data flows, resilience patterns
- **TESTING.md**: Comprehensive test guide with examples
- **README.md**: User-facing feature documentation
- **CONTRIBUTING.md**: Git workflow and PR guidelines
