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

### Development & Debugging

⚠️ **КРИТИЧНО — НИКОГДА не запускать тесты локально**:
- **НИКОГДА** не запускать `mvn test`, `pytest`, `pip install` или любые тестовые команды на этом сервере
- Все тесты запускаются **ТОЛЬКО через GitHub Actions CI/CD** после push в ветку
- Локально можно только редактировать код, коммитить и пушить

⚠️ **ВАЖНО — Развертывание**:
- Проект развернут на этом же сервере (Docker контейнеры + процессы)
- Credentials загружены через GitHub Secrets
- Работай с исходным кодом в текущей папке: `/root/Projects/Telegram-Export-Cleaner`
- Смотри логи и состояние проекта для диагностики

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
- Telegram bot with interactive wizard (chat picker, date range selection)
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
│   ├── ExportBot           # Interactive wizard: chat picker, date range, callbacks
│   ├── ExportJobProducer   # Enqueue jobs to Redis (with optional from_date/to_date)
│   └── UserSession         # Per-user conversation state (state machine)
└── cli/                    # Command-line interface
    └── Main                # Local file processing
```

### Python Worker Structure (export-worker/)

```
export-worker/
├── main.py                 # Точка входа: запуск asyncio event loop, graceful shutdown (SIGTERM/SIGINT)
├── config.py               # Pydantic Settings: загрузка конфигурации из env vars
├── models.py               # Pydantic-модели: ExportRequest, ExportResult, ErrorCode enum
├── message_cache.py        # Redis-кэш сообщений: sorted sets, range tracking, gap detection,
│                           #   msgpack сериализация, TTL 7 дней, LRU eviction, per-chat cap
├── pyrogram_client.py      # Pyrogram MTProto клиент: экспорт сообщений, FloodWait handling,
│                           #   кэш-синхронизация Pyrogram entity access_hash,
│                           #   get_messages_count() — universal count (raw MTProto для date-range)
├── queue_consumer.py       # Redis BRPOP consumer: получение задач из очереди, таймауты, retry
├── java_client.py          # HTTP-клиент к Java API: отправка сообщений на конвертацию,
│                           #   UTF-16 entity offset handling, split файлов > 45МБ на части
├── json_converter.py       # Конвертация Pyrogram Message → Telegram JSON export format
├── get_session.py          # Утилита: генерация Pyrogram string session для production
├── requirements.txt        # Production-зависимости
├── requirements-dev.txt    # Dev-зависимости (pytest, black, flake8, mypy)
├── Dockerfile              # Production-образ (python:3.11-slim, non-root user)
├── .dockerignore           # Исключения для Docker build context
└── tests/                  # Тесты
    ├── conftest.py         # Фикстуры: мокированные Redis, Pyrogram, httpx
    ├── test_models.py      # Валидация Pydantic-моделей
    ├── test_json_converter.py  # Конвертация сообщений
    ├── test_pyrogram_client.py # Telegram API клиент
    ├── test_queue_consumer.py  # Redis consumer
    ├── test_java_client.py     # Java API интеграция
    ├── test_export_worker.py   # Main worker loop
    ├── test_integration.py     # Интеграционные тесты (с моками)
    ├── test_end_to_end.py      # E2E тесты
    └── test_performance.py     # Нагрузочные тесты
```

**Ключевые паттерны Python Worker:**

- **Авторизация**: `TELEGRAM_SESSION_STRING` для production (stateless, без номера телефона), file-based session для локальной разработки
- **Retry с backoff**: FloodWait от Telegram API обрабатывается с экспоненциальным backoff и дедупликацией
- **Кэш сообщений (MessageCache)**: Redis sorted sets для кэширования per-chat. Два индекса: по msg_id (`cache:msgs`) и по дате (`cache:dates`). Трекинг кэшированных диапазонов по ID (`cache:ranges`) и по датам (`cache:date_ranges`). При повторном экспорте — gap detection по датам или ID → fetch только недостающего → merge с кэшем → монолитный файл. Пример: Вася экспортирует 11-13.01, Петя 01-08.01, Коля запрашивает 01-15.01 → из кэша 01-08 и 11-13, fetch только 09-10 и 14-15. TTL 7 дней, лимит 100K/чат, LRU eviction 120MB. `CACHE_ENABLED=true`.
- **Graceful shutdown**: Обработка SIGTERM/SIGINT, завершение текущей задачи перед остановкой
- **Прогресс-репортинг (ОБЯЗАТЕЛЬНЫЕ ПРАВИЛА)**:
  - **ВСЕГДА** получать total количество сообщений перед началом экспорта: `get_messages_count(chat_id, from_date?, to_date?)` — для полного экспорта через `get_chat_history_count`, для date-range через raw MTProto `messages.Search` с `min_date`/`max_date`
  - При старте уведомлять юзера с указанием total: "⏳ Экспорт начался — всего ~N сообщений"
  - Прогресс каждые **10%** с процент-баром, **НИКОГДА** не спамить абсолютными числами каждые N сообщений
  - При **любой** ошибке (исключение, таймаут, failed upload) — **ВСЕГДА** уведомлять юзера через `_notify_user_failure`
  - Файлы > 45МБ: разбивать на части по строкам и отправлять пронумерованными `_part1.txt`, `_part2.txt`
  - Прогресс должен работать во ВСЕХ путях экспорта: `_fetch_all_messages`, `_export_with_id_cache`, `_export_with_date_cache`
- **Memory monitoring**: psutil отслеживает потребление памяти (оптимизация для слабых серверов)
- **MAX_WORKERS**: По умолчанию 1, настраивается через env var. Каждый worker — отдельный Pyrogram клиент

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

### Python Worker Tests

```bash
# Все тесты
cd export-worker && pip install -r requirements-dev.txt
pytest tests/ -v

