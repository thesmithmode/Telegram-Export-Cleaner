# 🏗️ Архитектура системы

**Дата:** 2026-03-18
**Версия:** 1.0

---

## System Overview

```
┌────────────────────────────────────────────────────────────────┐
│                      Telegram Servers                          │
└────────────────┬─────────────────────────────────────────┬─────┘
                 │                                         │
        ┌────────▼─────────┐                   ┌──────────▼──────┐
        │   Bot API        │                   │  Client API     │
        │  (messages)      │                   │  (get_history)  │
        └────────┬─────────┘                   └──────────┬──────┘
                 │                                        │
        ┌────────▼──────────────────────────────────────────────────┐
        │              User Telegram Client                         │
        │  • Может использовать web.telegram.org                   │
        │  • Может использовать мобильное приложение               │
        │  • Может использовать Telegram Desktop                   │
        └────────┬──────────────────────────────────────────────────┘
                 │
    ┌────────────▼────────────────────────────────────────────────┐
    │                 Java Application Server                     │
    │                  (Spring Boot 2.7+)                         │
    ├─────────────────────────────────────────────────────────────┤
    │                                                             │
    │  ┌──────────────────────────────────────────────────────┐  │
    │  │          Telegram Bot (telegrambots 6.9.7)          │  │
    │  │  ├─ ExportBot (long polling)                        │  │
    │  │  │  ├─ /start — instructions                        │  │
    │  │  │  ├─ /export <chat_id> — submit task             │  │
    │  │  │  └─ /help — help message                         │  │
    │  │  └─ ExportJobProducer                               │  │
    │  │     └─ RPUSH task_id JSON to redis queue            │  │
    │  └──────────────────────────────────────────────────────┘  │
    │                     │                                        │
    │                     ▼ RPUSH telegram_export                 │
    │                     │                                        │
    │                     ▼                                        │
    │  ┌──────────────────────────────────────────────────────┐  │
    │  │     FileController (Existing)                        │  │
    │  │     FileStorageService (Existing)                    │  │
    │  │     ProcessingStatusService (Existing)               │  │
    │  │     MessageProcessor (Existing)                      │  │
    │  │     TelegramExporter (Existing)                      │  │
    │  │     MarkdownParser (Existing)                        │  │
    │  └──────────────────────────────────────────────────────┘  │
    │                                                             │
    └─────────────────────────────────────────────────────────────┘
                     │                                    │
         ┌───────────▼────────────┐          ┌──────────▼───────────┐
         │   Local File Storage   │          │   Redis 7+           │
         │  ├─ Import/ (temp)     │          │  ├─ Streams:         │
         │  ├─ Export/ (results)  │          │  │   export-tasks     │
         │  └─ TTL cleanup        │          │  │   export-results   │
         └────────────────────────┘          │  ├─ Hashes:           │
                                             │  │   user-sessions    │
                                             │  └─ TTL: 24h         │
                                             └──────────────────────┘
                                                        │
                          ┌─────────────────────────────▼────────────────┐
                          │       Python Export Worker                   │
                          │        (asyncio + Pyrogram)                  │
                          ├────────────────────────────────────────────┤
                          │                                            │
                          │  ┌──────────────────────────────────────┐ │
                          │  │   Queue Consumer                     │ │
                          │  │  • Listen to export-tasks           │ │
                          │  │  • Parse task parameters             │ │
                          │  └──────────────────────────────────────┘ │
                          │                  │                         │
                          │                  ▼                         │
                          │  ┌──────────────────────────────────────┐ │
                          │  │   Pyrogram Client                    │ │
                          │  │  • Authenticate (phone account)      │ │
                          │  │  • Session management                │ │
                          │  │  • Get chat history                  │ │
                          │  │  • Handle rate limiting              │ │
                          │  └──────────────────────────────────────┘ │
                          │                  │                         │
                          │                  ▼                         │
                          │  ┌──────────────────────────────────────┐ │
                          │  │   JSON Converter                     │ │
                          │  │  • Transform to result.json format   │ │
                          │  │  • Handle Markdown entities          │ │
                          │  │  • Validate structure                │ │
                          │  └──────────────────────────────────────┘ │
                          │                  │                         │
                          │                  ▼                         │
                          │  ┌──────────────────────────────────────┐ │
                          │  │   HTTP Client                        │ │
                          │  │  • POST to /api/files/upload        │ │
                          │  │  • Handle responses                 │ │
                          │  │  • Retry logic                      │ │
                          │  └──────────────────────────────────────┘ │
                          │                  │                         │
                          │                  ▼                         │
                          │  ┌──────────────────────────────────────┐ │
                          │  │   Result Handler                     │ │
                          │  │  • Save to Redis (export-results)    │ │
                          │  │  • Error handling                    │ │
                          │  │  • Logging                           │ │
                          │  └──────────────────────────────────────┘ │
                          │                                            │
                          └────────────────────────────────────────────┘
```

