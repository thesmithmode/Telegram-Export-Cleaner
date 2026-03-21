# Security Analysis Report: Telegram Export Cleaner

**Date**: 2026-03-21
**Severity Summary**: 1 HIGH | 4 MEDIUM | 1 LOW
**Status**: REMEDIATION IN PROGRESS

---

## Executive Summary

Security code review identified 6 issues in the Java Spring Boot Telegram Export Cleaner application. Primary risks include XSS via unsanitized URLs, race conditions in rate limiting, insufficient input validation, and missing CORS configuration. All issues have clear remediation paths without requiring architectural changes.

---

## Issue Breakdown

### 1. XSS Vulnerability in MarkdownParser (text_link entity)

**Severity**: HIGH
**CWE**: CWE-79 (Improper Neutralization of Input During Web Page Generation)
**Location**: `src/main/java/com/tcleaner/format/MarkdownParser.java`, lines 97-99

**Vulnerability Description**:
```java
private static String parseTextLink(JsonNode entity, String text) {
    String href = entity.has("href") ? entity.get("href").asText() : "#";
    return "[" + text + "](" + href + ")";  // VULNERABLE: href not validated
}
```

The `href` parameter is directly embedded into markdown output without validation. Malicious Telegram exports can inject:
- `javascript:alert('xss')`
- `data:text/html,<script>alert('xss')</script>`
- `vbscript:msgbox('xss')`

While the immediate output is markdown (not executable HTML), if downstream consumers render markdown to HTML without sanitization, attackers can achieve JavaScript execution.

**Attack Scenario**:
1. Attacker sends message to shared Telegram group with `text_link` entity containing `href="javascript:alert('stolen')"`
2. User exports chat and processes via API
3. Frontend receives markdown and renders with markdown-to-HTML library (e.g., marked.js)
4. Malicious URL executes in user's browser

**Business Impact**: Account compromise, credential theft, malware injection

**Remediation**:
- Validate `href` parameter against whitelist of safe protocols (http, https, mailto, ftp)
- Use `URL` class to parse and validate URL format
- Reject URLs with encoded/obfuscated protocols
- Add security test cases with XSS payloads

**Risk Score**: 8.5/10 (High)

---

### 2. CORS Not Configured in SecurityConfig

**Severity**: MEDIUM-HIGH
**CWE**: CWE-16 (Configuration)
**Location**: `src/main/java/com/tcleaner/SecurityConfig.java`

**Vulnerability Description**:
Currently, SecurityConfig permits all requests but doesn't explicitly configure CORS headers:
```java
.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
// CORS not configured — browser defaults apply
```

If the frontend is hosted on a different domain (e.g., `https://tcleaner.com` vs `http://api.tcleaner.com`), browsers will enforce Same-Origin Policy. Without explicit CORS configuration:
- `Access-Control-Allow-Origin` header not sent
- Cross-origin requests fail silently
- Attackers could host malicious frontend on attacker domain (not mitigated by CORS config alone, but still a risk)

**Attack Scenario**:
1. Attacker hosts malicious frontend at `evil.com`
2. JavaScript tries to POST to legitimate API at `tcleaner.api`
3. Without CORS headers, request fails (good), but configuration is implicit
4. If API is later deployed with overly permissive CORS (`*`), attacker gains access

**Business Impact**: Unintended cross-origin access, unauthorized API usage

**Remediation**:
- Explicitly configure CORS with whitelist of allowed origins
- Use `CorsRegistry` in SecurityConfig
- Make allowed origins configurable via `application.properties`
- Default: allow `localhost:3000`, `localhost:8081` (development)
- Production: configure via environment variable `ALLOWED_ORIGINS`

**Risk Score**: 6.5/10 (Medium-High)

---

### 3. Race Condition in Rate Limiting

**Severity**: MEDIUM
**CWE**: CWE-362 (Concurrent Execution using Shared Resource with Improper Synchronization)
**Location**: `src/main/java/com/tcleaner/api/FileController.java`, lines 94-106

**Vulnerability Description**:
```java
long last = lastUploadTime.get();           // T1: Thread A reads current time
long now = System.currentTimeMillis();
if (now - last < RATE_LIMIT_MS) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)...;
}
if (!lastUploadTime.compareAndSet(last, now)) {  // T2: Thread B wins race
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)...;
}
```

