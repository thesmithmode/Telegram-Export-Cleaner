# CLAUDE.md

## Архитектура

Бот экспортирует чаты в текст: Java Bot → Redis очередь → Python Worker → /api/convert → результат пользователю.

**Стек:** Java 21, Spring Boot 3.4.4, Python 3.11, Pyrogram 2.0.106, Redis 7, SQLite, Docker.

## Структура проекта

```
src/main/java/com/tcleaner/   Java Spring Boot бэкенд
  bot/                        Telegram-бот, очередь задач
  api/                        REST API, безопасность
  core/                       Парсинг и фильтрация экспорта
  format/                     Форматирование вывода
src/test/java/com/tcleaner/   Java-тесты (JUnit 5)
export-worker/                Python Pyrogram worker
  main.py                     ExportWorker, точка входа
  message_cache.py            SQLite-кэш сообщений (HDD)
  queue_consumer.py           BLPOP-консьюмер Redis-очереди
  pyrogram_client.py          Pyrogram + canonical ID resolver
  java_client.py              HTTP-клиент /api/convert
  config.py / models.py       Настройки и Pydantic-модели
  json_converter.py           Конвертация Telegram JSON
  tests/                      Python-тесты (pytest)
docs/                         Документация (см. ниже)
Dockerfile                    Java bot образ
docker-compose.yml            Все сервисы (redis, java-bot, worker)
pom.xml / checkstyle.xml      Maven + code style
README.md                     Быстрый старт
```

## Документация

- `docs/ARCHITECTURE.md` — полная схема системы
- `docs/DEVELOPMENT.md` — dev-процесс, CI/CD
- `docs/API.md` — REST API reference
- `docs/PYTHON_WORKER.md` — worker internals
- `docs/SETUP.md` — деплой и конфигурация
- `README.md` — быстрый старт, переменные окружения
- JavaDoc — `/** */` комментарии в каждом публичном Java-классе/методе (не отдельный файл)

## Окружение

- Этот репозиторий — **исходники**. Прод-версия деплоится через **GitHub Actions** в Docker на **этот же сервер** — все контейнеры (`telegram-cleaner-java-bot-1`, `telegram-cleaner-python-worker-1`, `telegram-cleaner-redis-1`) работают прямо здесь.
- **Логи прод-версии доступны** через `docker logs <container>` напрямую с этого хоста — используй их для диагностики реальных инцидентов, не гадай.
- Рабочие данные (SQLite-кэш сообщений, Pyrogram session) — в volumes контейнеров; хост-пути см. в `docker-compose.yml`.

## MCP серверы

- **Serena** — семантический поиск/редактирование кода (индекс обновляется автоматически). **Используй её активно** вместо чтения файлов целиком: `get_symbols_overview`, `find_symbol`, `search_for_pattern` экономят контекст.

## Стратегия работы с субагентами

- **При сложных многоуровневых исследованиях** (найти логику в нескольких файлах, изучить взаимодействие модулей, собрать контекст по нескольким вопросам) — **запускай haiku-субагентов параллельно** через `Agent` tool (`subagent_type=general-purpose` или `Explore`, `model=haiku`).
- **Можно запускать до 5 субагентов одновременно** одним сообщением с несколькими tool calls — это значительно дешевле по токенам, чем читать все файлы самому Sonnet/Opus.
- **Чтение логов Docker тоже делегируй субагенту** — `docker logs <container>` может отдавать мегабайты, не жги ими контекст основной модели.
- Субагенту всегда давай **самодостаточный промпт**: цель, контекст, какие файлы/символы/команды смотреть, что вернуть и в каком формате (кратко, bullet-points, с file:line ссылками).
- Субагенту тоже можно (и нужно) говорить использовать Serena MCP.

## ⚠️ Критичные правила

