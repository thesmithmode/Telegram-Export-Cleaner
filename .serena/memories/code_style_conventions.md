---
name: Code Style & Conventions
description: Java, Python code style, naming, documentation standards
type: project
---

# Code Style & Conventions

## Java (21, Spring Boot)

### Naming & Formatting
- **Line length:** 120 chars max (checkstyle.xml)
- **Indentation:** 4 spaces (no tabs)
- **Classes:** PascalCase (e.g., `ExportBot`, `MessageProcessor`)
- **Methods/vars:** camelCase (e.g., `processMessage`, `cacheKey`)
- **Constants:** UPPER_SNAKE_CASE (e.g., `MAX_MESSAGE_LENGTH`)

### Comments & Documentation
- **JavaDoc required** for all public classes and methods
- Format: `/** description */` (not multi-line style)
- Include `@param`, `@return`, `@throws` for complex methods
- Example:
  ```java
  /**
   * Processes a single Telegram message with filtering and formatting.
   * 
   * @param message the raw Telegram message
   * @return formatted message string, or empty if filtered out
   * @throws TelegramExporterException if processing fails
   */
  public String processMessage(Message message) throws TelegramExporterException {
  ```

### Import Style
- No wildcard imports (`import java.util.*` ❌)
- Organized: java.*, then third-party, then com.tcleaner.*

### Testing
- JUnit 5 + AssertJ
- Test class name: `FeatureTest.java` or `FeatureEdgeCasesTest.java`
- Test method names: `testSomethingWhenCondition()` or `shouldDoXWhenYHappens()`
- Embedded Redis (IT tests only in CI)

## Python (3.11, Pyrogram)

### Naming & Formatting
- **PEP 8 compliant**
- **Line length:** 88 chars (soft limit, 100 hard)
- **Indentation:** 4 spaces
- **Classes:** PascalCase (e.g., `ExportWorker`, `MessageCache`)
- **Functions/vars:** snake_case (e.g., `export_chat`, `cache_enabled`)
- **Constants:** UPPER_SNAKE_CASE (e.g., `MAX_RETRIES`)

### Comments & Documentation
- **Docstrings required** for all public functions/classes (Google style)
- Format:
  ```python
  def fetch_messages(chat_id: int) -> list[dict]:
      """Fetch all messages from a chat using Pyrogram.
      
      Args:
          chat_id: Telegram chat ID
          
      Returns:
          List of message dictionaries
          
      Raises:
          PyrogramError: If fetch fails
      """
  ```
- Type hints required (PEP 484)

### Testing
- pytest + conftest.py (fixtures)
- Test file name: `test_*.py` or `*_test.py`
- AsyncMock for Pyrogram async calls
- SQLite tmp_path for cache tests (CI only)

## Commits & Git

### Commit Messages (Russian only)
- **Format:** `PREFIX: description`
- **Prefixes:** `FEAT:`, `FIX:`, `REFACTOR:`, `CHORE:`, `DOCS:` (UPPERCASE in Russian)
- **Description:** imperative, present tense (e.g., "добавить кэширование", "исправить утечку памяти")
- **No Co-Authored-By trailers** (never add them)
- **Line 1:** max 72 chars, **no period** at end
- **Line 2:** blank line
- **Line 3+:** detailed explanation (optional)
- Example:
  ```
  FEAT: добавить 3-path кэширование в ExportWorker
  
  - date: кэш по дате экспорта (быстро)
  - id: кэш по max message ID (fallback)
  - full: полный кэш (долгосрочное)
  ```

## Logging

### Java
- Use SLF4J (`private static final Logger log = LoggerFactory.getLogger(ClassName.class)`)
- Levels: DEBUG (dev), INFO (events), WARN (recoverable), ERROR (unrecoverable)
- No System.out.println()

### Python
- Use `logging` module with config from environment
- Levels: DEBUG, INFO, WARNING, ERROR
- Log to stdout (Docker will capture)

## Code Organization

### Java Packages
- `bot/` — ExportBot, ExportJobProducer, UserSession
- `api/` — TelegramController, ApiKeyFilter, SecurityConfig
- `core/` — TelegramExporter, MessageFilter, MessageProcessor, TelegramExporterException
- `format/` — MarkdownParser, DateFormatter, UrlValidator, MessageFormatter, StringUtils

### Python Modules
- `main.py` — ExportWorker (entry point)
- `pyrogram_client.py` — async Pyrogram, canonical ID resolver
- `message_cache.py` — SQLite disk cache
- `queue_consumer.py` — Redis BLPOP consumer
- `java_client.py` — HTTP POST /api/convert
- `json_converter.py` — Telegram JSON parsing
- `config.py` — Pydantic settings
- `models.py` — Pydantic models
