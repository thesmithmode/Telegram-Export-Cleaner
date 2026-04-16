# Development

## Технологии

- Java 21, Spring Boot 3.4.4
- Python 3.11 (Pyrogram worker)
- Redis 7
- Docker Compose

---

## Структура репозитория

- `src/main/java/com/tcleaner/` — Java приложение (bot + API + core).
- `src/test/java/com/tcleaner/` — Java тесты.
- `export-worker/` — Python worker.
- `export-worker/tests/` — Python тесты.
- `docs/` — проектная документация.

---

## Локальный запуск

```bash
cp .env.example .env
# заполните обязательные env

docker compose up -d
curl http://localhost:8080/api/health
```

---

## Проверки и тесты

Java:

```bash
mvn test
```

Python:

```bash
cd export-worker
pytest
```

Быстрые технические проверки:

```bash
git diff --check
```

---

## Когда обновлять документацию

Обновляйте `README.md` и `docs/*`, если меняются:
- контракт API (`/api/convert`, параметры, ошибки),
- модель очередей/ключей Redis,
- поведение worker-а (cache/recovery/cancel),
- обязательные env-переменные и шаги деплоя.

---

## Стиль коммитов

Рекомендуемый формат префикса:
- `FEAT:`
- `FIX:`
- `DOCS:`
- `REFACTOR:`
- `TEST:`
- `CHORE:`
