# CLAUDE.md

## Что это

Telegram-бот для экспорта истории чатов в текстовый файл с форматированием.

**Поток данных:**
```
Пользователь → Telegram Bot (Java) → Redis очередь → Python Worker → Telegram API (Pyrogram)
                                                          ↓
                                            Java REST API /api/convert (форматирование)
                                                          ↓
                                            Telegram Bot API → файл пользователю
```

**Стек:** Java 21, Spring Boot 3.3, Python 3.11, Pyrogram 2.0, Redis, Docker

## Структура

| Компонент | Путь | Роль |
|---|---|---|
| Java Bot | `src/main/java/com/tcleaner/bot/` | Telegram-бот (long polling), wizard UI, Redis producer |
| Java API | `src/main/java/com/tcleaner/api/` | REST `/api/convert` — JSON→текст |
| Core | `src/main/java/com/tcleaner/core/` | Фильтрация, обработка сообщений |
| Format | `src/main/java/com/tcleaner/format/` | Markdown, даты, форматирование |
| Python Worker | `export-worker/` | Pyrogram клиент, кэш, очередь, прогресс |

**Python Worker — ключевые файлы:**
- `main.py` — ExportWorker: процесс задач, 3 пути экспорта (date cache / id cache / fallback)
- `pyrogram_client.py` — обёртка Pyrogram: итерация сообщений, FloodWait retry, count
- `java_client.py` — HTTP к Java API + доставка файла + ProgressTracker
- `message_cache.py` — Redis sorted sets кэш с gap detection по ID и датам
- `queue_consumer.py` — BRPOP consumer с Dead Letter Queue

## Критичные правила

- **НИКОГДА** не запускать `mvn test`, `pytest`, `pip install` локально — только через CI
- **НИКОГДА** не пересобирать/перезапускать контейнеры вручную — только через GitHub Actions
- **Пушить ТОЛЬКО в `dev`**, в `main` — только если явно сказано
- **Порядок:** push dev → дождаться CI → merge в main
- **Коммиты:** `FIX/FEAT/CHORE/REFACTOR/TEST/DOCS: описание на русском`
- **НИКОГДА** не добавлять `Co-Authored-By` или trailer-строки

## Сборка и тесты

```bash
mvn clean package          # сборка
mvn test                   # тесты (ТОЛЬКО в CI!)
mvn checkstyle:check       # стиль кода
```

Покрытие: минимум 80% (JaCoCo). Стиль: `checkstyle.xml`.
Тесты: JUnit 5 + AssertJ + Testcontainers. Python: pytest + моки в `conftest.py`.

## Конфигурация

**Java:** `application.properties` — bot token, Redis, multipart limit (512MB)
**Python:** `config.py` (Pydantic Settings) — Telegram API credentials, Redis, cache settings
**Secrets:** GitHub Secrets → Docker env vars (API keys, session string, SSH)
**Docker:** `docker-compose.yml` — java-bot:8080, redis:6379, python-worker
