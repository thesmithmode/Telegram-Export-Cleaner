# Telegram Export Cleaner

Telegram-бот для экспорта истории чатов в чистый текстовый файл.

**Try it**: [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot) (доступ по запросу)

---

## Что умеет

- Экспортирует историю публичных каналов и групп (и приватных, если аккаунт worker-а в них состоит)
- Фильтрует по диапазону дат
- Форматирует Telegram-разметку: **bold**, *italic*, `code`, [ссылки](url)
- Кэширует результаты — повторные запросы того же чата отрабатывают быстро
- Поддерживает отмену экспорта во время выполнения

---

## Использование

Откройте бот [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot). Есть три способа:

**1. Перешлите сообщение из нужного чата/канала**
   - Просто пробросьте любое сообщение боту — бот определит источник автоматически

**2. Введите ссылку:**
```
https://t.me/durov
https://t.me/c/123456789/1
```

**3. Введите username или ID:**
```
@durov
durov
-1001234567890
```

Бот предложит выбрать диапазон дат, затем поставит задачу в очередь. По завершении пришлёт файл `output.txt`.

**Пример вывода:**
```
20240115_14:30 **Заголовок** обычный текст
20240115_14:31 сообщение со ссылкой [нажми](https://example.com)
20240115_14:32 *курсив* и `код`
```

---

**Стек:** Java 21 · Spring Boot 3.3 · Telegram Bots SDK 9.5.0 · Python 3.11 · Pyrogram 2.0 · Redis · Docker

**Ключевые особенности:**
- Forward-based идентификация (пробросьте сообщение) + поддержка ссылок и username
- Redis-очередь с кэшированием по ID и датам (ускоренный повторный экспорт)
- Async Python worker с резолвингом canonical ID и поддержкой отмены экспорта
- Streaming JSON парсинг (эффективно для файлов любого размера)

---

## Архитектура

```
Пользователь → Java Bot (SDK 9.5.0) → Redis очередь → Python Worker (Pyrogram)
                                                               ↓
                                               Java API /api/convert (форматирование)
                                                               ↓
                                               Telegram Bot API → output.txt пользователю
```

---

## Установка

### Требования

- Docker & Docker Compose
- Telegram API credentials ([my.telegram.org/apps](https://my.telegram.org/apps))
- Bot token ([@BotFather](https://t.me/botfather))

### 1. Настроить окружение

```bash
cp .env.example .env
# Заполнить .env своими credentials
```

```env
TELEGRAM_API_ID=123456789
TELEGRAM_API_HASH=abcdef0123456789abcdef0123456789
TELEGRAM_BOT_TOKEN=123456:ABCDefGhIjKlMnOpQrStUvWxYz-_=
TELEGRAM_SESSION_STRING=BQF...   # см. шаг 2
```

### 2. Сгенерировать Pyrogram-сессию (один раз)

```bash
cd export-worker
python get_session.py
# Следовать инструкциям, сохранить строку как TELEGRAM_SESSION_STRING
```

### 3. Запустить

```bash
docker compose up -d
```

Проверка: `curl http://localhost:8080/api/health`

---

## REST API

Предоставляет endpoint для конвертации файлов в формате Telegram JSON Export. Все запросы к `/api/**` (кроме `/api/health`) требуют авторизации через заголовок `X-API-Key`.

### POST /api/convert

Принимает файл и возвращает форматированный текст.

**Заголовки:**
- `X-API-Key`: Ключ из переменной `JAVA_API_KEY`
- `Content-Type`: `multipart/form-data`

**Параметры (Multipart):**
- `file` (обязательный): Файл `result.json` из экспорта Telegram Desktop.
- `startDate` (опционально): Начальная дата в формате `YYYY-MM-DD`.
- `endDate` (опционально): Конечная дата в формате `YYYY-MM-DD`.
- `keywords` (опционально): Список ключевых слов через запятую. Сообщение будет включено, если содержит хотя бы одно из них.
- `excludeKeywords` (опционально): Список исключаемых слов через запятую. Сообщение будет пропущено, если содержит любое из них.

**Пример:**
```bash
curl -X POST http://localhost:8080/api/convert \
  -H "X-API-Key: your-super-secret-key" \
  -F "file=@result.json" \
  -F "startDate=2024-01-01"
```

### GET /api/health

Проверка состояния сервиса. Не требует API ключа.
