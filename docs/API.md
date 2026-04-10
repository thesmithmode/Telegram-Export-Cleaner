# REST API Reference

## Overview

REST API для синхронной конвертации Telegram JSON export файлов в форматированный текст.

**Base URL:** `http://localhost:8080`

**Authentication:** Required (except `/api/health`)
- Header: `X-API-Key: your-secret-key`
- From environment: `JAVA_API_KEY`

---

## Endpoints

### POST /api/convert

Конвертирует Telegram JSON export в текстовый файл с фильтрацией.

**Request:**

```http
POST /api/convert HTTP/1.1
Host: localhost:8080
X-API-Key: your-secret-key
Content-Type: multipart/form-data

--boundary
Content-Disposition: form-data; name="file"; filename="result.json"
Content-Type: application/json

{...JSON export data...}
--boundary
Content-Disposition: form-data; name="startDate"

2024-01-01
--boundary
Content-Disposition: form-data; name="endDate"

2024-12-31
--boundary
Content-Disposition: form-data; name="keywords"

telegram,export
--boundary--
```

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `file` | File (JSON) | ✓ Yes | Telegram Desktop export file (result.json) |
| `startDate` | ISO 8601 | No | Start date filter (YYYY-MM-DD) |
| `endDate` | ISO 8601 | No | End date filter (YYYY-MM-DD) |
| `keywords` | CSV string | No | Include if contains (comma-separated) |
| `excludeKeywords` | CSV string | No | Exclude if contains (comma-separated) |

**Response (Success):**

```
HTTP/1.1 200 OK
Content-Type: text/plain; charset=UTF-8
Content-Disposition: attachment; filename="output.txt"
Content-Length: 12345

20240115_14:30 **Заголовок** обычный текст
20240115_14:31 Текст со ссылкой [нажми](https://example.com)
20240115_14:32 *курсив* и `код`
20240115_14:33 ~~зачёркнутый текст~~
20240115_14:34 [card redacted as CARD]
```

**Response (Error):**

```json
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "timestamp": "2024-01-15T14:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid date format: startDate must be YYYY-MM-DD",
  "path": "/api/convert"
}
```

**Status Codes:**

| Code | Meaning | Reason |
|------|---------|--------|
| 200 | OK | File converted successfully |
| 400 | Bad Request | Invalid parameters (date format, date range) |
| 401 | Unauthorized | Missing or invalid X-API-Key |
| 413 | Payload Too Large | File exceeds 2GB limit |
| 500 | Internal Server Error | Unexpected error during conversion |

**Examples:**

**cURL - Basic export:**
```bash
curl -X POST http://localhost:8080/api/convert \
  -H "X-API-Key: your-secret-key" \
  -F "file=@result.json" \
  -o output.txt
```

**cURL - With date filter:**
```bash
curl -X POST http://localhost:8080/api/convert \
  -H "X-API-Key: your-secret-key" \
  -F "file=@result.json" \
  -F "startDate=2024-01-01" \
  -F "endDate=2024-12-31" \
  -o output.txt
```

**cURL - With keywords filter:**
```bash
curl -X POST http://localhost:8080/api/convert \
  -H "X-API-Key: your-secret-key" \
  -F "file=@result.json" \
  -F "keywords=telegram,export" \
  -F "excludeKeywords=spam,bot" \
  -o output.txt
```

**Python Requests:**
```python
import requests

files = {
    'file': open('result.json', 'rb'),
}
data = {
    'startDate': '2024-01-01',
    'endDate': '2024-12-31',
    'keywords': 'telegram,export',
}
headers = {
    'X-API-Key': 'your-secret-key'
}

response = requests.post(
    'http://localhost:8080/api/convert',
    files=files,
    data=data,
    headers=headers
)

with open('output.txt', 'wb') as f:
    f.write(response.content)
```

---

### GET /api/health

Проверка состояния сервиса. **Не требует API ключа.**

**Request:**

```http
GET /api/health HTTP/1.1
Host: localhost:8080
```

**Response (Success):**

```json
HTTP/1.1 200 OK
Content-Type: application/json

{
  "status": "UP",
  "timestamp": "2024-01-15T14:30:00Z"
}
```

**Response (Error):**

```json
HTTP/1.1 503 Service Unavailable
Content-Type: application/json

{
  "status": "DOWN",
  "timestamp": "2024-01-15T14:30:00Z",
  "details": "Redis connection failed"
}
```

