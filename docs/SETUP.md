# Setup & Deployment Guide

## Prerequisites

- Docker & Docker Compose
- Telegram account with API credentials
- Bot token from @BotFather

## Step 1: Get Telegram API Credentials

1. Go to [my.telegram.org/apps](https://my.telegram.org/apps)
2. Login with your Telegram account
3. Create new application (Desktop, Android, etc.)
4. Save: **API ID** and **API Hash**

```bash
TELEGRAM_API_ID=1234567890
TELEGRAM_API_HASH=abcdef1234567890abcdef1234567890
```

## Step 2: Create Bot Token

1. Open Telegram and find [@BotFather](https://t.me/botfather)
2. Send `/newbot`
3. Follow instructions (choose name, username)
4. Save bot token: `123456:ABCDEFGhIjKlMnOpQrStUvWxYz-_=`

```bash
TELEGRAM_BOT_TOKEN=123456:ABCDEFGhIjKlMnOpQrStUvWxYz-_=
```

## Step 3: Generate Pyrogram Session String

Session string — stateless authentication для Python worker (production).

**Option A: Using Docker (Recommended)**

```bash
docker run -it -v $(pwd)/export-worker:/app python:3.11-slim bash
cd /app
pip install pyrogram
python get_session.py
```

Follow prompts:
```
Enter phone number: +1234567890
Enter verification code: 12345
Session created!
Session string: BQF...
```

Save the session string.

**Option B: Local Python**

```bash
cd export-worker
python -m venv venv
source venv/bin/activate  # or `venv\Scripts\activate` on Windows
pip install pyrogram
python get_session.py
```

## Step 4: Create .env File

```bash
cp .env.example .env
```

Edit `.env`:
```env
# Telegram API
TELEGRAM_API_ID=1234567890
TELEGRAM_API_HASH=abcdef1234567890abcdef1234567890
TELEGRAM_BOT_TOKEN=123456:ABCDEFGhIjKlMnOpQrStUvWxYz-_=
TELEGRAM_SESSION_STRING=BQF...

# Redis (optional, defaults shown)
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_DB=0

# Cache settings (optional)
CACHE_ENABLED=true
CACHE_TTL_DAYS=30

# Logging
LOG_LEVEL=INFO
```

**Generating API Key:**
```bash
# Linux/Mac
openssl rand -hex 16

# Windows PowerShell
-join ((0..15) | ForEach-Object {'{0:x}' -f (Get-Random -Maximum 16)})

# Python
import secrets
secrets.token_hex(16)
```

## Step 5: Start Services

```bash
docker compose up -d
```

Verify startup:
```bash
# Java Bot
curl http://localhost:8080/api/health

# Response:
# {"status":"UP"}
```

Check logs:
```bash
docker logs st_java -f      # Java Bot logs
docker logs st_python -f    # Python Worker logs
docker logs st_redis -f     # Redis logs
```

Wait for initialization (~15 seconds):
```
st_java    | ... Started TelegramCleanerApplication
st_python  | ✅ Initializing Export Worker...
st_python  | ✅ Connected as YourName (@yourname)
```

## Step 6: Test Bot

Open Telegram and find your bot (search by username or use link):

```
/start
↓ Bot responds with introduction

@durov
↓ Choose: [📦 All] [📅 Date Range]

Select [📅 Date Range]
↓ Enter start date: 01.01.2024

↓ Enter end date: 31.12.2024

↓ Bot: "⏳ Processing..."
↓ After 5-30 seconds: Bot sends output.txt file
```

If not working, check logs:
```bash
docker logs st_java | grep -i "error\|exception"
docker logs st_python | grep -i "error\|exception"
```

## Configuration Details

### Java Bot

**Ports:**
- `8080` — REST API for `/api/convert`, `/api/health`

**Environment Variables:**
```env
TELEGRAM_BOT_TOKEN=...       # Bot token from @BotFather
spring.redis.host=redis      # Redis host
spring.redis.port=6379       # Redis port
```

### Python Worker

**Ports:**
- No exposed ports (internal only)

**Environment Variables:**
```env
TELEGRAM_API_ID=...              # API ID from my.telegram.org
TELEGRAM_API_HASH=...            # API Hash from my.telegram.org
TELEGRAM_SESSION_STRING=...      # Pyrogram session string
TELEGRAM_PHONE_NUMBER=...        # Phone (only for file-based auth)
JAVA_API_URL=http://java:8080   # Java Bot URL
REDIS_HOST=redis                # Redis host
REDIS_PORT=6379                 # Redis port
CACHE_ENABLED=true              # Enable message caching
CACHE_TTL_DAYS=30              # Cache retention (days)
```

### Redis

**Configuration:**
```bash
# Memory limit
maxmemory 256mb

# Eviction policy (protect critical data)
maxmemory-policy volatile-lru

# Keys:
telegram_export         # Main queue
telegram_export_express # Priority queue (cached)
active_export:{userId}  # User lock (SET NX)
cancel_export:{taskId}  # Cancellation flag
cache:msgs:{chatId}     # Cached messages by ID
cache:dates:{chatId}    # Cached messages by date
canonical:*             # ID mapping cache
```

## Docker Compose

```yaml
version: '3.9'

services:
  java:
    image: telegram-cleaner:java
    ports:
      - "8080:8080"
    environment:
      - TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
    depends_on:
      - redis
    restart: unless-stopped

  python:
    image: telegram-cleaner:python
    environment:
      - TELEGRAM_API_ID=${TELEGRAM_API_ID}
      - TELEGRAM_API_HASH=${TELEGRAM_API_HASH}
      - TELEGRAM_SESSION_STRING=${TELEGRAM_SESSION_STRING}
    depends_on:
      - redis
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    command: redis-server --maxmemory 256mb --maxmemory-policy volatile-lru
    restart: unless-stopped
```

## Troubleshooting

### Bot not responding

```bash
# Check Java bot status
curl http://localhost:8080/api/health

# If not running
docker logs st_java

# Common issues:
# - TELEGRAM_BOT_TOKEN not set or invalid
# - Redis not running (REDIS_HOST unreachable)
# - Port 8080 already in use
```

### Cannot export chat

```bash
# Check Python worker
docker logs st_python | grep -i "error"

# Common issues:
# - TELEGRAM_SESSION_STRING invalid or expired
# - TELEGRAM_API_ID/TELEGRAM_API_HASH wrong
# - Chat ID invalid or private chat
# - Worker not started (waiting for initialization)
```

### Redis connection failed

```bash
# Check Redis
redis-cli
> PING
# Should return: PONG

# Check from containers
docker exec st_java redis-cli -h redis PING
docker exec st_python redis-cli -h redis PING
```

### High memory usage

```bash
# Check Redis
redis-cli
> INFO memory
> DBSIZE

# Clear old cache
redis-cli
> FLUSHDB     # WARNING: clears ALL data
```

### Slow exports

1. Check Telegram API rate limits (FloodWait)
   ```bash
   docker logs st_python | grep "FloodWait"
   ```

2. Check network latency to Telegram
   ```bash
   docker exec st_python ping -c 3 149.154.175.50  # Telegram DC IP
   ```

3. Check Redis performance
   ```bash
   redis-cli
   > SLOWLOG GET 10  # Last 10 slow commands
   ```

## Updating

### Update Docker Images

```bash
# Pull latest code
git pull origin main

# Rebuild images
docker compose build

# Restart
docker compose down
docker compose up -d
```

### Update .env Settings

```bash
# Edit .env
nano .env

# Restart affected service
docker compose restart java   # or `python`
```

## Backup & Restore

### Backup Redis Data

```bash
# Create backup
docker exec st_redis redis-cli BGSAVE

# Copy from container
docker cp st_redis:/data/dump.rdb ./backup/
```

### Restore Redis Data

```bash
# Copy to container
docker cp backup/dump.rdb st_redis:/data/

# Restart Redis
docker compose restart redis
```

## Production Deployment

### Security Checklist

- [ ] Set `TELEGRAM_SESSION_STRING` not in git (use .env)
- [ ] Enable HTTPS (use nginx reverse proxy)
- [ ] Restrict Redis to internal network only
- [ ] Set resource limits in docker-compose (memory, CPU)
- [ ] Enable log rotation (don't fill disk)

### Resource Limits

```yaml
services:
  java:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          cpus: '1'
          memory: 1G
```

### Nginx Reverse Proxy

```nginx
server {
    listen 443 ssl http2;
    server_name api.example.com;

    ssl_certificate /etc/letsencrypt/live/api.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.example.com/privkey.pem;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

See also:
- [DEVELOPMENT.md](DEVELOPMENT.md) — contributing, testing
- [ARCHITECTURE.md](ARCHITECTURE.md) — system design
- [API.md](API.md) — REST API reference
