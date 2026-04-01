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
- REST API для конвертации JSON-экспорта (используется Python-воркером)
- Telegram bot with interactive wizard (chat picker, date range selection)
- Python worker for Telegram API interaction (Redis queue consumer)

**Tech Stack**: Spring Boot 3.3, Java 21, Maven, Redis, JUnit 5, AssertJ, Testcontainers

## Architecture

### Package Structure

```
com.tcleaner
├── TelegramCleanerApplication  # Spring Boot entry point
├── SecurityConfig              # Публичные endpoints, CSRF отключён, stateless
├── api/
│   └── TelegramController  # POST /api/convert (multipart), GET /api/health
├── core/                   # Core business logic
│   ├── TelegramExporter    # Main processor: reads JSON, returns formatted strings
│   ├── TelegramExporterInterface # Контракт processFile (используется TelegramController)
│   ├── TelegramExporterException # RuntimeException с errorCode (FILE_NOT_FOUND, INVALID_JSON, GENERAL_ERROR)
│   ├── MessageProcessor    # Applies markdown/date formatting to messages
│   ├── MessageFilter       # Date range and keyword filtering (поддерживает произвольные предикаты)
│   └── MessageFilterFactory # HTTP parameter parsing; возвращает null если фильтры не заданы
├── format/                 # Text transformation
│   ├── MarkdownParser      # Entity (bold, italic, links, spoiler и др.) → markdown
│   ├── MessageFormatter    # Output format: "YYYYMMDD message_text"
│   ├── DateFormatter       # Date parsing/formatting; ожидает полный ISO 8601 (с T), date-only → null
│   ├── StringUtils         # Утилита splitCsv: разбивает CSV-строку в список
│   └── UrlValidator        # XSS-защита: разрешает только безопасные схемы (http/https/tg/mailto/…)
└── bot/                    # Telegram bot (long polling)
    ├── BotInitializer      # Регистрирует ExportBot в TelegramBotsApi при старте (ConditionalOnExpression)
    ├── ExportBot           # Interactive wizard: chat picker, date range, callbacks;
    │                       #   @Scheduled eviction сессий без активности > 2ч (защита от OOM)
    ├── ExportJobProducer   # Enqueue jobs to Redis (with optional from_date/to_date);
    │                       #   атомарная защита от дублирующих экспортов через SET NX
    └── UserSession         # Per-user conversation state (state machine: IDLE→AWAITING_DATE_CHOICE→…);
                            #   lastAccess для TTL eviction
```

### Python Worker Structure (export-worker/)