Window exists between `get()` (T1) and `compareAndSet()` (T2):
- Thread A reads `last = 0` at T1
- Thread B reads `last = 0` at T1 (before A's compareAndSet)
- Thread A executes compareAndSet successfully
- Thread B also executes compareAndSet successfully (because `last` unchanged)
- Both threads bypass rate limit

With 4 concurrent requests, attacker can bypass 15-second rate limit entirely.

**Attack Scenario**:
1. Attacker sends 4 concurrent upload requests
2. All threads read `lastUploadTime = 0` before any compareAndSet
3. First compareAndSet succeeds, but other 3 also succeed
4. Rate limit bypassed; 4 files processed in parallel instead of 1 per 15 seconds

**Business Impact**: Resource exhaustion, DoS attack, increased server load

**Remediation**:
- Replace `get()` + `compareAndSet()` with atomic `getAndSet(now)`
- Alternative: use `synchronized` block or `ReentrantReadWriteLock`
- Add concurrent test with 4+ threads to verify fix
- Test with `CountDownLatch` to ensure race condition cannot occur

**Risk Score**: 5.5/10 (Medium)

---

### 4. Input Validation: Null Text Field in MessageProcessor

**Severity**: MEDIUM
**CWE**: CWE-476 (Null Pointer Dereference)
**Location**: `src/main/java/com/tcleaner/core/MessageProcessor.java`, line 56-57

**Vulnerability Description**:
```java
JsonNode textNode = message.get("text");
String text = MarkdownParser.parseText(textNode);  // parseText handles null
```

While `MarkdownParser.parseText()` handles null safely, the code doesn't validate message structure. Malformed JSON could cause silent failures or unexpected behavior:
- Missing "text" field in message object
- "text" field is array instead of string
- "text" field contains invalid entity structure

**Attack Scenario**:
1. Attacker crafts malicious JSON with missing "text" field
2. Message silently returns null from processMessage()
3. If caller doesn't handle null, NullPointerException occurs
4. Server error logs expose structure information

**Business Impact**: Information disclosure, application crash, DoS via malformed input

**Remediation**:
- Add explicit null checks after `message.get("text")`
- Validate message structure has required fields
- Create `MessageValidator` utility class
- Return meaningful error for malformed input
- Add comprehensive edge case tests

**Risk Score**: 5.0/10 (Medium)

---

### 5. Hardcoded /tmp Paths in StorageConfig

**Severity**: MEDIUM
**CWE**: CWE-426 (Untrusted Search Path)
**Location**: `src/main/java/com/tcleaner/storage/StorageConfig.java`, lines 20, 23

**Vulnerability Description**:
```java
private String importPath = System.getProperty("java.io.tmpdir") + "/tcleaner/import";
private String exportPath = System.getProperty("java.io.tmpdir") + "/tcleaner/export";
```

Using `/tmp` (or `java.io.tmpdir`) for production file storage introduces risks:
1. **Persistence**: `/tmp` often cleared on reboot or by cron jobs (`tmpwatch`)
2. **Confidentiality**: `/tmp` is world-readable on Unix; other users can access exported files
3. **Integrity**: Other processes can read/modify files in shared `/tmp`
4. **Hard to Configure**: While `application.properties` supports override, default is insecure

**Attack Scenario**:
1. User exports sensitive chat data, file written to `/tmp/tcleaner/export/{uuid}.md`
2. Another user on same server runs `ls -la /tmp/tcleaner/export/`
3. Attacker reads exported chat history (PII, credentials, etc.)
4. Server reboots; files in `/tmp` deleted; data loss

**Business Impact**: Data breach, information disclosure, regulatory violations (GDPR, HIPAA)

**Remediation**:
- Keep `java.io.tmpdir` as default for development
- Add validation in `@PostConstruct` to create directories securely (mode 0700)
- Document production requirement: set `app.storage.import-path` and `app.storage.export-path`
- Recommend `/opt/tcleaner/storage` or `/var/lib/tcleaner`
- Enforce via configuration documentation and deployment guides

**Risk Score**: 5.5/10 (Medium)

---

### 6. Telegram User ID Validation Missing

**Severity**: LOW
**CWE**: CWE-1025 (Comparison Using Wrong Factors)
**Location**: `src/main/java/com/tcleaner/bot/ExportBot.java`, line 112

**Vulnerability Description**:
```java
try {
    targetChatId = Long.parseLong(parts[1].trim());  // Only checks format, not range
} catch (NumberFormatException e) {
    // Handles parse error but not invalid ID
}
```

`Long.parseLong()` validates that input is numeric but not that it's a valid Telegram ID:
- Telegram user IDs are 32-bit positive integers (0 to 2147483647)
- Telegram group IDs are negative (format: -100 followed by 10 digits for supergroups)
- Invalid IDs (e.g., `Long.MAX_VALUE`, `0`, random large numbers) not rejected

**Attack Scenario**:
1. Attacker sends `/export 9999999999999999` (exceeds 32-bit range)
2. Code accepts it, but Telegram API call fails later
3. Error handling might expose API details or cause unexpected behavior
4. Low impact on this app, but poor validation discipline

**Business Impact**: Unexpected API errors, poor user experience, potential information leakage

**Remediation**:
- Create `TelegramIdValidator` utility class
- Validate user IDs: 1 to 2147483647
- Validate group IDs: negative with 13-15 digits total
- Provide clear error message for invalid ID format
- Add test cases covering valid/invalid ID ranges

**Risk Score**: 2.5/10 (Low)

---

## Remediation Summary

| Issue | Severity | File | Fix Type | Effort | Test Coverage |
|-------|----------|------|----------|--------|----------------|
| XSS (text_link) | HIGH | MarkdownParser.java | Add UrlValidator utility | Low | MarkdownParserSecurityTest |
| CORS Missing | MEDIUM-HIGH | SecurityConfig.java | Add CorsRegistry config | Low | SecurityConfigTest |
| Rate Limit Race | MEDIUM | FileController.java | Use getAndSet() | Low | Concurrent stress test |
| Null Text Field | MEDIUM | MessageProcessor.java | Add validation | Low | MessageProcessorTest |
| /tmp Paths | MEDIUM | StorageConfig.java | Add @PostConstruct | Low | Deployment docs |
| Telegram ID | LOW | ExportBot.java | Add TelegramIdValidator | Low | ExportBotTest |

**Total Effort**: ~4 hours
**Test Coverage Impact**: +15-20 test cases, maintains 80%+ coverage
**Breaking Changes**: None

---

## Implementation Order

### Phase 1: Utility Classes (no test impact)
1. Create `UrlValidator.java` - XSS protection
2. Create `TelegramIdValidator.java` - input validation
3. Create comprehensive tests for utilities

### Phase 2: Fix Core Classes
1. `MarkdownParser.java` - use UrlValidator
2. `FileController.java` - fix rate limit race
3. `SecurityConfig.java` - add CORS
4. `MessageProcessor.java` - add validation
5. `StorageConfig.java` - add @PostConstruct

### Phase 3: Verify & Test
1. Run existing test suite - ensure no regressions
2. Add security-focused test cases
3. Concurrent stress testing for rate limit fix
4. Manual integration testing with curl

---

## Testing Strategy

### Unit Tests
- `UrlValidatorTest` - 10+ XSS payloads
- `TelegramIdValidatorTest` - boundary testing
- `MarkdownParserSecurityTest` - enhanced security scenarios
- `FileControllerRateLimitTest` - concurrent thread testing
- `SecurityConfigTest` - CORS configuration

### Integration Tests
- Existing `IntegrationTest.java` - ensure no breaking changes
- Concurrent upload test - verify rate limit under load
- Malformed JSON test - edge case handling

### Manual Testing
- Test XSS payloads don't execute when markdown rendered
- Verify CORS headers present in responses
- Concurrent curl requests to test rate limit
- Check directory permissions after StorageConfig validation

---

## Recommendations Beyond Scope

These recommendations should be addressed in future security reviews:

1. **Authentication & Authorization**
   - Currently all endpoints public; consider API key or OAuth2
   - Rate limiting is only defense

2. **Secrets Management**
   - Telegram bot token in properties; use encrypted config
   - Environment variables preferred for sensitive data

3. **Logging & Monitoring**
   - No rate limit violation alerts
   - No suspicious input pattern detection
   - Add security event logging

4. **Dependency Management**
   - Regular `mvn dependency:check-update`
   - Automated vulnerability scanning (Trivy, Snyk)

5. **Infrastructure**
   - Document secure path configuration for production
   - Add WAF rules for malicious payloads
   - Implement request signing/HMAC for sensitive APIs

---

## Compliance Impact

- **OWASP Top 10**: Addresses A07:2021 (Identification and Authentication Failures), A03:2021 (Injection)
- **CWE Top 25**: Covers CWE-79, CWE-362, CWE-476
- **GDPR**: Improves data protection (secure storage paths)
- **HIPAA** (if used): Improves access controls and audit trails

---

## Sign-Off

This analysis identifies significant but remediable security issues. Implementation of recommended fixes will substantially improve application security posture.

**Reviewed by**: Security Engineer
**Date**: 2026-03-21
**Status**: READY FOR IMPLEMENTATION
