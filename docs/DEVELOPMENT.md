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

---

## Pinning policy для зависимостей

- **Java/Maven**: версии каркасных артефактов наследуются из Spring Boot BOM,
  но любые версии, влияющие на runtime-поведение или security (Caffeine,
  Liquibase, sqlite-jdbc, telegrambots), **зафиксированы явно в `pom.xml`**.
  Без этого `mvn dependency:tree` после bump'а Spring Boot мог бы тихо
  поменять major-версии. Изменение пина — отдельный PR с обоснованием.
- **Python/pip**: `requirements.txt` использует `==X.Y.Z` (без `~=`/`>=`).
  Каждая версия закрепляется на тестируемом релизе; bump = правка строки
  + прогон CI. `pip-audit` запускается в CI на каждый push.
- **GitHub Actions**: third-party actions (`appleboy/scp-action`,
  `appleboy/ssh-action`) запинены **на SHA**, не на тег — supply-chain
  риск (тег можно перенести). Bump через `gh api repos/.../git/refs/tags/<TAG>`.
- **Docker base images**: `eclipse-temurin:21-jre-alpine`, `python:3.11-slim`,
  `redis:7-alpine` — major+minor зафиксированы. Digest-pin (`@sha256:...`)
  опционально, по риск-аппетиту проекта.

---

## Pre-commit hooks

`.pre-commit-config.yaml` в корне настраивает gitleaks + базовые file-санитайзеры.

```bash
pip install pre-commit
pre-commit install              # ставит hook в .git/hooks/pre-commit
pre-commit run --all-files      # прогон руками по всему репо
```

Зачем: репо публичный → случайно закоммиченный Telegram bot token / API hash
требует ротации всех env (отзыв через `@BotFather` + redeploy CI).
