# Telegram Export Cleaner

Сервис для экспорта истории Telegram-чатов в текст:
- **Java/Spring Boot**: Telegram-бот + REST API конвертации.
- **Python worker**: получает задачи из Redis, выгружает сообщения через Pyrogram, отправляет результат в Java API.

## Что умеет

- Экспорт по `@username` или `https://t.me/...` через Telegram-бота.
- Выбор диапазона: весь чат, последние 24ч/3д/7д/30д, или ручной диапазон дат.
- Отмена активного экспорта (`/cancel` или кнопка в сообщении задачи).
- REST-конвертация `result.json` в `output.txt` с фильтрами дат и ключевых слов.
- Потоковая обработка больших JSON в Java (`StreamingResponseBody`).
- Двухуровневый кэш:
  - Redis — очередь/состояние задач.
  - SQLite — кэш сообщений worker-а на диске.

## Быстрый старт (Docker)

```bash
git clone https://github.com/thesmithmode/Telegram-Export-Cleaner
cd Telegram-Export-Cleaner
cp .env.example .env
# заполните TELEGRAM_API_ID, TELEGRAM_API_HASH, TELEGRAM_BOT_TOKEN
# (TELEGRAM_SESSION_STRING желательно для production)
docker compose up -d
curl http://localhost:8080/api/health
```

Ожидаемый ответ:

```json
{"status":"UP"}
```

## Как пользоваться ботом

1. Откройте бота и отправьте `/start`.
2. Отправьте `@username` или ссылку `https://t.me/<chat>`.
3. Выберите диапазон дат кнопками.
4. Дождитесь `output.txt`.

> В приватных чатах/каналах аккаунт worker-а (не бот) должен иметь доступ.

## REST API

### `POST /api/convert`
`multipart/form-data`:
- `file` (обязательно) — Telegram export JSON.
- `startDate`, `endDate` (опционально, `YYYY-MM-DD`).
- `keywords`, `excludeKeywords` (опционально, CSV).

Пример:
```bash
curl -X POST http://localhost:8080/api/convert \
  -F "file=@result.json" \
  -F "startDate=2024-01-01" \
  -F "endDate=2024-12-31" \
  -o output.txt
```

### `GET /api/health`
Проверка доступности Java-сервиса:
```bash
curl http://localhost:8080/api/health
```

## Документация

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — компоненты и поток данных.
- [docs/API.md](docs/API.md) — контракт REST API.
- [docs/BOT.md](docs/BOT.md) — сценарии Telegram-бота.
- [docs/PYTHON_WORKER.md](docs/PYTHON_WORKER.md) — работа воркера и кэша.
- [docs/SETUP.md](docs/SETUP.md) — установка и конфигурация.
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — разработка и проверки.