---

## 📦 Компоненты

### 1. Java Layer - Telegram Bot

#### ExportBot (com.tcleaner.bot.ExportBot)
- **责任:** Spring Boot компонент для Telegram Bot (telegrambots-spring-boot-starter 6.9.7)
- **Функции:**
  - Long polling для получения обновлений от Telegram
  - Обработка команд `/start`, `/help`, `/export <chat_id>`
  - Отправка сообщений пользователю
- **Аннотации:** `@Component`, `@ConditionalOnExpression("'${telegram.bot.token:}' != ''")`
- **Dependencies:** TelegramLongPollingBot, Spring Boot, ExportJobProducer

#### ExportJobProducer (com.tcleaner.bot.ExportJobProducer)
- **責任:** Добавление задач в Redis очередь
- **Функции:**
  - Создание JSON-задачи с уникальным task_id
  - RPUSH в Redis queue `telegram_export`
  - Логирование добавленных задач
- **Формат JSON:**
  ```json
  {
    "task_id": "export_abc123...",
    "user_id": 12345,
    "user_chat_id": 12345,
    "chat_id": -100123456789,
    "limit": 0,
    "offset_id": 0
  }
  ```

---

### 2. Python Layer - Export Worker

#### Queue Consumer
- **責任:** Слушание и обработка входящих задач
- **Функции:**
  - Подключение к Redis
  - XREAD из export-tasks
  - Преобразование данных в объекты Python
- **Tech:** asyncio, aioredis

#### Pyrogram Client
- **責任:** Интеграция с Telegram Client API
- **Функции:**
  - Аутентификация (phone account + SMS code)
  - Управление сессией
  - get_chat_history() с параметрами
  - Обработка rate limiting и ошибок
- **Tech:** Pyrogram async

#### JSON Converter
- **責任:** Трансформация истории в JSON
- **Функции:**
  - Парсинг Message объектов от Pyrogram
  - Создание структуры result.json (совместимо с Telegram Desktop)
  - Сохранение Markdown entities
  - Валидация output

#### HTTP Client
- **責任:** Отправка JSON на Java сервис
- **Функции:**
  - POST multipart/form-data на /api/files/upload
  - Обработка response (получить file_id)
  - Retry logic с exponential backoff
- **Tech:** aiohttp или requests

#### Result Handler
- **責任:** Сохранение результатов
- **Функции:**
  - XADD в export-results
  - Логирование success/error
  - Удаление задачи из export-tasks

---

### 3. Data Layer - Redis

#### List Queue (Jobs)
```
telegram_export (Redis List)
├─ Structure (JSON per element):
│  ├─ task_id:      "export_abc123..."
│  ├─ user_id:      12345
│  ├─ user_chat_id: 12345 (where to send result)
│  ├─ chat_id:      -100123456789 (which chat to export)
│  ├─ limit:        0
│  └─ offset_id:    0
├─ Operations:
│  ├─ RPUSH — add job (by ExportJobProducer)
│  ├─ BLPOP — get job (by Python Worker)
│  └─ No TTL (jobs deleted after processing)
```

#### Other Keys
```
status:{fileId}
├─ Value: PENDING | PROCESSING | COMPLETED | FAILED
├─ Used by: ProcessingStatusService
└─ TTL: app.storage.export-ttl-minutes (10 min default)
```

**Note:** The old Stream-based architecture (`export-tasks`, `export-results`) is not implemented yet. Current implementation uses simple List queue.

---

### 4. Existing Layer - Unchanged Components

#### FileController
- **Используется:** Для приема JSON от Python Worker через `/api/files/upload`
- **Изменения:** Нет (полностью совместимо)

#### FileStorageService
- **Используется:** Для сохранения и управления файлами
- **Используемые методы:**
  - uploadFile(MultipartFile)
  - processFileAsync(fileId)
  - getFile(fileId)