**cURL:**
```bash
curl http://localhost:8080/api/health
```

---

## Error Codes

### ApiExceptionHandler Errors

| Code | HTTP | Meaning |
|------|------|---------|
| `INVALID_DATE` | 400 | Date format invalid (not YYYY-MM-DD) |
| `DATE_RANGE_INVALID` | 400 | startDate > endDate |
| `INVALID_REQUEST` | 400 | Missing required parameter (file) |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

**Example Error Response:**
```json
{
  "timestamp": "2024-01-15T14:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "startDate (2024-12-31) cannot be after endDate (2024-01-01)",
  "path": "/api/convert"
}
```

---

## Rate Limiting

Currently **no rate limiting** implemented. Recommendation for production:

```bash
# In SecurityConfig
httpSecurity
    .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
    .and()
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/health").permitAll()
        .requestMatchers("/api/**").authenticated()
    )
    .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

# Consider adding:
# - Redis-based rate limiter (e.g., Bucket4j)
# - Per-API-key limits
# - Sliding window for fairness
```

---

## Authentication

### API Key Setup

1. **Generate API Key:**
   ```bash
   # Linux/Mac:
   echo $RANDOM | md5sum | head -c 32
   # or
   openssl rand -hex 16
   ```

2. **Set Environment Variable:**
   ```bash
   export JAVA_API_KEY="your-generated-key"
   ```

3. **Use in Requests:**
   ```bash
   curl -H "X-API-Key: $JAVA_API_KEY" http://localhost:8080/api/convert ...
   ```

### Missing/Invalid Key

```json
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{
  "error": "Unauthorized",
  "message": "Missing or invalid X-API-Key header"
}
```

---

## Data Formats

### Input: Telegram Export JSON

Structure from `Telegram Desktop → Export Chat History`:

```json
{
  "messages": [
    {
      "id": 1,
      "type": "message",
      "date": "2024-01-15T14:30:00",
      "from": "User",
      "text": "Hello",
      "text_entities": [
        {
          "type": "bold",
          "offset": 0,
          "length": 5
        }
      ]
    }
  ]
}
```

### Output: Formatted Text

```
TIMESTAMP_HH:MM [FORMATTED_TEXT]

20240115_14:30 **Hello**
20240115_14:31 Regular text with [link](https://example.com)
20240115_14:32 *Italic* and `code` and ~~strikethrough~~
```

**Entity Types Supported:**
- `bold` → `**text**`
- `italic` → `*text*`
- `underline` → `__text__` (if Markdown parser supports)
- `strikethrough` → `~~text~~`
- `code` → `` `text` ``
- `code_block` → ` ```text``` `
- `text_link` → `[text](url)`
- `pre` → Code block with language
- `mention` → `@username`
- `hashtag` → `#tag`
- `cashtag` → `$CASHTAG`
- `spoiler` → `||text||`
- `custom_emoji` → `[emoji_<doc_id>]`
- Bank card numbers → `[CARD]` (redacted for privacy)

---

## Limits & Constraints

| Constraint | Value | Notes |
|-----------|-------|-------|
| Max file size | 2GB | Multipart upload limit |
| Max date range | Unlimited | But can't exceed export date range |
| Max keywords | 1000 | Comma-separated list |
| Timeout | No limit | Streaming response |
| Concurrent requests | No limit* | *Recommend monitoring |

---

## Security

### Best Practices

1. **Keep API Key Secret**
   - Store in environment variables, not in code
   - Rotate periodically
   - Use different keys for dev/staging/prod

2. **HTTPS Only**
   - Never send API key over HTTP
   - Use SSL/TLS in production
   - Set up nginx reverse proxy with SSL

3. **Input Validation**
   - File size checked (2GB limit)
   - Date format validated (strict ISO 8601)
   - Keywords sanitized before processing
   - URLs whitelisted (XSS protection)

4. **Error Messages**
   - Generic error messages to prevent info leakage
   - Log details server-side for debugging

---

## Related Documentation

- 🏛️ [ARCHITECTURE.md](ARCHITECTURE.md) — System design overview
- 📖 [DEVELOPMENT.md](DEVELOPMENT.md) — Contributing, testing, code style
- 🔧 [SETUP.md](SETUP.md) — Installation & Configuration
