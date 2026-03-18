# ⚙️ Требования для Setup

**Дата:** 2026-03-18
**Версия:** 1.0

---

## 🔧 Инструменты и версии

### Java Backend

**Требуемые версии:**
```
Java:           11+ (рекомендуется 17+)
Spring Boot:    2.7+ (текущая версия: 2.x.x)
Maven:          3.6+
Docker:         20.10+
Docker Compose: 2.0+
```

**Проверить текущие версии:**
```bash
java -version
mvn -version
docker -version
docker-compose --version
```

### Python Export Worker

**Требуемые версии:**
```
Python:         3.9+ (рекомендуется 3.11+)
pip:            21+
virtualenv:     (опционально, но рекомендуется)
Docker:         20.10+
```

**Проверить текущие версии:**
```bash
python --version
pip --version
```

### Redis

**Требуемая версия:**
```
Redis:          7.0+ (текущая версия: 7.x)
```

**Провер текущей версии (будет в Docker):**
```bash
docker run redis:7-alpine redis-server --version
```

### Telegram

**Требуемые данные:**
```
API_ID:         (число, получить из my.telegram.org)
API_HASH:       (строка, получить из my.telegram.org)
BOT_TOKEN:      (получить от @BotFather)
PHONE_NUMBER:   (номер телефона для экспорта)
```

---

## 📋 Пошаговая подготовка

### Шаг 1: Получить Telegram API Credentials

#### 1.1 API_ID и API_HASH

1. Зайти на https://my.telegram.org/apps
2. Войти с номером телефона
3. Создать приложение:
   - **App title:** `telegram-export-cleaner`
   - **Short name:** `export_cleaner`
   - Остальные поля: не важны
4. Получить:
   - **api_id** (число, ~9 цифр)
   - **api_hash** (строка, ~32 символа)

**Пример:**
```
api_id=1234567
api_hash=abcdef123456789abcdef123456789ab
```

#### 1.2 Bot Token

1. Открыть Telegram → найти @BotFather
2. Отправить `/newbot`
3. Ввести имя бота (например: `export_cleaner_bot`)
4. Ввести username (например: `@my_export_cleaner_bot`)
5. Получить Bot Token (строка вида `123:ABCdef...`)

**Пример:**
```
Bot Token: 123456789:ABCdefGHIjklmnOpqrsTUVwxyz1234567
```

### Шаг 2: Подготовить окружение

#### 2.1 Клонировать репозиторий (или уже есть)

```bash
cd /home/user/Telegram-Export-Cleaner
```

#### 2.2 Создать .env файл

**Файл:** `.env` (в корне проекта)

```env
# Telegram API
TELEGRAM_API_ID=1234567
TELEGRAM_API_HASH=abcdef123456789abcdef123456789ab
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmnOpqrsTUVwxyz1234567
TELEGRAM_PHONE_NUMBER=+79991234567  # Номер телефона для экспорта

# Redis
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_DB=0

# Java App
JAVA_APP_PORT=8080
JAVA_OPTS=-Xmx1024m

# Python Worker
PYTHON_WORKER_PORT=5000
PYTHON_WORKER_WORKERS=5  # Количество одновременных экспортов
```

**ВАЖНО:**
- ❌ НЕ коммитить .env в git
- ✅ Добавить в .gitignore (если еще не добавлено)
- 🔒 Заменить значения на реальные

**Проверить .gitignore:**
```bash
cat .gitignore | grep ".env"
# Если пусто, добавить:
echo ".env" >> .gitignore
```

#### 2.3 Создать .env.example для документации

**Файл:** `.env.example`

```env
# Copy this file to .env and fill in the values

# Telegram API (get from https://my.telegram.org/apps)
TELEGRAM_API_ID=YOUR_API_ID
TELEGRAM_API_HASH=YOUR_API_HASH

# Telegram Bot Token (get from @BotFather)
TELEGRAM_BOT_TOKEN=YOUR_BOT_TOKEN

# Phone number for export (with country code, e.g., +79991234567)
TELEGRAM_PHONE_NUMBER=+YOUR_PHONE_NUMBER

# Redis Configuration
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_DB=0

# Java Application
JAVA_APP_PORT=8080
JAVA_OPTS=-Xmx1024m

# Python Export Worker
PYTHON_WORKER_PORT=5000
PYTHON_WORKER_WORKERS=5
```

### Шаг 3: Обновить Docker Compose

**Файл:** `docker-compose.yml`

