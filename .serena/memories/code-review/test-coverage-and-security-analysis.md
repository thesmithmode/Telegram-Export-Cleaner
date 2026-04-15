# Test Coverage & Security Analysis Report

## CRITICAL Issues Found

### 1. OUTDATED DEPENDENCY: psutil==5.9.8
**Location:** export-worker/requirements.txt:20
- Version 5.9.8 from 2022 — 3+ years old, multiple CVEs
- Latest: 5.11.x or 6.x (2024+)
- Impact: CPU/memory monitoring utilities may have security vulnerabilities
- Action: Update to psutil==5.11.4 or 6.0.0 (verify compatibility first)

### 2. Missing Error Path Test Coverage in Python
**Fixed (2026-04-14):** Topic export date filtering tests added (4 tests in TestGetTopicHistoryDirect).
**Fixed (2026-04-14):** messages.Search replaced with messages.GetReplies for forum topics — Search returned incomplete results.
**Locations:**
- export-worker/tests/test_pyrogram_client.py: No tests for actual message fetch errors
- export-worker/tests/test_java_client.py: No tests for HTTP retry logic beyond mocks
- export-worker/tests/test_message_cache.py: No eviction/LRU tests when disk limit exceeded
- Main concern: Queue consumer doesn't test job failure handling (dead-letter queue)

### 3. Docker Security: User Privilege Escalation Risk
**Location:** Dockerfile:18
- Java container uses unprivileged `app` user (✓ good)
- **But:** export-worker/Dockerfile not provided in review — **cannot verify if Python also runs unprivileged**
- **Risk:** If Python worker runs as root, any code injection exploits system
- Action: Verify export-worker/Dockerfile has equivalent `USER` directive

### 4. Redis noeviction Policy → Silent Data Loss in Prod
**Location:** docker-compose.prod.yml:95
- `--maxmemory-policy noeviction` — correct (fails loudly on overflow)
- **BUT:** No monitoring/alerts configured when Redis approaches 128MB limit
- Impact: Jobs silently fail when Redis fills up (no visible error)
- Action: Add Prometheus metrics or redis-monitor to CI output

## MAJOR Issues

### 5. Java Test Coverage Gaps
**Missing test files for critical paths:**
- NO tests for multipart file upload error scenarios (empty, corrupted, size limits)
- NO tests for async cancellation during streaming
- NO tests for memory pressure (TelegramExporter with massive JSON)
- SecurityConfigTest exists but doesn't test CORS/CSRF/XXE protections

### 6. Python Integration Test Isolation Issue
**Location:** .github/workflows/ci.yml:86-133
- `python-integration-tests` uses real Redis in GitHub Actions
- No mock for Java API responses — tests depend on hardcoded localhost
- `python-e2e-tests` runs full message pipeline but doesn't validate round-trip correctness
- **Gap:** No test simulates network failure + retry logic

### 7. CI Concurrency & Flakiness Risk
**Location:** .github/workflows/ci.yml:8-10
- `cancel-in-progress: true` on dev branch (good)
- `build.yml` (main branch): `cancel-in-progress: false` (good)
- **BUT:** No test timeout fail-fast — Python tests can hang if redis service doesn't start
- Issue: `python-performance-tests` runs unbuffered; could timeout if system slow

### 8. Incomplete Error Code Coverage
**Locations:**
- export-worker/tests/test_export_worker.py:95 — mock returns hardcoded error `"CHAT_NOT_ACCESSIBLE"`
- No tests for partial failures: chat accessible but individual messages fail to fetch
- No tests for rate-limiting (Telegram API returns 429)

## MINOR Issues

### 9. Missing Test for Cache Edge Cases
**Location:** export-worker/tests/test_message_cache.py
- Tests store/retrieve, but NO test for concurrent access from multiple workers
- NO test for database corruption recovery (SQLite WAL edge case)
- NO test for CACHE_ENABLED=false code path in ExportWorker.process_job

### 10. Inconsistent Logging in Tests
**Locations:**
- Java tests: Use LogbackAppender to verify log output (ApiKeyFilterTest)
- Python tests: No equivalent — never assert on log messages
- Action: Add conftest.py caplog fixtures for Python critical paths

### 11. CI/CD Smoke Test Port Mismatch
**Location:** .github/workflows/build.yml:206
```bash
curl -fsS http://127.0.0.1:8081/api/health  # Production port
```
- docker-compose.yml:55 → localhost:8080 (dev)
- docker-compose.prod.yml:14 → 8081 (prod)
- Post-deploy smoke test uses correct 8081 ✓, but comment doesn't explain why

### 12. Test Fixtures Don't Cover Real Telegram Edge Cases
**Location:** export-worker/tests/conftest.py
- `simple_message()` fixture: perfect message with all fields
- Missing fixtures:
  - Message with no from_user (anonymous channel post)
  - Message with pinned_message reference
  - Message from deleted user account
  - Message with custom_emoji in text

### 13. Java JaCoCo Coverage Threshold vs. Actual Coverage
**Location:** pom.xml:140-150
- Line coverage minimum: 80%
- No branch coverage requirement (conditional coverage missing)
- No report published to GitHub (CI doesn't fail if coverage drops)

### 14. Python Doesn't Check Coverage Threshold in CI
**Location:** .github/workflows/ci.yml:77
- `--cov-report=xml` written but NOT checked against minimum
- Actions: codecov-action has `fail_ci_if_error: false`
- Result: Coverage can drop from 80% → 60% and CI passes

### 15. embed-redis 0.7.3 May Not Start on Windows CI
**Location:** pom.xml:82
- embedded-redis version 0.7.3 has OS compatibility issues
- No skip logic in CI if running on non-Linux
- Risk: Tests work locally (Linux) but could fail in CI on macOS

## OBSERVATIONS (Not Issues)

✓ Good: Separate unit/integration/E2E/performance test suites
✓ Good: AsyncMock + pytest-asyncio properly used in Python tests  
✓ Good: JUnit 5 @DisplayName for readable test output in Java
✓ Good: docker-compose.yml has healthchecks with proper conditions
✓ Good: Resource limits enforced (768m Java, 768m Python, 256m Redis)
✓ Good: Logging drivers configured with max-size rotation

## Recommended Priority

1. **Immediate:** Update psutil (CVE risk)
2. **High:** Add python-cache eviction tests + concurrent access tests
3. **High:** Verify export-worker/Dockerfile runs unprivileged user
4. **Medium:** Add missing test fixtures for real Telegram edge cases
5. **Medium:** Enable coverage threshold checks in CI (both Java and Python)
6. **Low:** Document port mapping differences in CI output
