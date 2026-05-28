# AGENTS.md — Telegram Export Cleaner

Само-обучение: новое проектное правило добавлять сюда. Без дублей, <=100 строк.

## Что это

Telegram Export Cleaner — Telegram-бот и REST API для выгрузки истории чатов в текстовый файл. Архитектура: Java 21 + Spring Boot 3.4.4 для бота, API и dashboard; Python 3.11 worker через Pyrogram; Redis 7 для очередей; SQLite/Liquibase для dashboard; Docker Compose для локального и production запуска.

## Где смотреть сначала

- `README.md` — продукт, локальный запуск, REST API, общая архитектура.
- `docs/ARCHITECTURE.md` — data flow и подписки.
- `docs/DEVELOPMENT.md` — структура, проверки, pinning policy.
- `docs/API.md`, `docs/BOT.md`, `docs/PYTHON_WORKER.md`, `docs/DASHBOARD.md` — контракты слоев.
- `.codex/autoresearch/` — локальный контекст эксперимента dashboard UX optimization.
- `.codex/agent-memory/code-reviewer/` — память code-review по архитектурным рискам.

## Инварианты архитектуры

- Java владеет ботом, REST API, session/status orchestration и dashboard.
- Python worker владеет Telegram API через Pyrogram, SQLite-кэшем сообщений и выполнением export jobs.
- Redis — граница очередей и статусов; не ломать приоритеты `express`, `main`, `subscription`.
- `/api/**` кроме `/api/health` требует `X-API-Key: $JAVA_API_KEY`.
- Dashboard живет в том же Spring Boot процессе под `/dashboard/**`.
- UI бота поддерживает 10 языков; тексты держать в `src/main/resources/bot_messages[_<lang>].properties`.

## Workflow

- Работать от текущей ветки проекта; `main` не трогать без явной команды пользователя.
- Ветки для экспериментов из `.codex/autoresearch/program.md`: `ux-fast`, не мержить в `main`/`dev` напрямую.
- Push в любую ветку запускает CI с Java + Python тестами.
- Production deploy описан в `docs/SERVER_SETUP.md`; mutating действия на prod только через согласованный deploy/rollback pipeline.
- Секреты только в `.env`, GitHub Secrets или переменных окружения. Не печатать значения токенов/API hash/API key в чат, stdout, логи или коммиты.

## Проверки

- Локально быстрые проверки: `git diff --check`, `mvn -q -DskipTests compile`, `python -m py_compile export-worker/*.py`.
- Полные Java/Python тесты выполняются в GitHub Actions CI.
- Если меняется API, очередь Redis, worker cache/recovery/cancel, env или deploy — обновить `README.md` и `docs/*`.
- Coverage ниже глобального порога проекта нельзя считать зеленым релизным состоянием; исторический JaCoCo gate 82% из code-review не является целевой нормой.

## Кодовые правила

- Java: Spring Boot BOM задает каркасные версии, runtime/security зависимости фиксировать явно в `pom.xml`.
- Python: `requirements.txt` pin через `==X.Y.Z`, без `~=` и `>=`.
- GitHub Actions third-party actions pin на SHA, не на tag.
- Docker base images фиксировать минимум major+minor; digest-pin по риску задачи.
- Не добавлять новые managed external services без явного согласования.

## Почему такое решение, даже если кажется странным:

- Autoresearch и agent-memory перенесены в `.codex/`, потому что это полезный агентский контекст, а не runtime-настройки старого инструмента.
- Локальные settings-файлы старого инструмента не переносятся в AGENTS.md: это настройки запуска, а не проектные правила.
- Полные тесты не запускаются локально по умолчанию, потому что `docs/DEVELOPMENT.md` фиксирует CI-only полные проверки; локально используются дешевые sanity gates.
