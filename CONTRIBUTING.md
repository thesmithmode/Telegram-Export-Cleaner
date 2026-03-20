# Contributing Guide

## Development Workflow

### 1. Fork and Clone

```bash
git clone https://github.com/yourusername/Telegram-Export-Cleaner.git
cd Telegram-Export-Cleaner
git checkout dev
```

### 2. Create Feature Branch

```bash
git checkout -b feature/your-feature-name
# or for bugfixes:
git checkout -b fix/bug-description
```

### 3. Setup Local Environment

**Java:**
```bash
# Install Java 21+, Maven 3.8+
mvn clean install
```

**Python:**
```bash
cd export-worker
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip install -r requirements.txt
pip install -r requirements-dev.txt
```

**Docker (Optional):**
```bash
docker-compose up -d redis
# Java tests will use embedded Redis
```

### 4. Code Style & Conventions

#### Java

**Package Naming**
```
com.tcleaner.{subsystem}.{component}

Examples:
✅ com.tcleaner.core.MessageFilter
✅ com.tcleaner.storage.FileStorageService
✅ com.tcleaner.format.MarkdownParser
❌ com.tcleaner.MessageFilter (wrong: lacks subsystem)
```

**Class Naming**
```
✅ MessageFilter (noun, concrete class)
✅ MessageFilterFactory (noun, factory pattern)
✅ FileStorageServiceInterface (noun, interface suffix)
❌ MessageFilterImpl (avoid "Impl" suffix)
```

**Method Naming**
```
✅ public List<String> processMessages(List<JsonNode> messages)
✅ public boolean matches(JsonNode message)
✅ private void validateFileId(String fileId)
❌ public List processMessages(List messages)  (avoid generics omission)
```

**Javadoc**
```java
/**
 * Processes Telegram export and returns formatted lines.
 *
 * <p>Supports optional filtering by date and keywords.
 * Uses Jackson Tree Model for memory efficiency.</p>
 *
 * @param inputPath path to result.json file
 * @param filter optional message filter (null = no filtering)
 * @return list of formatted "YYYYMMDD text" lines
 * @throws IOException if file cannot be read
 * @throws TelegramExporterException for invalid JSON
 */
```

**Formatting**
- Indentation: 4 spaces
- Line length: 120 characters (soft limit)
- Imports: Organized alphabetically, grouped by package

#### Python

**Module Naming**
```
snake_case for filenames:
✅ pyrogram_client.py
✅ message_converter.py
❌ PyrogramClient.py
```

**Class Naming**
```
PascalCase:
✅ class TelegramClient
✅ class ExportWorker
```

**Function/Method Naming**
```
snake_case:
✅ async def connect(self)
✅ async def get_chat_history(self, chat_id)
❌ async def getChat_History()
```

**Docstring**
```python
async def verify_and_get_info(self, chat_id: int) -> Tuple[bool, Optional[dict]]:
    """
    Verify access to chat and retrieve metadata.

    Args:
        chat_id: Telegram chat ID

    Returns:
        (is_accessible, chat_info) tuple where:
        - is_accessible: True if chat is accessible
        - chat_info: Dict with title, type, members_count or None

    Raises:
        BadRequest: If chat doesn't exist
        ChannelPrivate: If no access to chat
    """
```

**Formatting**
- Indentation: 4 spaces
- Line length: 100 characters (PEP 8)
- Use `black` for auto-formatting

### 5. Tests

**Write tests BEFORE code (TDD):**

```java
// 1. Write failing test
@Test
void testNewFeature() {
    MessageFilter filter = new MessageFilter();
    filter.withNewCondition("value");
    assert filter.matches(message);
}

// 2. Write minimal code to pass test
public MessageFilter withNewCondition(String value) {
    this.conditions.add(value);
    return this;
}

// 3. Refactor for clarity and SOLID principles
```

**Test Naming**
```
✅ testProcessJobSuccess()
✅ testExportWithEmptyMessages()
✅ test_chat_not_accessible()
❌ test1(), testX()
```

**Test Organization**
```
class SomeClassTest {
    // Setup methods
    @BeforeEach void setUp() { }

    // Happy path tests
    @Test void testSuccess() { }

    // Edge case tests
    @Test void testEmptyInput() { }
    @Test void testNullInput() { }

    // Error tests
    @Test void testErrorCondition() { }

    // Helper methods
    private SomeClass createInstance() { }
}
```

### 6. Commit Messages