# Только unit-тесты (быстрые, без внешних зависимостей)
pytest tests/test_models.py tests/test_json_converter.py -v

# Интеграционные тесты
pytest tests/test_integration.py -v

# С покрытием
pytest tests/ -v --cov=. --cov-report=html
```

**Принципы тестирования Python Worker:**
- Внешние зависимости (Redis, Pyrogram, httpx) мокируются через `conftest.py`
- Unit-тесты не требуют сети или Telegram API
- E2E-тесты (`test_end_to_end.py`) проверяют полный цикл с мокированными сервисами

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

### Git Workflow & Deployment

**ВАЖНО: Пушить ТОЛЬКО в `dev` ветку, если не сказано явно пушить в другую ветку.**
- По умолчанию: `git push origin dev`
- В production (main) только если явно сказано: "пушить в main"

**КРИТИЧНО: Порядок работы dev → main:**
1. Пушить в `dev`
2. **Дождаться успешного завершения CI/CD для `dev`** (ci.yml: тесты прошли)
3. Только после этого мержить в `main` и пушить
- Нельзя пушить в main не дождавшись итогов CI на dev

**КРИТИЧНО: Все работы с контейнерами — ТОЛЬКО через GitHub Actions CI/CD**
- **НИКОГДА** не пересобирать контейнеры вручную (`docker-compose build`, `docker build`, etc)
- **НИКОГДА** не перезапускать контейнеры вручную (`docker-compose up`, etc)
- Если контейнеры не обновляются после push → проблема в workflow (`.github/workflows/`), исправляй pipeline
- GitHub Actions должен автоматически:
  1. Пересобирать образы при push в dev/main
  2. Заливать в ghcr.io registry
  3. Запускать deploy скрипт на сервер
  4. Перезагружать контейнеры на сервере

### Commit Messages

Follow this format:
- **Prefix** (English): `FIX`, `FEAT`, `CHORE`, `REFACTOR`, `TEST`, `DOCS`
- **Description** (Russian): Detailed explanation of what changed and why
- **НИКОГДА** не добавлять `Co-Authored-By` или любые другие trailer-строки в конец коммита

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
- `app.storage.export-ttl-minutes` - File cleanup TTL (default 10 minutes)
- `spring.servlet.multipart.max-file-size` - Upload size limit (512MB)
- `spring.redis.host` / `spring.redis.port` - Redis connection

**Python Worker** (`export-worker/config.py`):
- `TELEGRAM_API_ID` - Telegram API ID from my.telegram.org
- `TELEGRAM_API_HASH` - Telegram API hash from my.telegram.org
- `TELEGRAM_PHONE_NUMBER` - Phone number for Pyrogram (dev mode only)
- `TELEGRAM_SESSION_STRING` - Production: pre-authorized string session (no phone needed)
- `TELEGRAM_BOT_TOKEN` - Bot token for sending results
- `JAVA_API_BASE_URL` - Java API endpoint (http://java-bot:8080 in Docker)
- `REDIS_HOST/PORT` - Redis queue connection
- `CACHE_ENABLED` - Включить кэш сообщений (default: true)
- `CACHE_TTL_SECONDS` - TTL кэша (default: 604800 = 7 дней)
- `CACHE_MAX_MEMORY_MB` - Лимит памяти для кэша (default: 120MB)
- `CACHE_MAX_MESSAGES_PER_CHAT` - Макс. сообщений на чат (default: 100000)

**Environment** (`.env` - DO NOT COMMIT):

**Development (local file-based session)**:
```env
TELEGRAM_API_ID=your_id
TELEGRAM_API_HASH=your_hash
TELEGRAM_PHONE_NUMBER=+1234567890
TELEGRAM_BOT_TOKEN=your_token
REDIS_HOST=localhost
REDIS_PORT=6379
```

**Production (string session via GitHub Secrets)**:
- `TELEGRAM_API_ID` ✅ GitHub Secret
- `TELEGRAM_API_HASH` ✅ GitHub Secret
- `TELEGRAM_SESSION_STRING` ✅ GitHub Secret (generated once: `python export-worker/get_session.py`)
- `TELEGRAM_BOT_TOKEN` ✅ GitHub Secret
- `SERVER_HOST/PORT/USER/SSH_KEY` ✅ GitHub Secrets (deployment credentials)

### Docker Compose

**Services**:
- Java Spring Boot API: `localhost:8080`
- Redis: `localhost:6379` (internal networking in compose)
- Python worker: Polls Redis, calls Java API

Start with: `docker-compose up -d`

## Performance Considerations

### Memory

- **JSON Parsing**: Jackson Streaming API (`JsonParser`) — reads messages one by one without loading the full file into memory. Peak memory per request is proportional to a single message, not the file size.
- **`processFile()`**: Legacy Tree Model method — still available for backward compatibility and tests, but not used by web endpoints or `FileStorageService`.
- **Upload limit**: 512MB (`spring.servlet.multipart.max-file-size`)
- **HTTP Response**: `StreamingResponseBody` — lines are written to the HTTP response as they are processed, no intermediate `List<String>` accumulation.

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