```yaml
version: '3.8'

services:
  # Existing Redis (обновить конфиг)
  redis:
    image: redis:7-alpine
    container_name: telegram_export_redis
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes --maxmemory 2gb --maxmemory-policy allkeys-lru
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Existing Java App (без изменений)
  java-app:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: telegram_export_java
    ports:
      - "${JAVA_APP_PORT}:8080"
    environment:
      - SPRING_REDIS_HOST=${REDIS_HOST}
      - SPRING_REDIS_PORT=${REDIS_PORT}
      - SPRING_REDIS_DATABASE=${REDIS_DB}
      - TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
      - JAVA_OPTS=${JAVA_OPTS}
    depends_on:
      redis:
        condition: service_healthy
    volumes:
      - ./import:/tmp/import
      - ./export:/tmp/export
    networks:
      - telegram-export-net

  # New Python Export Worker
  python-worker:
    build:
      context: ./export-worker
      dockerfile: Dockerfile
    container_name: telegram_export_worker
    environment:
      - TELEGRAM_API_ID=${TELEGRAM_API_ID}
      - TELEGRAM_API_HASH=${TELEGRAM_API_HASH}
      - TELEGRAM_PHONE_NUMBER=${TELEGRAM_PHONE_NUMBER}
      - REDIS_HOST=${REDIS_HOST}
      - REDIS_PORT=${REDIS_PORT}
      - JAVA_APP_URL=http://java-app:8080
      - WORKERS=${PYTHON_WORKER_WORKERS}
      - LOG_LEVEL=INFO
    depends_on:
      - redis
      - java-app
    volumes:
      - ./export-worker/session:/app/session  # Pyrogram session
      - ./export-worker/logs:/app/logs
    networks:
      - telegram-export-net
    restart: unless-stopped

networks:
  telegram-export-net:
    driver: bridge

volumes:
  redis_data:
    driver: local
```

### Шаг 4: Обновить application.properties

**Файл:** `src/main/resources/application.properties`

```properties
# Spring configurations
spring.application.name=telegram-export-cleaner
server.port=8080

# Redis Configuration
spring.redis.host=${SPRING_REDIS_HOST:localhost}
spring.redis.port=${SPRING_REDIS_PORT:6379}
spring.redis.database=${SPRING_REDIS_DATABASE:0}
spring.redis.timeout=2000ms
spring.redis.jedis.pool.max-active=8
spring.redis.jedis.pool.max-idle=8
spring.redis.jedis.pool.min-idle=0

# Telegram Bot Configuration
telegram.bot.token=${TELEGRAM_BOT_TOKEN}
telegram.bot.webhook.enabled=true
telegram.bot.webhook.url=${TELEGRAM_BOT_WEBHOOK_URL:http://localhost:8080}

# Queue Configuration
queue.type=redis
queue.redis.host=${SPRING_REDIS_HOST:localhost}
queue.redis.port=${SPRING_REDIS_PORT:6379}
queue.max-concurrent=1000
queue.export-task-ttl=86400
queue.result-ttl=3600

# File Storage Configuration
app.storage.import-path=./import
app.storage.export-path=./export
app.storage.export-ttl-minutes=10
app.storage.cleanup-interval-seconds=60

# Java Application
server.servlet.context-path=/
logging.level.root=INFO
logging.level.com.tcleaner=DEBUG
```

### Шаг 5: Создать структуру папок

```bash
# Папки для файлов
mkdir -p import export export-worker/session export-worker/logs

# Проверить структуру
tree -L 2 -I 'target|node_modules'
```

**Результат:**
```
Telegram-Export-Cleaner/
├── docs/
│   ├── PLAN.md
│   ├── RESEARCH_FINDINGS.md
│   ├── QUEUE_COMPARISON.md
│   ├── ARCHITECTURE.md
│   ├── SETUP_REQUIREMENTS.md
│   └── ...
├── src/
├── export-worker/
│   ├── main.py
│   ├── requirements.txt
│   ├── Dockerfile
│   ├── session/        ← Pyrogram session (создается при запуске)
│   └── logs/          ← Логи Worker'а
├── import/            ← Входящие JSON файлы
├── export/            ← Готовые результаты .md
├── docker-compose.yml
├── .env              ← Секреты (в .gitignore!)
├── .env.example      ← Шаблон для документации
├── pom.xml
└── ...
```

---

## 🚀 Запуск системы

### Локальный запуск (Development)

#### 1. Убедиться что все готово

```bash
# Проверить Java
java -version

# Проверить Docker
docker --version
docker-compose --version

# Проверить .env файл
cat .env | grep TELEGRAM_API_ID
```

#### 2. Собрать Java приложение

```bash
mvn clean package -DskipTests
```

#### 3. Запустить Docker Compose

```bash
docker-compose up -d
```

**Ожидаемый вывод:**
```
[+] Running 3/3
 ✔ Service redis is healthy
 ✔ Service java-app started
 ✔ Service python-worker started
```

#### 4. Проверить логи

```bash
# Java app logs
docker-compose logs -f java-app

# Python worker logs
docker-compose logs -f python-worker

# Redis logs
docker-compose logs -f redis
```

#### 5. Первый запуск Python Worker (нужна аутентификация)

```bash
# Войти в контейнер Python Worker
docker-compose exec python-worker bash

# Запустить инициализацию (если требуется)
python main.py --init

# Ввести номер телефона: +79991234567
# Получить SMS код: 12345
# Ввести код: 12345
# Готово! session.dat создан

# Выход
exit
```

