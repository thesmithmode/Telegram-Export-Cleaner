# Development

## Технологии

- Java 21, Spring Boot 3.4.4
  - Spring Security (3 filter chains: actuator / dashboard / api)
  - Spring Actuator (`/actuator/health` публичен, остальное `denyAll`)
  - Spring Data JPA (Hibernate) + SQLite + Liquibase (миграции)
  - Bean Validation (jakarta.validation) на `/api/convert`
  - Thymeleaf (SSR дашборд)
  - Caffeine (in-memory cache)
- Python 3.11 (Pyrogram worker, httpx, redis.asyncio)
- Redis 7
- Docker Compose v2

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

> **Тесты запускаются только в GitHub Actions CI**, не локально.
> Push в любую ветку → CI прогоняет Java + Python тесты.
> Локально — только статические проверки.

Быстрые технические проверки локально:

```bash
git diff --check                   # whitespace / merge markers
mvn -q -DskipTests compile         # Java компиляция (без тестов)
python -m py_compile export-worker/*.py  # Python синтаксис
```

Полные тесты (Java JUnit 5 + AssertJ + Embedded Redis, Python pytest + AsyncMock):
ждать зелёный статус CI на push.

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
