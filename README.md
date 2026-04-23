# Telegram Export Cleaner

Телеграм-бот для выгрузки истории чатов в текстовый файл.

👉 **Попробовать прямо сейчас: [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot)**

---

## Что умеет

- Экспортирует историю любого чата по `@username` или `https://t.me/...` ссылке
- Выбор периода: весь чат, последние 24ч / 3 / 7 / 30 дней или произвольный диапазон дат
- Поддержка форумных топиков (`https://t.me/channel/TOPIC_ID`)
- Отмена в любой момент командой `/cancel` или кнопкой в чате
- Кэш сообщений на диске — повторный экспорт того же чата быстрее
- Фильтрация по ключевым словам через REST API
- UI бота на 10 языках (выбор при `/start`, смена через `/settings`); язык
  синхронизируется с дашбордом

---

## Как пользоваться ботом

1. Открыть [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot)
2. Отправить `/start`
3. Отправить `@username` или `https://t.me/...` ссылку на чат
4. Выбрать диапазон дат кнопками
5. Получить `output.txt`

---

## Запустить локально (Docker)

```bash
git clone https://github.com/thesmithmode/Telegram-Export-Cleaner
cd Telegram-Export-Cleaner
cp .env.example .env
# заполнить TELEGRAM_API_ID, TELEGRAM_API_HASH, TELEGRAM_BOT_TOKEN
docker compose up -d
```

Проверить что всё поднялось:
```bash
curl http://localhost:8080/api/health
# {"status":"UP"}
```

---

## REST API

Конвертация экспорта Telegram Desktop (`result.json`) без бота:

```bash
curl -X POST http://localhost:8080/api/convert \
  -F "file=@result.json" \
  -F "startDate=2024-01-01" \
  -F "endDate=2024-12-31" \
  -o output.txt
```

Параметры: `startDate`, `endDate` (YYYY-MM-DD), `keywords`, `excludeKeywords` (CSV).

---

## Архитектура

```
Telegram Bot (Java/Spring Boot)
    │  ставит задачу в очередь
    ▼
Redis (очереди + статусы)
    │  воркер забирает задачу
    ▼
Python Worker (Pyrogram)
    │  тянет сообщения из Telegram API
    │  кэширует в SQLite
    ▼
Java API (POST /api/convert)
    │  форматирует и стримит текст
    ▼
Пользователь получает output.txt
```

- **Java** — бот, REST API, управление сессиями
- **Python** — Telegram API через Pyrogram, SQLite-кэш, очередь
- **Redis** — очереди задач, статусы, дедупликация

Подробнее: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

---

## Переменные окружения

Обязательные:

| Переменная | Описание |
|---|---|
| `TELEGRAM_API_ID` | Из [my.telegram.org/apps](https://my.telegram.org/apps) |
| `TELEGRAM_API_HASH` | Из [my.telegram.org/apps](https://my.telegram.org/apps) |
| `TELEGRAM_BOT_TOKEN` | От [@BotFather](https://t.me/BotFather) |
| `JAVA_API_KEY` | Секрет между java-bot и worker (`openssl rand -hex 32`) |

Рекомендуемые:

| Переменная | Описание |
|---|---|
| `TELEGRAM_SESSION_STRING` | Pyrogram session для стабильной авторизации |
| `CACHE_MAX_DISK_GB` | Лимит SQLite-кэша (по умолчанию 25 GB) |

Полный список: [docs/SETUP.md](docs/SETUP.md)

---

## Документация

- [docs/SETUP.md](docs/SETUP.md) — установка и настройка
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — архитектура и data flow
- [docs/API.md](docs/API.md) — REST API
- [docs/BOT.md](docs/BOT.md) — сценарии бота
- [docs/PYTHON_WORKER.md](docs/PYTHON_WORKER.md) — воркер и кэш
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — разработка