**Format: `<TYPE>: <description>` (Russian description)**

Types:
- `FEATURE` - New feature
- `BUG` - Bug fix
- `REFACTOR` - Code reorganization (no behavior change)
- `PERF` - Performance improvement
- `TEST` - Test additions/changes
- `DOCS` - Documentation
- `CONFIG` - Configuration changes
- `CHORE` - Maintenance, dependencies

**Examples:**
```
✅ FIX: исправить формат сущностей при преобразовании в Java
✅ REFACTOR: извлечь StringUtils для консолидации логики CSV
✅ TEST: добавить тесты для ExportBot
✅ DOCS: расширить ARCHITECTURE.md с диаграммами потоков данных

❌ fixed bug
❌ updates
❌ changes
```

### 7. Pull Request

**Before submitting:**

1. Rebase on latest dev
   ```bash
   git fetch origin
   git rebase origin/dev
   ```

2. Run tests locally
    ```bash
    mvn test        # Java
    pytest tests/         # Python
    ```

3. Check code style
   ```bash
   mvn checkstyle:check  # Java
   black --check .       # Python
   ```

4. Verify no credentials in code
   ```bash
   grep -r "API_KEY\|TOKEN\|PASSWORD" src/
   ```

**PR Template:**
```markdown
## Changes
- Brief description of changes
- List key modifications

## Related Issues
Closes #123

## Testing
- [x] Added unit tests
- [x] Manual testing completed
- [ ] Performance tested (if applicable)

## Checklist
- [x] Code follows style guide
- [x] Documentation updated
- [x] No hardcoded credentials
- [x] All tests passing
```

## Architecture Guidelines

### When to Create New Package

**Should extract to new package when:**
- Functionality is independent of other components
- Multiple classes with related responsibility
- Possible to replace implementation without changing others
- Other modules would benefit from depending on interface

**Should NOT extract when:**
- Only one class in package
- Tightly coupled to existing classes
- Over-engineering for hypothetical future

### Interface vs Implementation

**Create interface when:**
```java
// Multiple implementations exist or planned
class RedisStatusRepository implements StatusRepository { }
class PostgresStatusRepository implements StatusRepository { }

// External systems depend on contract
class FileController {
    private final FileStorageServiceInterface storage;  // Not impl
}

// Testing requires mocking
@Mock FileStorageServiceInterface storage;
```

**Use concrete class when:**
```java
// Single implementation, internal use only
class MarkdownParser {  // No interface needed
    // Only used internally by MessageProcessor
}
```

### Error Handling

**Principle: Fail fast, give context**

```java
// ❌ Bad: Swallows errors
try {
    processFile(path);
} catch (IOException e) {
    // Silent failure
}

// ✅ Good: Logged with context
try {
    processFile(path);
} catch (IOException e) {
    log.error("Failed to process file {}: {}", path, e.getMessage());
    throw new TelegramExporterException("PROCESSING_ERROR",
        "Cannot process file: " + path, e);
}
```

**Use custom exceptions for domain errors:**
```java
// ✅ Good
throw new TelegramExporterException("INVALID_JSON", "Message: " + ex);

// ❌ Avoid generic exceptions
throw new RuntimeException("Error");
```

## Code Review Checklist

When reviewing PR, check:

- [ ] Code follows style guide
- [ ] Tests added/updated
- [ ] No credentials or secrets
- [ ] Comments explain "why" not "what"
- [ ] Error messages are helpful
- [ ] No unnecessary complexity
- [ ] Interface/implementation separation maintained
- [ ] Performance implications considered
- [ ] Documentation updated

## Performance Considerations

### Java

- **Memory**: Use streams for large datasets, avoid concatenation
- **I/O**: Use Files.write() with Charset, not string concatenation
- **JSON**: Jackson Tree Model loads entire tree into memory (design tradeoff)

### Python

- **Async**: Use async/await for I/O-bound operations
- **Generators**: Stream large result sets with async generators
- **Error handling**: Catch specific exceptions, not bare `Exception`

## Documentation

Update relevant docs when:
- [ ] ARCHITECTURE.md - If changing package structure or flow
- [ ] TESTING.md - If adding new test categories
- [ ] CONTRIBUTING.md - If changing development process
- [ ] README.md - If changing setup or usage

## Questions?

- Check existing code for patterns
- Review similar PRs/issues
- Ask in GitHub discussions or PR comments
- Reference ARCHITECTURE.md and TESTING.md

Happy coding! 🚀
