# Java Bot Guide

## Overview

Java Bot — основной компонент для взаимодействия с пользователями Telegram. Управляет:
- Приём команд и сообщений от пользователя (long-polling)
- Интерактивный wizard UI для выбора параметров экспорта
- Управление Redis очередями (основная + express для кэшированных)
- Отслеживание состояния сессий пользователей
- Отправка результатов файлом

## Architecture

```
Telegram Bot API
      ↓
Java Bot (Long-polling)
      ↓
ExportBot (обработка команд и логика)
      ↓
┌─────────────────────────────────────┐
│ - ExportJobProducer (очередь)       │
│ - BotMessenger (отправка сообщений) │
│ - UserSession (состояние диалога)   │
└─────────────────────────────────────┘
      ↓
Redis (очереди, locks, cache)
```

## Components

### ExportBot

Основной класс для обработки обновлений от Telegram Bot API.

**Ответственность:**
- Обработка `/start`, `/help`, `/cancel` команд
- Обработка текстовых сообщений (chat ID, username, link)
- Обработка callback запросов (выбор дат, диапазонов)
- Управление wizard UI (inline кнопки)
- Отслеживание session state для каждого пользователя

**Session States:**
```
IDLE
  ↓ (user starts /start)
WAITING_CHAT_ID
  ↓ (user sends chat ID/username)
WAITING_DATE_MODE
  ↓ (user chooses: all, range, specific)
WAITING_START_DATE, WAITING_END_DATE
  ↓ (user enters dates)
PROCESSING
  ↓ (job in queue, waiting for result)
IDLE (after result sent)
```

### ExportJobProducer

Управление Redis очередью и защита от параллельных экспортов.

**Ключевые функции:**

1. **SET NX Protection** — блокировка от дубликатов
   ```java
   active_export:{userId}  // SET NX, TTL=1 час
   ```
   Если пользователь нажмёт экспорт дважды, второй будет отклонён.

2. **Express Queue** — приоритет для кэшированных данных
   ```
   telegram_export           // основная очередь (новые чаты)
   telegram_export_express   // приоритетная (кэшированные)
   ```

3. **Cancel Support** — возможность отмены во время обработки
   ```java
   cancel_export:{taskId}  // SETEX на 30 минут
   ```

**Методы:**
```java
void enqueue(long userId, ExportRequest request)
    // Добавить в очередь (основная или express)
    // SET NX блокировка, проверка дубликатов

void cancel(long userId)
    // Установить флаг отмены в Redis
    // Удалить из очереди если ещё не начата
```

### BotMessenger

Отправка сообщений пользователю через Telegram API.

**Методы:**
```java
void send(long chatId, String text)
    // Простое сообщение

void sendWithKeyboard(long chatId, String text, InlineKeyboardMarkup keyboard)
    // Сообщение с inline-кнопками (выбор дат, диапазонов)

int sendWithKeyboardGetId(long chatId, String text, InlineKeyboardMarkup keyboard)
    // Отправить и вернуть ID сообщения для редактирования

void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard)
    // Отредактировать существующее сообщение (обновить статус)

void answerCallback(String callbackQueryId)
    // Ответить на callback запрос (убрать "loading" индикатор)

void sendRemoveReplyKeyboard(long chatId, String text)
    // Отправить и убрать клавиатуру
```

### UserSession

Потокобезопасное управление состоянием диалога для каждого пользователя.

**Хранит:**
```java
userId: long                        // Telegram user ID
state: State enum                   // Текущее состояние (IDLE, WAITING_CHAT, etc)
selectedChatId: long               // Выбранный chat ID
selectedStartDate: LocalDate        // Выбранная начальная дата
selectedEndDate: LocalDate          // Выбранная конечная дата
selectedKeywords: List<String>      // Ключевые слова для фильтрации
excludedKeywords: List<String>      // Исключаемые ключевые слова
lastMessageId: int                  // ID последнего сообщения (для редактирования)
```

**Thread Safety:**
```java
ConcurrentHashMap<Long, UserSession> sessions;  // thread-safe
synchronize при изменении state и параметров
```

## Wizard Flow

**Пример: Пользователь экспортирует @durov**

```
User: /start
  ↓
Bot: "Введите chat ID, username или ссылку"
UserSession: IDLE → WAITING_CHAT_ID
  ↓
User: @durov
  ↓
Bot: ResolveCanonicalId (Python) → 123456 (durov's ID)
  ↓
Bot: "Выбери опцию:"
     [📦 Весь чат] [📅 Диапазон] [❌ Отмена]
UserSession: WAITING_CHAT_ID → WAITING_DATE_MODE
  ↓
User: clicks [📅 Диапазон]
  ↓
Bot: "Начальная дата (дд.мм.гггг) или [⏮ С начала]"
UserSession: WAITING_DATE_MODE → WAITING_START_DATE
  ↓
User: 01.01.2024
  ↓
Bot: "Конечная дата (дд.мм.гггг) или [⏭ До сегодня]"
UserSession: WAITING_START_DATE → WAITING_END_DATE
  ↓
User: 31.12.2024
  ↓
ExportJobProducer.enqueue()
  - Check SET NX active_export:{userId}
  - Create JSON task: {chat_id: 123456, start: 2024-01-01, end: 2024-12-31}
  - LPUSH to telegram_export_express (likely cached)
  ↓
Bot: "⏳ Экспорт в очереди..."
UserSession: WAITING_END_DATE → PROCESSING
  ↓
[Python Worker processes...]
  ↓
JavaBotClient calls BotMessenger.sendFile()
  ↓
Bot: [Sends output.txt file]
UserSession: PROCESSING → IDLE
```

## Redis Keys

| Key | Type | TTL | Purpose |
|-----|------|-----|---------|
| `telegram_export` | List | ∞ | Main job queue (LPUSH/BLPOP) |
| `telegram_export_express` | List | ∞ | Priority queue for cached jobs |
| `active_export:{userId}` | String | 1h | SET NX lock (prevent duplicates) |
| `cancel_export:{taskId}` | String | 30m | Cancellation flag for running job |

## Configuration

**application.properties:**
```properties
# Telegram Bot Token
telegram.bot.token=${TELEGRAM_BOT_TOKEN}

# Redis
spring.redis.host=redis
spring.redis.port=6379
spring.redis.db=0

# Multipart uploads
spring.servlet.multipart.max-file-size=2GB
spring.servlet.multipart.max-request-size=2GB

# API Key
api.key=${JAVA_API_KEY}
```

## Testing

Tests located in `src/test/java/com/tcleaner/bot/`:
- `ExportBotTest.java` — command processing, state transitions
- `ExportJobProducerTest.java` — queue management, locks
- `BotMessengerTest.java` — message sending, keyboards, editing
- `UserSessionTest.java` — session state management

All tests use mocks for Telegram API and Redis.

## Troubleshooting

**Bot not responding**
```bash
docker logs st_java | grep -i "error\|exception"
```

**Stuck in PROCESSING state**
```bash
redis-cli
> GET active_export:{userId}  # if exists, job is locked
> DEL active_export:{userId}  # manually unlock if needed
```

**Cannot export chat**
- Check if worker is running
- Check if chat ID is correct (numeric or valid username)
- Check Redis connection

---

See also:
- [ARCHITECTURE.md](ARCHITECTURE.md) — system design
- [PYTHON_WORKER.md](PYTHON_WORKER.md) — worker implementation
- [DEVELOPMENT.md](DEVELOPMENT.md) — contributing guidelines
