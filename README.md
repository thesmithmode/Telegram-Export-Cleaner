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

Откройте бот [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot) и нажмите **"📂 Выбрать чат из Telegram"** — откроется нативный пикер.

Или введите вручную:

```
/export https://t.me/durov
/export @durov
/export -1001234567890
```

Бот предложит выбрать диапазон дат, затем поставит задачу в очередь. По завершении пришлёт файл `output.txt`.

**Пример вывода:**
```
20240115_14:30 **Заголовок** обычный текст
20240115_14:31 сообщение со ссылкой [нажми](https://example.com)
20240115_14:32 *курсив* и `код`
```

---

## Архитектура

```
Пользователь → Java Bot (long polling) → Redis очередь → Python Worker (Pyrogram)
                                                               ↓
                                               Java API /api/convert (форматирование)
                                                               ↓
                                               Telegram Bot API → output.txt пользователю
```

**Стек:** Java 21 · Spring Boot 3.3 · Python 3.11 · Pyrogram 2.0 · Redis · Docker

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
TELEGRAM_BOT_USERNAME=MyExportBot
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

Используется Python Worker для форматирования, но доступен напрямую.

### `POST /api/convert`

Принимает JSON-файл с массивом сообщений, возвращает `output.txt`.

**Параметры формы:**

| Параметр | Обязателен | Описание |
|---|---|---|
| `file` | ✅ | JSON-файл с сообщениями |
| `startDate` | ❌ | Фильтр от даты (YYYY-MM-DD) |
| `endDate` | ❌ | Фильтр до даты (YYYY-MM-DD) |
| `keywords` | ❌ | Включить сообщения с любым из слов (через запятую) |
| `excludeKeywords` | ❌ | Исключить сообщения с любым из слов (через запятую) |

```bash
curl -X POST http://localhost:8080/api/convert -F "file=@result.json" -o output.txt

curl -X POST "http://localhost:8080/api/convert?startDate=2024-01-01&endDate=2024-12-31" \
  -F "file=@result.json" -o output.txt
```

### `GET /api/health`

```json
{"status": "UP"}
```

---

## Разработка

```bash
# Сборка Java
mvn clean package

# Проверка стиля
mvn checkstyle:check

# Python (локально)
cd export-worker
python3.11 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
```

Тесты запускаются только в CI (GitHub Actions).
Покрытие: JaCoCo ≥ 80% для Java, pytest для Python.
