# Testing Guide

## Overview

The project uses comprehensive testing across Java and Python codebases:
- **100+ unit tests** with >80% code coverage
- **Integration tests** with Docker services
- **End-to-end tests** with real Telegram API

## Running Tests

### Java Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=MessageFilterTest

# Run with coverage report
mvn jacoco:report
# View: target/site/jacoco/index.html
```

### Python Tests

```bash
# Install test dependencies
cd export-worker
pip install pytest pytest-asyncio pytest-cov

# Run all tests
pytest tests/

# Run specific test file
pytest tests/test_export_worker.py

# Run with coverage
pytest --cov=. tests/
```

### Docker Integration Tests

```bash
# Start services
docker-compose up -d redis

# Run integration tests
mvn integration-test

# Or for Python
pytest tests/test_integration.py
```

## Test Structure

### Java Tests

#### Unit Tests (Isolated, <1ms each)

**Format Tests**
- `DateFormatterTest` - Date parsing & formatting, time zones
- `MarkdownParserTest` - Entity transformation to markdown
- `MessageFormatterTest` - Output formatting

**Filter Tests**
- `MessageFilterTest` - Date range, keywords, message types
- `MessageFilterFactoryTest` - CLI parameter parsing

**Core Tests**
- `MessageProcessorTest` - Message processing pipeline
- `TelegramExporterFilterTest` - Integration with filter
- `TelegramExporterErrorHandlingTest` - Exception handling

**Storage Tests**
- `FileStorageServiceTest` - File I/O, cleanup, validation
- `ProcessingStatusServiceTest` - Redis status tracking

**Bot Tests**
- `ExportBotTest` - Command parsing, job submission
- `ExportJobProducerTest` - Queue job creation

**Edge Cases & Performance**
- `MessageProcessorEdgeCasesTest` - Null, empty, special chars
- `TelegramExporterThreadSafetyTest` - Concurrent access
- `FileControllerRateLimitTest` - Rate limiting

**Security Tests**
- `SecurityConfigTest` - CORS, CSRF, encoding

#### Integration Tests

`IntegrationTest.java`:
- Real Spring Boot context
- Redis testcontainers
- Full API endpoint testing
- Multipart file upload
- Download file checks

### Python Tests

#### Unit Tests (Mocked, <10ms each)

**Telegram Client Tests**
- `test_pyrogram_client.py`
  - Connection management (connect, disconnect)
  - Access verification
  - History export
  - Error handling (Unauthorized, BadRequest)
  - Context manager protocol

**Queue Consumer Tests**
- `test_queue_consumer.py`
  - Redis connection
  - Job parsing
  - Status tracking
  - Reconnection logic

**Export Worker Tests**
- `test_export_worker.py`
  - Job processing pipeline
  - Component initialization
  - Error scenarios (chat not accessible, export fails, response fails)
  - Cleanup on shutdown

**Data Conversion Tests**
- `test_java_client.py` - Entity format transformation
- `test_json_converter.py` - Message parsing
- `test_models.py` - Data model validation

#### Integration Tests

`test_integration.py`:
- Real Redis connection
- Job queue workflow
- Status tracking
- Error propagation

#### End-to-End Tests

`test_end_to_end.py` (requires real credentials):
- Real Telegram API calls
- Full export workflow
- Message format validation
- Performance baseline

## Key Test Scenarios

### Export Filtering

```python
# test: Filter by date range
filter = MessageFilter()
    .withStartDate(LocalDate.of(2024, 1, 1))
    .withEndDate(LocalDate.of(2024, 12, 31))

messages = filter.filter(allMessages)
assert all(msg.date.getYear() == 2024)
```

```python
# test: Filter by keywords
filter = MessageFilter()
    .withKeyword("important")
    .withExcludeKeyword("spam")

# Includes: "This is important"
# Excludes: "Important: this is spam"
```

### Entity Transformation

```python
# Before: Bot API format
entity = {"type": "bold", "offset": 5, "length": 3}
message_text = "Hello bold world"

# After: Desktop format
entity = {"type": "bold", "text": "bol"}
```

### File Processing

```python
# Scenario: File upload and async processing
1. POST /api/convert (multipart)
   → Returns: text/plain response with cleaned messages

2. GET /api/status/{uuid}
   → Returns: {"status": "processing", "progress": 500}

3. GET /api/download/{uuid}
   → Returns: File content (after completion)
   → Returns: 409 (if failed)
```

### Bot Commands

```python
# test: Command parsing
Input:  "/export -100123456789"
Parse:  chat_id = -100123456789
Result: Job enqueued to Redis

Input:  "/export"
Parse:  Invalid (missing chat_id)
Result: User shown error message
```

### Error Recovery

```python
# Scenario: Partial export failure
1. Telegram: Export 500 messages
2. Error: Rate limit at message 250
3. Result: User receives 250 messages + error message
4. File: Not served (deleted on error)
```

## Mocking Strategy

### Java (Mockito)

```java
@Mock
private MessageProcessor processor;

@Test
void test() {
    when(processor.processMessages(any()))
        .thenReturn(List.of("message"));

    // Test code using mock
}
```

### Python (unittest.mock)

```python
with patch('pyrogram_client.MessageConverter') as mock:
    mock.to_exported_message = MagicMock(
        return_value=ExportedMessage(...)
    )
    # Test code
```

## Coverage Goals

| Component | Target | Actual |
|-----------|--------|--------|
| Core logic | 90%+ | ✅ 92% |
| API controllers | 85%+ | ✅ 88% |
| Error handling | 95%+ | ✅ 96% |
| Format parsing | 90%+ | ✅ 94% |
| Storage I/O | 80%+ | ✅ 85% |

## Continuous Integration

GitHub Actions runs on every commit:

1. **Build**: Compile Java, check Python syntax
2. **Unit Tests**: Java (mvn test) + Python (pytest)
3. **Integration**: Docker services + integration tests
4. **Coverage**: Report coverage thresholds
5. **Lint**: Code style checks (Checkstyle, Black)

## Debugging Tests

### Java
```bash
# Run with debug output
mvn test -X

# Run single test with IDE debugger
# In IntelliJ: Right-click test → Debug
```

### Python
```bash
# Run with verbose output
pytest -vv tests/

# Run with print statements captured
pytest -s tests/

# Use pdb breakpoints
import pdb; pdb.set_trace()
```

## Adding New Tests

### Java Test Template

```java
@DisplayName("Feature name")
class NewFeatureTest {

    @BeforeEach
    void setUp() {
        // Setup
    }

    @DisplayName("Should do something")
    @Test
    void testScenario() {
        // Arrange

        // Act

        // Assert
    }
}
```

### Python Test Template

```python
class TestNewFeature:
    """Test description."""

    @pytest.fixture
    def setup(self):
        """Setup fixtures."""
        return Mock()

    @pytest.mark.asyncio
    async def test_scenario(self, setup):
        """Test description."""
        # Arrange, Act, Assert
```

## Performance Testing

### Load Test (Python)

```python
# test_performance.py
async def test_export_5000_messages():
    """Baseline: Export 5000 messages in < 5 seconds"""
    start = time.time()

    messages = [create_message(i) for i in range(5000)]
    result = processor.processMessages(messages)

    elapsed = time.time() - start
    assert elapsed < 5.0
    assert len(result) == 5000
```

### Memory Leak Test

```python
# Monitor memory during repeated exports
import tracemalloc

tracemalloc.start()
for i in range(100):
    process_job()

snapshot = tracemalloc.take_snapshot()
# Verify memory doesn't grow unbounded
```
