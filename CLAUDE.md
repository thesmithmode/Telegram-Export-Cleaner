# CLAUDE.md

## Архитектура

Бот экспортирует чаты в текст: Java Bot → Redis очередь → Python Worker → /api/convert → результат пользователю.

**Стек:** Java 21, Spring Boot 3.4.4, Python 3.11, Pyrogram 2.0.106, Redis 7, Docker.

See [ARCHITECTURE.md](docs/ARCHITECTURE.md), [DEVELOPMENT.md](docs/DEVELOPMENT.md), [API.md](docs/API.md) for details.

## ⚠️ Критичные правила

1. **НИКОГДА не запускать тесты локально** — только GitHub Actions CI
2. **НИКОГДА не пересобирать контейнеры вручную** — только GitHub Actions  
3. **Коммиты:** FIX:, FEAT:, REFACTOR: на РУССКОМ (ЗАГЛАВНЫЕ префиксы)
4. **НИКОГДА не добавлять Co-Authored-By** в коммиты
5. **Merge dev→main: ALWAYS --squash** (один коммит на main)
6. **Пушить только в dev/Review**, main только явно
7. **При конфликте локального и удалённого репо — ВСЕГДА приоритет у origin** (reset к remote, не rebase локального)

## Java пакеты

- **bot/** — ExportBot (wizard UI), ExportJobProducer (SET NX, express queue), UserSession
- **api/** — TelegramController (multipart, streaming), ApiKeyFilter, SecurityConfig
- **core/** — TelegramExporter (Tree + Streaming), MessageFilter, MessageProcessor, TelegramExporterException
- **format/** — MarkdownParser (20+ entity types), DateFormatter, UrlValidator, MessageFormatter, StringUtils

## Python worker

- **main.py** — ExportWorker (3-path caching: date/id/fallback)
- **pyrogram_client.py** — async Pyrogram, canonical ID resolver
- **message_cache.py** — Redis sorted sets (by msg_id, by date)
- **queue_consumer.py** — BLPOP consumer, job lifecycle
- **java_client.py** — HTTP POST /api/convert, progress tracking

## Тестирование & качество

- Java: JUnit 5 + AssertJ, 80% JaCoCo, Embedded Redis (ТОЛЬКО в CI)
- Python: pytest + conftest, AsyncMock, moки Redis/Pyrogram (ТОЛЬКО в CI)
- All public classes: JavaDoc (Java) + docstrings (Python)
- Code style: checkstyle.xml (Java), PEP 8 (Python)
