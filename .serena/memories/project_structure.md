---
name: Project Structure
description: Directory layout and key files
type: project
---

# Project Structure

## Directory Tree

```
/root/Projects/Telegram-Export-Cleaner/
│
├── src/                              # Java source code
│   ├── main/java/com/tcleaner/
│   │   ├── TelegramCleanerApplication.java  # Spring Boot entry point
│   │   ├── SecurityConfig.java      # Spring Security + API key filter
│   │   ├── WebConfig.java           # CORS, multipart config
│   │   │
│   │   ├── bot/                     # Telegram Bot
│   │   │   ├── ExportBot.java       # Main bot (wizard UI)
│   │   │   ├── ExportJobProducer.java  # Redis SET NX, queue producer
│   │   │   ├── UserSession.java     # In-memory session state
│   │   │   ├── BotMessenger.java    # Message sending utility
│   │   │   └── BotConfig.java       # Bot configuration
│   │   │
│   │   ├── api/                     # REST API
│   │   │   ├── TelegramController.java  # /api/convert, multipart streaming
│   │   │   ├── ApiKeyFilter.java    # X-API-Key authentication
│   │   │   └── ApiExceptionHandler.java # Global exception handler
│   │   │
│   │   ├── core/                    # Core export logic
│   │   │   ├── TelegramExporter.java    # Tree + streaming export
│   │   │   ├── MessageFilter.java       # Filter by date/keywords
│   │   │   ├── MessageProcessor.java    # Process single message
│   │   │   └── TelegramExporterException.java
│   │   │
│   │   └── format/                  # Markdown & text formatting
│   │       ├── MarkdownParser.java      # 20+ Telegram entity types
│   │       ├── MessageFormatter.java    # Format message text
│   │       ├── DateFormatter.java       # Date/time formatting
│   │       ├── UrlValidator.java        # URL validation
│   │       └── StringUtils.java         # String helpers
│   │
│   └── test/java/com/tcleaner/      # Java tests (JUnit 5, AssertJ)
│       ├── *Test.java               # Unit tests
│       ├── *EdgeCasesTest.java       # Edge cases
│       ├── *DiTest.java              # Dependency injection tests
│       ├── IntegrationTest.java      # Full system tests
│       ├── api/                      # API tests
│       ├── bot/                      # Bot tests
│       └── format/                   # Formatter tests
│
├── export-worker/                   # Python worker
│   ├── main.py                      # ExportWorker entry point
│   ├── pyrogram_client.py            # Async Pyrogram + canonical ID resolver
│   ├── message_cache.py              # SQLite disk cache (HDD)
│   ├── queue_consumer.py             # Redis BLPOP consumer
│   ├── java_client.py                # HTTP POST /api/convert
│   ├── json_converter.py             # Telegram JSON parsing
│   ├── config.py                     # Pydantic settings
│   ├── models.py                     # Pydantic models
│   ├── requirements.txt              # Production dependencies
│   ├── requirements-dev.txt          # Dev + test dependencies
│   ├── Dockerfile                    # Worker container
│   ├── .dockerignore
│   │
│   └── tests/                        # pytest tests
│       ├── test_*.py                 # Unit tests
│       └── conftest.py               # pytest fixtures
│
├── docs/                             # Documentation
│   ├── ARCHITECTURE.md               # System design & data flow
│   ├── DEVELOPMENT.md                # Dev workflow, git, CI/CD
│   ├── API.md                        # REST API reference
│   ├── PYTHON_WORKER.md              # Worker internals
│   └── SETUP.md                      # Installation & deployment
│
├── .github/
│   └── workflows/
│       ├── ci.yml                    # CI on push to dev (tests, lint)
│       └── build.yml                 # CD on push to main (docker, deploy)
│
├── .serena/                          # Serena index (auto-generated)
├── target/                           # Maven build output (ignored)
├── .git/                             # Git repository
├── .claude/                          # Claude Code config (auto)
│
├── docker-compose.yml                # Local dev stack (nginx, bot, worker, redis)
├── Dockerfile                        # Java bot container
├── pom.xml                           # Maven project config
├── checkstyle.xml                    # Java code style rules
├── CLAUDE.md                         # Dev instructions (local only, .gitignore)
├── .mcp.json                         # Serena MCP server config
├── README.md                         # Quick start guide
├── .env.example                      # Environment variables template
└── .gitignore                        # Git ignore rules

```

## Key Files

### Configuration
- **pom.xml** — Maven build, dependencies (Java 21, Spring Boot 3.4.4, etc.)
- **checkstyle.xml** — Java code style (120 char lines, spaces not tabs)
- **docker-compose.yml** — Local stack (nginx, Java bot, Python worker, Redis)
- **.env.example** — Environment variables template (TELEGRAM_API_ID, TOKEN, etc.)
- **.mcp.json** — Serena semantic code indexing config

### Documentation
- **docs/ARCHITECTURE.md** — Full system design, components, data flow
- **docs/DEVELOPMENT.md** — Git workflow, CI/CD, testing guidelines
- **docs/API.md** — REST API endpoints, error codes, examples
- **docs/PYTHON_WORKER.md** — Python worker implementation details
- **docs/SETUP.md** — Installation, Docker, troubleshooting

### Build & CI/CD
- **.github/workflows/ci.yml** — Pull request CI (mvn clean package, pytest)
- **.github/workflows/build.yml** — Main push CI/CD (tests, Docker build, deploy)
- **Dockerfile** — Java bot image (Spring Boot JAR)
- **export-worker/Dockerfile** — Python worker image

## Java Package Structure

```
com.tcleaner/
├── bot/
│   ├── ExportBot               # Telegram bot
│   ├── ExportJobProducer       # Redis queue producer
│   ├── UserSession             # Session state
│   ├── BotMessenger            # Message utility
│   └── BotConfig
├── api/
│   ├── TelegramController      # REST endpoints
│   ├── ApiKeyFilter            # Authentication
│   └── ApiExceptionHandler     # Error handling
├── core/
│   ├── TelegramExporter        # Main export logic
│   ├── MessageFilter           # Filter messages
│   ├── MessageProcessor        # Process single msg
│   └── TelegramExporterException
├── format/
│   ├── MarkdownParser          # Markdown -> plain text
│   ├── MessageFormatter        # Format message
│   ├── DateFormatter           # Date/time
│   ├── UrlValidator            # URL validation
│   └── StringUtils             # String helpers
└── TelegramCleanerApplication  # Main class
```

## Python Module Structure

```
export-worker/
├── main.py                    # ExportWorker (main)
├── config.py                  # Pydantic Settings
├── models.py                  # Pydantic models
├── pyrogram_client.py         # Pyrogram async client
├── message_cache.py           # SQLite cache
├── queue_consumer.py          # Redis consumer
├── java_client.py             # HTTP client
├── json_converter.py          # JSON parser
├── tests/
│   ├── test_*.py              # pytest tests
│   └── conftest.py            # fixtures
└── requirements.txt           # Dependencies
```

## Important Notes

- **Java tests:** `src/test/java/com/tcleaner/`
- **Python tests:** `export-worker/tests/`
- **Tests run in CI only** (GitHub Actions, not locally)
- **Commits:** Russian-only, FEAT:/FIX:/REFACTOR: prefixes
- **Merge strategy:** dev → main via --squash (one clean commit)
- **CLAUDE.md:** local only, never committed (in .gitignore)
