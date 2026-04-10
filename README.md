# Telegram Export Cleaner

Telegram-бот для экспорта истории чатов в чистый текстовый файл.

**🤖 Попробуй:** [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot) (доступ по запросу)

## Возможности

- ✅ Экспорт истории публичных каналов, групп и приватных чатов
- 📅 Фильтрация по диапазону дат
- 🔍 Фильтрация по ключевым словам (включение/исключение)
- 🎨 Форматирование Telegram-разметки: **bold**, *italic*, `code`, [ссылки](url)
- ⚡ Кэширование результатов для быстрого повторного экспорта
- ❌ Отмена экспорта во время выполнения

## Быстрый старт

1. **Используй бота в Telegram:**
   - Отправь ссылку (`https://t.me/username`) или username (`@username`)
   - Выбери диапазон дат в интерактивном меню
   - Получи файл `output.txt` в личных сообщениях

2. **Развёрнуть локально:**
   ```bash
   git clone https://github.com/thesmithmode/Telegram-Export-Cleaner
   cp .env.example .env
   # Заполни TELEGRAM_API_ID, TELEGRAM_API_HASH, TELEGRAM_BOT_TOKEN
   docker compose up -d
   curl http://localhost:8080/api/health
   ```

3. **REST API для конвертации файлов:**
   ```bash
   curl -X POST http://localhost:8080/api/convert \
     -H "X-API-Key: your-key" \
     -F "file=@result.json" \
     -F "startDate=2024-01-01"
   ```

## Стек

**Java 21** · **Spring Boot 3.4.4** · **Python 3.11** · **Pyrogram 2.0** · **Redis 7** · **Docker**

## Документация

- 📖 [**DEVELOPMENT.md**](docs/DEVELOPMENT.md) — как контрибьютить, git workflow, тестирование
- 🏛️ [**ARCHITECTURE.md**](docs/ARCHITECTURE.md) — архитектура системы, компоненты, data flow
- 📡 [**API.md**](docs/API.md) — REST API endpoints, примеры, error codes
- 🤖 [**BOT.md**](docs/BOT.md) — Java bot, wizard UI, session management
- 🐍 [**PYTHON_WORKER.md**](docs/PYTHON_WORKER.md) — Python worker, Pyrogram, кэширование
- 🔧 [**SETUP.md**](docs/SETUP.md) — установка, конфигурация, troubleshooting

---