- **Изменения:** Нет

#### ProcessingStatusService
- **Используется:** Для хранения статуса обработки (Redis)
- **Используемые методы:**
  - getStatus(fileId)
  - setStatus(fileId, status)
- **Изменения:** Нет

#### MessageProcessor, TelegramExporter, MarkdownParser
- **Используются:** Для очистки и обработки JSON
- **Изменения:** Нет

---

## 🔄 Data Flow

### 1. User requests export via Telegram Bot

```
User: /export -100123456789
  ↓
ExportBot.onUpdateReceived(Update)
  ↓
ExportBot.handleExport(chatId, userId, text)
  ↓
Validate chat_id format (must be number)
  ↓
ExportJobProducer.enqueue(userId, userChatId, targetChatId)
  ↓
Create JSON with task_id, user_id, user_chat_id, chat_id
  ↓
RPUSH telegram_export <JSON>
  ↓
Bot: "Task accepted! ID: export_abc123..."
```

### 2. Python Worker processes task

```
python main.py (listening to Redis)
  ↓
BLPOP telegram_export (blocking read)
  ↓
Task received: {user_id, user_chat_id, chat_id, task_id}
  ↓
Pyrogram client: authenticate → get_chat_history(chat_id)
  ↓
Convert messages to result.json format
  ↓
POST /api/files/upload (multipart/form-data)
  ↓
Java returns: {fileId: 'uuid'}
  ↓
Save result to Redis (task_id → fileId mapping) [optional]
```

### 3. Java receives and processes export

```
FileController.uploadFile(MultipartFile)
  ↓
FileStorageService.uploadFile() → Import/{uuid}.json
  ↓
FileStorageService.processFileAsync(fileId)
  ↓
TelegramExporter.processFile() → MessageProcessor
  ↓
File saved to Export/{uuid}.md
  ↓
ProcessingStatusService.setStatus(fileId, COMPLETED)
```

### 4. Java Bot sends result to user [Future]

```
Background polling thread (future enhancement)
  ↓
Check export result status
  ↓
FileStorageService.getFile(fileId)
  ↓
Download file → cleaned_export.txt
  ↓
ExportBot.sendDocument(user_chat_id, file)
  ↓
User receives: "✅ Export complete! [sends file]"
```

**Note:** Step 4 is a future enhancement. Currently, users must poll the Java API status manually.

---

## 🔐 Security Considerations

### 1. Authentication
- **Bot Token:** Хранить в .env (не в коде)
- **API_ID, API_HASH:** Хранить в .env
- **Phone Number:** Запрашивать при первом запуске Worker'а

### 2. Data Protection
- **Session.dat:** Хранить в .gitignore (содержит credentials)
- **User IDs:** Валидировать перед операциями
- **Chat IDs:** Проверять что чат публичный

### 3. Rate Limiting
- **Telegram API:** Pyrogram имеет встроенный exponential backoff
- **Redis Queue:** Max 1000 одновременных (остальные ждут)
- **Bot Commands:** Rate limit на пользователя (max 1 запрос в 5 секунд)

### 4. File Handling
- **Uploaded files:** Удаляются через TTL (не вечно)
- **Temp files:** Очищаются при ошибке
- **Sensitive data:** JSON с сообщениями обрабатывается только локально

---

## 🚀 Deployment Architecture

### Local Development
```
Docker Compose:
├─ redis:7
├─ java-app (port 8080)
└─ python-worker (в фоне)

Files:
├─ docker-compose.yml
├─ .env.local
└─ docs/
```

### Production
```
Kubernetes (опционально) или Docker Compose:
├─ Redis Cluster (для масштабируемости)
├─ Java App (multiple replicas)
├─ Python Workers (multiple instances)
├─ Nginx (reverse proxy)
└─ Monitoring (Prometheus, Grafana)
```

---

## 📊 Monitoring & Logging

### Redis Metrics
```
- XLEN export-tasks
- XLEN export-results
- DBSIZE
- INFO memory
- INFO stats
```

### Java Metrics
```
- Active users (Redis)
- Export success rate
- Average processing time
- Error rate
```

### Python Metrics
```
- Tasks processed per minute
- Average export time
- Pyrogram errors
- Memory usage
```

---

**Версия:** 1.0
**Дата:** 2026-03-18