```
export-worker/
├── main.py                 # Точка входа: запуск asyncio event loop, graceful shutdown (SIGTERM/SIGINT)
├── config.py               # Pydantic Settings: загрузка конфигурации из env vars
├── models.py               # Pydantic-модели: ExportRequest, ExportResult, ErrorCode enum
├── message_cache.py        # Redis-кэш сообщений: sorted sets, range tracking, gap detection,
│                           #   msgpack сериализация, TTL 30 дней, LRU eviction, per-chat cap
├── pyrogram_client.py      # Pyrogram MTProto клиент: экспорт сообщений, FloodWait handling,
│                           #   кэш-синхронизация Pyrogram entity access_hash,
│                           #   raw MTProto fallback (access_hash=0) для публичных каналов по числовому ID,
│                           #   get_messages_count() — universal count (raw MTProto для date-range)
├── queue_consumer.py       # Redis BRPOP consumer: получение задач из очереди, таймауты, retry,
│                           #   Dead Letter Queue (невалидные задачи → <queue>_dead),
│                           #   socket_timeout=10с для защиты от deadlock
├── java_client.py          # HTTP-клиент к Java API: отправка сообщений на конвертацию,
│                           #   UTF-16 entity offset handling, split файлов > 45МБ на части,
│                           #   _notify_user_empty() при пустом результате экспорта
├── json_converter.py       # Конвертация Pyrogram Message → Telegram JSON export format
├── get_session.py          # Утилита: генерация Pyrogram string session для production
├── requirements.txt        # Production-зависимости
├── requirements-dev.txt    # Dev-зависимости (pytest, black, flake8, mypy)
├── Dockerfile              # Production-образ (python:3.11-slim, non-root user)
├── .dockerignore           # Исключения для Docker build context
└── tests/                  # Тесты
    ├── conftest.py         # Фикстуры: мокированные Redis, Pyrogram, httpx
    ├── test_models.py          # Валидация Pydantic-моделей
    ├── test_json_converter.py  # Конвертация сообщений
    ├── test_message_cache.py   # Redis sorted sets кэш, gap detection, LRU eviction
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
- **Кэш сообщений (MessageCache)**: Redis sorted sets для кэширования per-chat. Два индекса: по msg_id (`cache:msgs`) и по дате (`cache:dates`). Трекинг кэшированных диапазонов по ID (`cache:ranges`) и по датам (`cache:date_ranges`). При повторном экспорте — gap detection по датам или ID → fetch только недостающего → merge с кэшем → монолитный файл. Пример: Вася экспортирует 11-13.01, Петя 01-08.01, Коля запрашивает 01-15.01 → из кэша 01-08 и 11-13, fetch только 09-10 и 14-15. TTL 30 дней, LRU eviction 120MB. `CACHE_ENABLED=true`.
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
- **Dead Letter Queue**: Невалидные задачи (ошибка JSON или валидации Pydantic) автоматически перекладываются в Redis-список `<queue>_dead` с причиной и timestamp — не теряются
- **Пустой результат**: `send_response()` явно уведомляет пользователя если экспорт вернул 0 сообщений
- **Валидация дат**: `ExportRequest.from_date`/`to_date` валидируются Pydantic field_validator на входе из очереди (форматы: `YYYY-MM-DD` или `YYYY-MM-DDTHH:MM:SS`)

### Design Principles

**1. Separation of Concerns**
- Each package has one responsibility (formatting, bot, api, etc.)
- Clear data flow: POST /api/convert → filter + format → text response
- Exception types (TelegramExporterException) signal specific error conditions

**2. Thread Safety**
- Jackson `ObjectMapper` is thread-safe after configuration
- `MessageProcessor` is stateless

## Testing Strategy

### Test Organization

Tests follow **3-tier approach**:

**Unit Tests** (fast, isolated, no external dependencies)
- `MessageFilterTest` - ~20 сценариев для date/keyword filtering
- `MessageProcessorTest` - Message formatting pipeline
- `DateFormatterTest` - Time zone handling, edge cases
- `MarkdownParserTest` - Entity transformation to markdown
- `TelegramControllerTest` - Request validation, response format

**Integration Tests** (slower, use Testcontainers)
- `IntegrationTest.java` - Full JSON→text processing cycle, no mocks

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
- **Mock external dependencies**: Telegram API, Redis (в Spring-тестах — Testcontainers)
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

### Modifying Message Processing

1. Change logic in `MessageProcessor` or add a new formatter class
2. Update `MessageProcessorTest` with new scenarios
3. If adding date/markdown handling: extend `DateFormatterTest` or `MarkdownParserTest`

### Adding a New Filter Type

1. Add filter condition to `MessageFilter.filter()` method
2. Add test cases to `MessageFilterTest` (at least 3: positive, negative, edge case)
3. Update `MessageFilterFactory` to parse new HTTP parameter
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
- `spring.servlet.multipart.max-file-size` - Upload size limit (512MB)
- `spring.data.redis.host` / `spring.data.redis.port` - Redis connection

**Python Worker** (`export-worker/config.py`):
- `TELEGRAM_API_ID` - Telegram API ID from my.telegram.org
- `TELEGRAM_API_HASH` - Telegram API hash from my.telegram.org
- `TELEGRAM_PHONE_NUMBER` - Phone number for Pyrogram (dev mode only)
- `TELEGRAM_SESSION_STRING` - Production: pre-authorized string session (no phone needed)
- `TELEGRAM_BOT_TOKEN` - Bot token for sending results
- `JAVA_API_BASE_URL` - Java API endpoint (http://java-bot:8080 in Docker)
- `REDIS_HOST/PORT` - Redis queue connection
- `CACHE_ENABLED` - Включить кэш сообщений (default: true)
- `CACHE_TTL_SECONDS` - TTL кэша (default: 2592000 = 30 дней)
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
- **`processFile()`**: Legacy Tree Model method — используется только в тестах.
- **Upload limit**: 512MB (`spring.servlet.multipart.max-file-size`)

### Concurrency

- **Java**: Thread-safe ObjectMapper, stateless formatters
- **Bot**: Single-threaded long polling by design

## Troubleshooting

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