#### 6. Проверить что все работает

```bash
# Проверить Redis
docker-compose exec redis redis-cli ping
# Ожидаемый вывод: PONG

# Проверить Java app
curl http://localhost:8080/actuator/health
# Ожидаемый вывод: {"status":"UP"}

# Проверить логи
docker-compose logs --tail=50 java-app
```

### Остановить систему

```bash
docker-compose down

# Остановить и удалить volumes
docker-compose down -v
```

---

## 📚 Python Export Worker - Дополнительная подготовка

### Создать requirements.txt

**Файл:** `export-worker/requirements.txt`

```
pyrogram==1.4.16
aioredis==2.0.1
aiohttp==3.8.5
requests==2.31.0
python-dotenv==1.0.0
pydantic==1.10.13
pyyaml==6.0.1
pytest==7.4.0
pytest-asyncio==0.21.1
```

### Создать Dockerfile для Python Worker

**Файл:** `export-worker/Dockerfile`

```dockerfile
FROM python:3.11-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements
COPY requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy application
COPY . .

# Create session directory
RUN mkdir -p session logs

# Run worker
CMD ["python", "main.py"]
```

### Создать основной файл Python Worker

**Файл:** `export-worker/main.py` (скелет)

```python
import asyncio
import logging
import os
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

# Configure logging
logging.basicConfig(
    level=os.getenv('LOG_LEVEL', 'INFO'),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Configuration
TELEGRAM_API_ID = int(os.getenv('TELEGRAM_API_ID'))
TELEGRAM_API_HASH = os.getenv('TELEGRAM_API_HASH')
TELEGRAM_PHONE = os.getenv('TELEGRAM_PHONE_NUMBER')
REDIS_HOST = os.getenv('REDIS_HOST', 'localhost')
REDIS_PORT = int(os.getenv('REDIS_PORT', 6379))
JAVA_APP_URL = os.getenv('JAVA_APP_URL', 'http://localhost:8080')

async def main():
    logger.info("Starting Telegram Export Worker...")
    logger.info(f"Connecting to Redis: {REDIS_HOST}:{REDIS_PORT}")
    logger.info(f"Java app URL: {JAVA_APP_URL}")

    # TODO: Implement main loop
    # 1. Connect to Redis
    # 2. Listen to export-tasks
    # 3. Process each task
    # 4. Save result to export-results

if __name__ == '__main__':
    asyncio.run(main())
```

---

## 🔐 Безопасность

### Environment Variables

**Никогда не коммитить:**
- ❌ .env файл
- ❌ session.dat (содержит credentials)
- ❌ Bot token в коде
- ❌ API credentials в коде

**Правильно хранить:**
- ✅ .env файл в .gitignore
- ✅ Используйте .env.example для документации
- ✅ В production используйте secrets (Kubernetes, AWS Secrets Manager и т.д.)

### Firewall Rules

```
Telegram Bot API → свободный доступ (Telegram servers)
Redis → только из приложения (не expose публично!)
Java App → expose на 8080 (за Nginx в production)
Python Worker → только из Java app
```

---

## 📊 Проверка готовности

### Чеклист перед стартом

- [ ] Java 11+ установлен
- [ ] Python 3.9+ установлен
- [ ] Docker установлен
- [ ] Docker Compose установлен
- [ ] Telegram API_ID получен
- [ ] Telegram API_HASH получен
- [ ] Telegram Bot Token получен
- [ ] Номер телефона подготовлен
- [ ] .env файл создан с правильными значениями
- [ ] .gitignore содержит .env
- [ ] docker-compose.yml обновлен
- [ ] application.properties обновлен
- [ ] export-worker/ структура создана
- [ ] Папки import/ и export/ созданы

---

## 🆘 Troubleshooting

### Java не запускается

```bash
# Проверить логи
docker-compose logs java-app

# Проверить Java версию
java -version

# Перестроить контейнер
docker-compose build --no-cache java-app
```

### Python Worker не подключается к Redis

```bash
# Проверить Redis запущен
docker-compose logs redis

# Проверить IP/hostname
docker inspect telegram_export_redis | grep IPAddress

# Проверить переменные окружения в Worker
docker-compose exec python-worker env | grep REDIS
```

### SMS код не приходит при аутентификации Pyrogram

```
Решение:
1. Убедиться что номер телефона правильный (+7999...)
2. Ввести код из SMS
3. Если потребуется - ввести password (если включена 2FA)
4. Ждать 5-10 минут если много запросов (rate limit)
```

### Redis запросы медленные

```bash
# Проверить размер БД
redis-cli DBSIZE

# Очистить старые данные
redis-cli FLUSHDB

# Проверить AOF размер
docker-compose exec redis ls -lh /data/

# Запустить BGREWRITEAOF
redis-cli BGREWRITEAOF
```

---

**Версия:** 1.0
**Дата:** 2026-03-18
**Статус:** ✅ Готово к использованию
