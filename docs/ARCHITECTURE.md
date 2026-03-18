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
    │  │          TelegramBotService                          │  │
    │  │  ├─ TelegramBotController (Webhook/Polling)        │  │
    │  │  ├─ TelegramUpdateHandler (Parse messages)         │  │
    │  │  ├─ TelegramMenuBuilder (InlineKeyboard)           │  │
    │  │  ├─ UserSessionManager (State management)          │  │
    │  │  └─ TelegramClientProxy (API calls)                │  │
    │  └──────────────────────────────────────────────────────┘  │
    │                     │                                        │
    │                     ▼                                        │
    │  ┌──────────────────────────────────────────────────────┐  │
    │  │          Redis Queue Handler                         │  │
    │  │  ├─ ExportTaskQueue (publish tasks)                 │  │
    │  │  ├─ ExportResultListener (listen results)           │  │
    │  │  └─ SessionStateManager (user state)                │  │
    │  └──────────────────────────────────────────────────────┘  │
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

### 1. Java Layer - Telegram Bot Service

#### TelegramBotService
- **责任:** Основной сервис для управления Telegram Bot
- **Функции:**
  - Инициализация бота
  - Управление webhook/polling
  - Маршрутизация обновлений
- **Dependencies:** java-telegram-bot-api, Spring, Redis

#### TelegramUpdateHandler
- **责任:** Обработка входящих сообщений/нажатий кнопок
- **Функции:**
  - Парсинг типа обновления (message, callback_query)
  - Определение текущего состояния пользователя
  - Маршрутизация к нужному handler'у

#### TelegramMenuBuilder
- **责任:** Создание интерактивных меню
- **Функции:**
  - Построение InlineKeyboardMarkup (кнопки)
  - Кеширование списков чатов
  - Форматирование сообщений с markdown

#### UserSessionManager
- **责任:** Управление состоянием пользователя
- **Функции:**
  - Хранение состояния (выбран ли чат, период, и т.д.)
  - Валидация переходов между состояниями
  - Очистка старых сессий
- **Storage:** Redis Hashes

#### ExportTaskQueue
- **责任:** Взаимодействие с Redis очередью
- **Функции:**
  - Добавление задач в export-tasks
  - Чтение результатов из export-results
  - Управление TTL задач

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

#### Streams (Queues)
```
export-tasks
├─ Структура:
│  ├─ user_id: "123456789"
│  ├─ chat_id: "-1001234567890"
│  ├─ date_from: "2024-01-01"
│  ├─ date_to: "2024-12-31"
│  └─ filter_keywords: "keyword1,keyword2" (опционально)
└─ TTL: 24 часа

export-results
├─ Структура:
│  ├─ task_id: "123-abc"
│  ├─ file_id: "uuid-of-file"
│  ├─ status: "COMPLETED" | "FAILED"
│  └─ error: "Error message if failed"
└─ TTL: 1 час (для результатов)
```

#### Hashes (User Sessions)
```
user-sessions:{user_id}
├─ current_state: "SELECT_CHAT" | "SELECT_DATE" | "PROCESSING"
├─ selected_chat_id: "-1001234567890"
├─ selected_chat_title: "My Channel"
├─ date_from: "2024-01-01"
├─ date_to: "2024-12-31"
└─ created_at: "timestamp"
```

#### Keys
```
task-result:{task_id}
├─ Хранит file_id для быстрого доступа
└─ TTL: 1 час
```

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

### 1. User initiates export

```
User: "/start" или "Начать экспорт"
  ↓
TelegramUpdateHandler.onMessage()
  ↓
UserSessionManager.createSession(user_id)
  ↓
TelegramMenuBuilder.buildChatSelectionMenu()
  ↓
sendMessage с InlineKeyboard (список чатов)
```

### 2. User selects chat

```
User: нажимает на чат из списка
  ↓
TelegramUpdateHandler.onCallbackQuery()
  ↓
UserSessionManager.setState(CHAT_SELECTED, chat_id)
  ↓
TelegramMenuBuilder.buildDateRangeMenu()
  ↓
sendMessage с кнопками выбора даты (или по умолчанию)
```

### 3. User confirms parameters

```
User: выбирает дату или нажимает "Продолжить"
  ↓
UserSessionManager.setState(PROCESSING)
  ↓
ExportTaskQueue.addTask(user_id, chat_id, params)
  ↓
task_id = redis.xadd('export-tasks', task_data)
  ↓
sendMessage "⏳ Обрабатываю... Номер в очереди: 42"
```

### 4. Python Worker processes task

```
python main.py (listening to Redis)
  ↓
redis.xread('export-tasks')
  ↓
Task received: {user_id, chat_id, date_from, date_to}
  ↓
pyrogram.get_chat_history(chat_id, offset_date=date_from, limit=100000)
  ↓
Convert messages to result.json format
  ↓
POST /api/files/upload (multipart/form-data)
  ↓
Java returns: {fileId: 'uuid'}
  ↓
redis.xadd('export-results', {task_id, file_id, status})
  ↓
redis.xdel('export-tasks', task_id)
```

### 5. Java processes file

```
FileController.uploadFile(MultipartFile)
  ↓
FileStorageService.uploadFile() → saved to Import/{uuid}.json
  ↓
FileStorageService.processFileAsync(fileId)
  ↓
TelegramExporter.processFile() → MessageProcessor
  ↓
File saved to Export/{uuid}.md
  ↓
ProcessingStatusService.setStatus(COMPLETED)
```

### 6. Bot retrieves result and sends to user

```
ExportResultListener (background task)
  ↓
redis.xread('export-results') → получить результат
  ↓
UserSessionManager.getSession(user_id)
  ↓
FileStorageService.getFile(fileId)
  ↓
Download file → cleaned_export.md
  ↓
TelegramBotService.sendDocument(user_id, file)
  ↓
User receives file: "✅ Вот ваш экспорт!"
```

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