1. **НИКОГДА не запускать тесты локально** — только GitHub Actions CI
2. **НИКОГДА не пересобирать контейнеры вручную** — только GitHub Actions
3. **Коммиты:** FIX:, FEAT:, REFACTOR: на РУССКОМ (ЗАГЛАВНЫЕ префиксы)
4. **НИКОГДА не добавлять Co-Authored-By** в коммиты
5. **Автор коммитов:** всегда `thesmithmode <117716736+thesmithmode@users.noreply.github.com>` — задан в `git config` репозитория, не менять
6. **Все merge делаются ТОЛЬКО через --squash** (один коммит)
7. **ЗАПРЕЩЕНО трогать ветку main** — никаких коммитов, пушей, мерджей в main без прямого приказа пользователя
8. **Пушить в текущую ветку, если это не main.** Для фиксов — уточнять у пользователя куда пушить, если не очевидно из контекста. Обычно работа идёт в отдельной ветке под конкретный фикс/фичу
9. **При конфликте локального и удалённого репо — ВСЕГДА приоритет у origin** (reset к remote, не rebase локального)

## Алгоритм работы над фиксами / фичами

Для любой задачи вида «пофикси X / добавь Y» жёсткий порядок:

1. **Ветка.** Работать в отдельной ветке. Имя — из контекста задачи или прямо от пользователя (`hotfix`, `fix/...`, `feat/...`). Если неясно — уточнить у пользователя. НЕ трогать `main` и чужие feature-ветки.
2. **План.** Сначала исследовать код (Serena + Explore-субагенты параллельно), потом зафиксировать план в файле. Только после одобрения — писать код.
3. **Тесты перед реализацией (или одновременно).** Для Python — `export-worker/tests/test_*.py`, для Java — `src/test/java/...`. Покрывать и happy path, и edge cases (пустой вход, сетевые ошибки, отмена и т.п.).
4. **Реализация** — минимально инвазивная. Не расширять scope.
5. **Документация.** Обновить только релевантное: `README.md`, `docs/*.md`, `CLAUDE.md` (если меняется workflow/архитектура). Не создавать новых md-файлов без необходимости.
6. **Коммиты** — атомарные, по одному логическому блоку. Русский, ЗАГЛАВНЫЕ префиксы (`FIX:`, `FEAT:`, `REFACTOR:`, `DOCS:`, `TEST:`). Автор `thesmithmode <117716736+thesmithmode@users.noreply.github.com>`. **НИКОГДА не добавлять Co-Authored-By.**
7. **Push** в свою ветку с retry (2/4/8/16s) при сетевых ошибках.
8. **CI.** После push — следить за GitHub Actions через MCP (`mcp__github__list_commits` → `mcp__github__get_commit` на HEAD своей ветки, смотреть `check_runs`/`statuses`). Если красный — чинить коммитом в ту же ветку.
9. **Мерж** — НИКОГДА без явной команды пользователя. PR тоже не создавать без явной команды.

При сомнениях по любому из шагов — сначала спросить, потом делать.

## Java пакеты

- **bot/** — ExportBot (wizard UI), ExportJobProducer (SET NX, express queue), UserSession
- **api/** — TelegramController (multipart, streaming), ApiKeyFilter, SecurityConfig
- **core/** — TelegramExporter (Tree + Streaming), MessageFilter, MessageProcessor, TelegramExporterException
- **format/** — MarkdownParser (20+ entity types), DateFormatter, UrlValidator, MessageFormatter, StringUtils

## Python worker

- **main.py** — ExportWorker (3-path caching: date/id/fallback)
- **pyrogram_client.py** — async Pyrogram, canonical ID resolver
- **message_cache.py** — SQLite на HDD (LRU по чатам, merge intervals, WAL)
- **queue_consumer.py** — BLPOP consumer, job lifecycle
- **java_client.py** — HTTP POST /api/convert, progress tracking

## Тестирование & качество

- **Java** — `src/test/java/com/tcleaner/`: JUnit 5 + AssertJ, 80% JaCoCo, Embedded Redis (ТОЛЬКО в CI)
- **Python** — `export-worker/tests/`: pytest + conftest.py, AsyncMock, SQLite tmp_path (ТОЛЬКО в CI)
- **Документация:** только README.md и файлы в `docs/`. JavaDoc и docstrings в исходниках — только если логика нетривиальна и требует пояснения
- Code style: checkstyle.xml (Java), PEP 8 (Python)
