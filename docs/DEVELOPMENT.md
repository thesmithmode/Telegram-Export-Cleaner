# Development

## Стек

- Java 21 + Spring Boot 3.4.4
- Python 3.11 (worker)
- Redis 7
- Docker Compose

## Структура

- `src/main/java/com/tcleaner` — Java API и Telegram-бот.
- `src/test/java/com/tcleaner` — Java тесты.
- `export-worker/` — Python worker.
- `export-worker/tests/` — Python тесты.

## Локальный запуск

```bash
docker compose up -d
```

## Полезные команды

```bash
# Java тесты
mvn test

# Python тесты
cd export-worker && pytest

# Проверка health
curl http://localhost:8080/api/health
```

## Документация и изменения

При изменениях API/очередей/форматов сообщений обновляйте:
- `README.md`
- `docs/API.md`
- `docs/ARCHITECTURE.md`
- `docs/PYTHON_WORKER.md`

## Конвенция коммитов

Используется формат:
- `FEAT: ...`
- `FIX: ...`
- `DOCS: ...`
- `REFACTOR: ...`
- `TEST: ...`
- `CHORE: ...`
