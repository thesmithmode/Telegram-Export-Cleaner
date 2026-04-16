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
- Обработка `/start`, `/cancel` команд
- Обработка текстовых сообщений (chat ID, username, link)
- Обработка callback запросов (выбор дат, диапазонов)
- Управление wizard UI (inline кнопки)
- Отслеживание session state для каждого пользователя

**Session States (`UserSession.State`):**
```
IDLE
  ↓ (user sends chat ID / username / t.me link)
AWAITING_DATE_CHOICE
  ↓ (user picks: весь чат / 24ч / 3д / 7д / 30д / указать даты)
AWAITING_FROM_DATE            IDLE (quick-range / "весь чат" → сразу в очередь)
  ↓ (user types дд.мм.гггг or [⏮ С начала])
AWAITING_TO_DATE
  ↓ (user types дд.мм.гггг or [⏭ До сегодня])
IDLE (job enqueued, Python worker отвечает напрямую через /api/convert)
```

Состояния `PROCESSING` нет: Java-бот не ждёт ответа активно — Python-воркер
сам вызывает `POST /api/convert` и шлёт файл пользователю через Telegram Bot API.

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

3. **Quick-Range Shortcuts** — кнопки быстрого экспорта без ввода дат.
   По клику `fromDate` выставляется в `(today − (N−1) days)`, `toDate = null`
   (до сегодня включительно), задача сразу уходит в очередь.
   ```
   CB_LAST_24H  →  last 1 day
   CB_LAST_3D   →  last 3 days
   CB_LAST_7D   →  last 7 days
   CB_LAST_30D  →  last 30 days
   ```

4. **Cancel Support** — возможность отмены во время обработки
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

void setMyCommands(List<BotCommand> commands)
    // Зарегистрировать slash-команды Telegram для подсказок в UI клиента
```

`ExportBot` вызывает `setMyCommands(...)` при старте приложения, поэтому у пользователя
в Telegram сразу появляются подсказки `/start`, `/cancel`.

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

**Путь A — быстрый экспорт (клик «🗓 7 дней»)**

```
User: /start
  ↓
Bot: "Введите chat ID, username или ссылку на чат"
UserSession: IDLE
  ↓
User: @channel или https://t.me/channel или -1001234567890
  ↓
Bot: "📋 Чат: @channel — выберите диапазон:"
     [📦 Весь чат]
     [⏱ 24 часа] [🗓 3 дня] [🗓 7 дней] [🗓 30 дней]
     [📅 Указать даты] [❌ Отмена]
UserSession: IDLE → AWAITING_DATE_CHOICE
  ↓
User: clicks [🗓 7 дней]  ← startQuickRangeExport(days=7)
  ↓
ExportJobProducer.enqueue()
  - SET NX active_export:{userId}  → дубликат? → отказ
  - fromDate = today − 6 days, toDate = null (до сегодня)
  - LPUSH to telegram_export_express (likely cached)
UserSession: AWAITING_DATE_CHOICE → IDLE
  ↓
[Python Worker: SQLite date-hit? → fast path. Miss → Pyrogram]
  ↓
Worker → POST /api/convert → Java → sendFile(user_chat_id, output.txt)
```

**Путь B — ручной ввод дат**

```
[...после AWAITING_DATE_CHOICE...]
  ↓
User: clicks [📅 Указать даты]
UserSession: AWAITING_DATE_CHOICE → AWAITING_FROM_DATE
  ↓
Bot: "Введите начальную дату дд.мм.гггг" + [⏮ С начала чата] [↩ Назад]
  ↓
User: 01.01.2024  (или [⏮ С начала])
UserSession: AWAITING_FROM_DATE → AWAITING_TO_DATE
  ↓
Bot: "Введите конечную дату дд.мм.гггг" + [⏭ До сегодня] [↩ Назад]
  ↓
User: 31.12.2024  (или [⏭ До сегодня])
  ↓
ExportJobProducer.enqueue() → ...то же что в пути A...
UserSession: AWAITING_TO_DATE → IDLE
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
