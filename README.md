# Telegram Export Cleaner

Telegram Export Cleaner — сервис для выгрузки истории Telegram-чатов в читаемый текстовый файл.

## Что это за проект

Проект состоит из трех рабочих компонентов:

1. **Java/Spring Boot сервис**
   - Telegram-бот (интерфейс для пользователей).
   - REST API для конвертации `result.json` → `output.txt`.
2. **Python worker (Pyrogram)**
   - Вычитывает задачи из Redis.
   - Забирает сообщения из Telegram API.
   - Использует дисковый кэш сообщений (SQLite).
3. **Redis**
   - Очереди задач + статусные ключи/флаги отмены.

> Важно: Redis в текущей архитектуре **не** является основным хранилищем сообщений. Сообщения кэшируются в SQLite у worker-а.

---

## Ключевые возможности

- Экспорт из `@username` или `https://t.me/...` через бота.
- Диапазоны дат в боте:
  - весь чат,
  - последние 24 часа / 3 / 7 / 30 дней,
  - ручной диапазон (`дд.мм.гггг`).
- Отмена экспорта:
  - командой `/cancel`,
  - inline-кнопкой в сообщении задачи.
- Потоковая конвертация крупных JSON-файлов в Java (`StreamingResponseBody`).
- Фильтры в REST API:
  - даты (`startDate`, `endDate`),
  - include/exclude keywords.

---

## Быстрый старт (Docker)

```bash
git clone https://github.com/thesmithmode/Telegram-Export-Cleaner
cd Telegram-Export-Cleaner
cp .env.example .env
# заполните как минимум TELEGRAM_API_ID, TELEGRAM_API_HASH, TELEGRAM_BOT_TOKEN
docker compose up -d
curl http://localhost:8080/api/health
```

Ожидаемый ответ:

```json
{"status":"UP"}
```

---

## Минимальные переменные окружения

Обязательные для реальной работы:

- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`
- `TELEGRAM_BOT_TOKEN`

Рекомендуемые:

- `TELEGRAM_SESSION_STRING` — для production/stable авторизации worker-а.
- `REDIS_QUEUE_NAME` — если нужно нестандартное имя очереди.
- `CACHE_DB_PATH`, `CACHE_MAX_DISK_GB` — если нужно кастомизировать кэш.

Подробно: [docs/SETUP.md](docs/SETUP.md).

---

## Как пользоваться ботом

1. Откройте бота и отправьте `/start`.
2. Отправьте идентификатор чата:
   - `@username`, или
   - `https://t.me/username`.
3. Выберите диапазон дат кнопками.
4. Дождитесь `output.txt`.

Если экспорт уже запущен — бот не создаст дубль, а попросит дождаться завершения или сделать `/cancel`.

---

## REST API

### `POST /api/convert`

`multipart/form-data`:
- `file` (обязательно): JSON Telegram Export (`result.json`).
- `startDate`, `endDate` (опционально): `YYYY-MM-DD`.
- `keywords`, `excludeKeywords` (опционально): CSV строки.

Пример:

```bash
curl -X POST http://localhost:8080/api/convert \
  -F "file=@result.json" \
  -F "startDate=2024-01-01" \
  -F "endDate=2024-12-31" \
  -F "keywords=release,note" \
  -o output.txt
```

### `GET /api/health`

```bash
curl http://localhost:8080/api/health
```

---

## Документация

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — архитектура и data flow.
- [docs/API.md](docs/API.md) — контракт REST API.
- [docs/BOT.md](docs/BOT.md) — сценарии Telegram-бота.
- [docs/PYTHON_WORKER.md](docs/PYTHON_WORKER.md) — worker, кэш, recovery.
- [docs/SETUP.md](docs/SETUP.md) — установка, конфиг, troubleshooting.
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — разработка и проверки.
- [docs/DASHBOARD.md](docs/DASHBOARD.md) — веб-дашборд статистики (в разработке).
