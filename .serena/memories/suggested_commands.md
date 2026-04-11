---
name: Suggested Commands
description: Commands for development, testing, building, and deployment
type: project
---

# Development Commands

## Project Structure
```
/root/Projects/Telegram-Export-Cleaner/
├── src/main/java/com/tcleaner/     # Java source code
├── src/test/java/com/tcleaner/     # Java tests
├── export-worker/                  # Python worker
├── docs/                            # Documentation
├── docker-compose.yml               # Local development stack
├── pom.xml                          # Maven config
├── checkstyle.xml                   # Java code style
└── .github/workflows/               # CI/CD pipelines
```

## Java/Maven Commands

### Build & Package
```bash
# Clean build (runs tests, creates JAR)
mvn clean package

# Build without tests
mvn clean package -DskipTests

# Build with code style check
mvn clean package -Pcheckstyle

# Only compile (no tests, no package)
mvn clean compile

# Only run tests
mvn clean test

# Code style check only
mvn checkstyle:check

# Fix code style (auto-format)
mvn checkstyle:format
```

### IDE & Development
```bash
# Generate IDE files (IntelliJ IDEA, Eclipse)
mvn idea:idea
mvn eclipse:eclipse

# Update dependencies
mvn dependency:tree
mvn versions:display-dependency-updates
```

## Python/Pytest Commands

### Setup & Dependencies
```bash
# Install dev dependencies (includes pytest)
pip install -r export-worker/requirements-dev.txt

# Install production dependencies
pip install -r export-worker/requirements.txt
```

### Testing
```bash
# Run all tests
pytest export-worker/tests/

# Run with coverage
pytest export-worker/tests/ --cov=export-worker --cov-report=html

# Run specific test file
pytest export-worker/tests/test_message_cache.py

# Run with verbose output
pytest export-worker/tests/ -v

# Run with asyncio debugging
pytest export-worker/tests/ -v --tb=short
```

## Docker Compose (Local Development)

### Start/Stop Services
```bash
# Start all services (Java bot, Python worker, Redis)
docker compose up -d

# Stop all services
docker compose down

# Restart specific service
docker compose restart java-bot
docker compose restart worker
docker compose restart redis

# View service status
docker compose ps

# View logs
docker logs -f telegram-cleaner-java-bot-1
docker logs -f telegram-cleaner-python-worker-1
docker logs -f telegram-cleaner-redis-1

# Clean up (remove volumes)
docker compose down -v
```

## Git & CI/CD

### Local Git Workflow
```bash
# Create feature branch from dev
git checkout -b feature/my-feature dev

# Commit (RUSSIAN messages, no Co-Authored-By)
git commit -m "FEAT: описание на русском языке"

# Push to dev (NOT main)
git push origin feature/my-feature
# Create PR to dev

# After merge: sync local dev
git checkout dev
git pull origin dev
```

### CI/CD Status
```bash
# View GitHub Actions (via gh CLI)
gh run list

# View specific workflow run
gh run view <run-id>

# Check branch CI status
gh pr view <pr-number>
```

## Health Checks

### Java API
```bash
# Health endpoint
curl http://localhost:8080/api/health

# With API key (if required)
curl -H "X-API-Key: your-key" http://localhost:8080/api/health
```

### Python Worker
```bash
# Check Redis connection
redis-cli ping

# View Python worker logs
docker logs -f telegram-cleaner-python-worker-1

# Monitor worker queue
redis-cli LLEN export:queue
redis-cli BLPOP export:queue 1
```

## Documentation Commands

### View Docs
```bash
# Architecture overview
cat docs/ARCHITECTURE.md

# Development workflow
cat docs/DEVELOPMENT.md

# API reference
cat docs/API.md

# Python worker details
cat docs/PYTHON_WORKER.md

# Setup & deployment
cat docs/SETUP.md
```

## Code Quality

### Linting & Formatting

#### Java
```bash
# Check code style
mvn checkstyle:check

# Auto-format code
mvn checkstyle:format

# Run SpotBugs (if configured)
mvn clean compile spotbugs:check
```

#### Python
```bash
# PEP 8 check (flake8)
flake8 export-worker/

# Auto-format (black)
black export-worker/

# Type checking (mypy)
mypy export-worker/
```

## Important Notes

### ⚠️ DO NOT (per CLAUDE.md)
- ❌ Run `mvn test` locally — tests only in CI
- ❌ Rebuild Docker containers manually — only via GitHub Actions
- ❌ Push to `main` branch directly — only via PR with --squash
- ❌ Add Co-Authored-By trailers to commits
- ❌ Run `pytest` locally — tests only in CI

### ✅ DO
- ✅ Develop on `dev` branch
- ✅ Create PRs dev → main (will be squashed)
- ✅ Write tests in PR (CI will run them)
- ✅ Use Russian in commit messages & comments
- ✅ Keep CLAUDE.md in .gitignore (no commits to it)
