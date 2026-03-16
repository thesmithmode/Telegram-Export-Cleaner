# Telegram Export Cleaner

Инструмент для очистки и конвертации экспорта Telegram-чатов в текстовый формат, оптимизированный для работы с LLM (Large Language Models).

## Содержание

- [Что делает](#что-делает)
- [Архитектура](#архитектура)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [CLI-режим](#cli-режим)
- [Web API режим](#web-api-режим)
- [Конфигурация](#конфигурация)
- [Docker](#docker)
- [CI/CD](#cicd)
- [Качество кода](#качество-кода)
- [Коды ошибок](#коды-ошибок)
- [Лицензия](#лицензия)

---

## Что делает

Приложение берёт файл `result.json` из экспорта Telegram Desktop и преобразует его в компактный текстовый формат: одно сообщение — одна строка.

**Формат вывода:**
```
YYYYMMDD Текст сообщения
20250624 Привет, как дела?
20250625 Тут написал что-то **важное** и ссылка [нажми](https://example.com)
```

**Что обрабатывается:**
- Все типы текстовых сущностей: plain, bold, italic, code, pre, strikethrough, spoiler, underline, blockquote, link, text\_link, mention, hashtag, cashtag, email, phone, bot\_command, custom\_emoji, bank\_card
- Пропускаются служебные (`type: service`) сообщения
- Пропускаются сообщения без текста (медиа без подписи и т.п.)
- Переносы строк внутри сообщения заменяются пробелами (одно сообщение = одна строка)

**Пример:**

| Вход (`result.json`) | Выход |
|---|---|
| `{"messages": [{"id":1,"type":"message","date":"2025-06-24T15:29:46","text":"Привет!"}]}` | `20250624 Привет!` |

---

## Архитектура

```
TelegramCleanerApplication   ← точка входа (Web / CLI)
│
├── CLI-режим:  Main → TelegramExporter → MessageProcessor
│                                       → DateFormatter
│                                       → MarkdownParser
│                                       → MessageFormatter
│
└── Web-режим:
    ├── TelegramController   POST /api/convert, POST /api/convert/json, GET /api/health
    │   └── TelegramExporter (синхронно, ответ в теле)
    │
    └── FileController       POST /api/files/upload, GET /api/files/{id}/status, GET /api/files/{id}/download
        ├── FileStorageService   (Import/Export папки, @Async обработка)
        │   └── TelegramExporter
        └── ProcessingStatusService (статусы в Redis)
```

### Ключевые классы

| Класс | Роль |
|---|---|
| `TelegramCleanerApplication` | Точка входа; решает Web vs CLI по флагу `--cli` |
| `Main` | CLI: парсинг аргументов (JCommander), вызов `TelegramExporter` |
| `TelegramExporter` | Основной сервис: читает JSON, делегирует фильтрацию и форматирование |
| `MessageProcessor` | Извлекает дату и текст из одного JSON-узла, строит строку результата |
| `MessageFilter` | Fluent-builder фильтр: по дате, ключевым словам, типу, произвольному предикату |
| `MessageFilterFactory` | Создаёт `MessageFilter` из строковых параметров (CLI / HTTP) |
| `MarkdownParser` | Конвертирует массив `text_entities` в Markdown |
| `DateFormatter` | ISO 8601 → `YYYYMMDD` и `YYYYMMDDHHmm` |
| `MessageFormatter` | Собирает итоговую строку `"YYYYMMDD текст"` |
| `TelegramController` | REST: синхронная конвертация |
| `FileController` | REST: асинхронная обработка через Import/Export папки |
| `FileStorageService` | Управление файлами (Import → обработка → Export), очистка по TTL |
| `ProcessingStatusService` | Хранение статусов обработки в Redis (`status:<fileId>`) |
| `StorageCleanupScheduler` | `@Scheduled`: удаляет устаревшие файлы из Export |
| `SecurityConfig` | Spring Security: все endpoints публичные, CSRF отключён |
| `StorageConfig` | `@ConfigurationProperties(prefix = "app.storage")`: пути и TTL |
| `TelegramExporterException` | RuntimeException с кодом ошибки (`FILE_NOT_FOUND`, `INVALID_JSON`) |

### Интерфейсы

| Интерфейс | Реализация | Используется в |
|---|---|---|
| `TelegramExporterInterface` | `TelegramExporter` | `TelegramController` (только processFile) |
| `TelegramFileExporterInterface` | `TelegramExporter` | `Main` (processFileToFile) |

---

## Требования

- **Java 21+**
- **Maven 3.6+**
- **Redis** (для Web-режима с асинхронной обработкой через `/api/files/*`)

> В CLI-режиме Redis **не нужен**.

---

## Быстрый старт

```bash
# Сборка
mvn clean package

# CLI (без Redis)
java -jar target/telegram-cleaner-1.0.0.jar --cli -i ./ChatExport -o output.txt

# Web API (нужен Redis на localhost:6379)
java -jar target/telegram-cleaner-1.0.0.jar
```

---

## CLI-режим

Запускается с флагом `--cli`. Redis не требуется.

```bash
java -jar target/telegram-cleaner-1.0.0.jar --cli -i <папка_с_result.json> [опции]
```

### Опции

| Опция | Описание | По умолчанию |
|---|---|---|
| `-i, --input` | Папка с `result.json` | `.` (текущая) |
| `-o, --output` | Выходной файл | `tcleaner_output.txt` |
| `-s, --start-date` | Начальная дата (YYYY-MM-DD) | — |
| `-e, --end-date` | Конечная дата (YYYY-MM-DD) | — |
| `-k, --keyword` | Включать только сообщения с ключевыми словами (через запятую) | — |
| `-x, --exclude` | Исключать сообщения с ключевыми словами (через запятую) | — |
| `-v, --verbose` | Подробный вывод | `false` |
| `--help` | Справка | — |

### Примеры

```bash
# Базовая обработка
java -jar target/telegram-cleaner-1.0.0.jar --cli -i ./ChatExport

# Фильтр по дате
java -jar target/telegram-cleaner-1.0.0.jar --cli -i ./ChatExport -s 2025-01-01 -e 2025-06-30

# Фильтр по ключевым словам (хотя бы одно должно присутствовать)
java -jar target/telegram-cleaner-1.0.0.jar --cli -i ./ChatExport -k "привет,пока"

# Исключить сообщения с указанными словами
java -jar target/telegram-cleaner-1.0.0.jar --cli -i ./ChatExport -x "спам,реклама"

# Комбинированный фильтр с подробным выводом
java -jar target/telegram-cleaner-1.0.0.jar --cli -i ./ChatExport -s 2025-01-01 -k "важно" -v
```

---

## Web API режим

Запускается **без** флага `--cli`. Поднимает HTTP-сервер на порту `8080`.  
Все endpoints публичные — аутентификация не требуется.

### Endpoints

| Метод | Endpoint | Описание |
|---|---|---|
| GET | `/api/health` | Проверка здоровья сервиса |
| POST | `/api/convert` | Синхронная конвертация (multipart/form-data) |
| POST | `/api/convert/json` | Синхронная конвертация (JSON в теле) |
| POST | `/api/files/upload` | Загрузка файла с асинхронной обработкой |
| GET | `/api/files/{id}/status` | Статус асинхронной обработки |
| GET | `/api/files/{id}/download` | Скачивание результата (`.md`) |

---

### GET /api/health

```bash
curl http://localhost:8080/api/health
```

```json
{"status": "UP"}
```

---

### POST /api/convert — синхронная конвертация (multipart)

Принимает `result.json`, возвращает текст в теле ответа.  
Максимальный размер файла: **50 МБ**.

```bash
# Базовая конвертация
curl -X POST -F "file=@result.json" http://localhost:8080/api/convert -o output.txt

# С фильтром по дате
curl -X POST -F "file=@result.json" \
  -F "startDate=2025-01-01" -F "endDate=2025-06-30" \
  http://localhost:8080/api/convert -o filtered.txt

# С ключевыми словами
curl -X POST -F "file=@result.json" \
  -F "keywords=привет,пока" \
  -F "excludeKeywords=спам" \
  http://localhost:8080/api/convert -o filtered.txt
```

**Параметры запроса:**

| Параметр | Тип | Описание |
|---|---|---|
| `file` | file | `result.json` (обязательно) |
| `startDate` | string | Начальная дата `YYYY-MM-DD` (опционально) |
| `endDate` | string | Конечная дата `YYYY-MM-DD` (опционально) |
| `keywords` | string | Слова для включения, через запятую (опционально) |
| `excludeKeywords` | string | Слова для исключения, через запятую (опционально) |

**Ответы:**

| Код | Описание |
|---|---|
| `200` | `text/plain` с обработанными сообщениями |
| `400` | Пустой файл / не JSON / невалидный формат даты |
| `500` | Внутренняя ошибка |

---

### POST /api/convert/json — синхронная конвертация (JSON в теле)

Принимает содержимое `result.json` напрямую в теле запроса. Лимит: **10 МБ**.

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d @result.json \
  "http://localhost:8080/api/convert/json?startDate=2025-01-01" \
  -o output.txt
```

---

### POST /api/files/upload — асинхронная загрузка

Файл сохраняется в папку Import под UUID-именем, запускается асинхронная обработка.  
**Rate limit: 1 запрос в 15 секунд.**

```bash
curl -X POST -F "file=@result.json" http://localhost:8080/api/files/upload
```

**Ответ 202 Accepted:**
```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "message": "Файл принят в обработку"
}
```

**Ответ 429 Too Many Requests:**
```json
{"error": "Слишком частые запросы. Подождите 13 сек."}
```

---

### GET /api/files/{id}/status — статус обработки

```bash
curl http://localhost:8080/api/files/550e8400-e29b-41d4-a716-446655440000/status
```

```json
{"fileId": "550e8400-...", "status": "COMPLETED", "exists": true}
```

| Статус | Описание |
|---|---|
| `PENDING` | Файл принят, обработка ещё идёт |
| `COMPLETED` | Файл обработан, можно скачивать |
| `FAILED` | Ошибка при обработке |
| `NOT_FOUND` | Файл не найден (удалён по TTL или не загружался) |

---

### GET /api/files/{id}/download — скачивание результата

```bash
curl http://localhost:8080/api/files/550e8400-e29b-41d4-a716-446655440000/download \
  -o output.md
```

Возвращает `text/markdown` файл. TTL файла: **10 минут** (настраивается).

---

### Полный сценарий асинхронной обработки (bash)

```bash
# 1. Загрузить файл
RESPONSE=$(curl -s -X POST -F "file=@result.json" http://localhost:8080/api/files/upload)
FILE_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['fileId'])")
echo "File ID: $FILE_ID"

# 2. Дождаться завершения (опрос)
while true; do
  STATUS=$(curl -s "http://localhost:8080/api/files/$FILE_ID/status" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  echo "Status: $STATUS"
  [ "$STATUS" = "COMPLETED" ] && break
  [ "$STATUS" = "FAILED" ] && echo "ERROR" && exit 1
  sleep 2
done

# 3. Скачать результат
curl "http://localhost:8080/api/files/$FILE_ID/download" -o output.md
```

---

## Конфигурация

Конфигурация хранится в `src/main/resources/application.properties`.  
Переменные окружения переопределяют значения из файла.

| Свойство | Переменная окружения | По умолчанию | Описание |
|---|---|---|---|
| `server.port` | — | `8080` | Порт HTTP-сервера |
| `app.storage.import-path` | — | `/data/import` | Папка входящих файлов |
| `app.storage.export-path` | — | `/data/export` | Папка готовых файлов |
| `app.storage.export-ttl-minutes` | — | `10` | TTL файлов в Export (мин) |
| `app.storage.cleanup-interval-ms` | — | `60000` | Интервал очистки (мс) |
| `spring.servlet.multipart.max-file-size` | — | `50MB` | Макс. размер файла |
| `spring.data.redis.host` | `REDIS_HOST` | `localhost` | Хост Redis |
| `spring.data.redis.port` | `REDIS_PORT` | `6379` | Порт Redis |

---

## Docker

### Быстрый старт

```bash
# Поднять приложение + Redis
docker-compose up -d

# Проверить статус
docker-compose ps

# Логи приложения
docker-compose logs -f telegram-cleaner

# Остановить
docker-compose down
```

### Что поднимается

- **telegram-cleaner** — приложение на порту `8080`, volumes: `./import:/data/import`, `./export:/data/export`
- **redis** — Redis 7 Alpine на порту `6379`

Оба сервиса имеют healthcheck. `telegram-cleaner` стартует только после готовности Redis.

### Сборка образа вручную

```bash
docker build -t telegram-cleaner .
docker run -p 8080:8080 \
  -e REDIS_HOST=localhost \
  -v ./import:/data/import \
  -v ./export:/data/export \
  telegram-cleaner
```

> Dockerfile использует multi-stage build: Maven для сборки, JRE-only образ для запуска.  
> Приложение запускается от непривилегированного пользователя `appuser`.  
> JVM ограничена: `-Xmx256m -Xms64m`.

---

## CI/CD

Пайплайн запускается автоматически при каждом push в ветку `main` и состоит из трёх последовательных этапов.

**Build & Test** — собирает проект через `mvn clean package` и прогоняет все тесты. Если тесты упали, следующие этапы не запускаются.

**Publish Docker Image** — собирает Docker-образ и публикует его в GitHub Container Registry (GHCR). Образ тегируется двумя тегами: `:latest` и коротким SHA коммита (например `:a1b2c3d`), что позволяет откатиться на любую предыдущую версию.

**Deploy to Production** — подключается к серверу по SSH, обновляет `docker-compose.yml` и перезапускает контейнеры через `docker compose up -d`.

Все чувствительные данные (адрес сервера, SSH-ключ) хранятся в GitHub Secrets и никогда не появляются в коде.

---

## Качество кода

```bash
# Сборка + все проверки
mvn clean package

# Только тесты
mvn test

# Только Checkstyle
mvn checkstyle:checkstyle

# Отчёт о покрытии JaCoCo
mvn test jacoco:report
# Открыть: target/site/jacoco/index.html
```

- **Checkstyle**: конфигурация в [`checkstyle.xml`](checkstyle.xml). Максимальная длина строки — 120 символов. Star imports запрещены. Javadoc обязателен для public методов.
- **JaCoCo**: минимальное покрытие строк — **80%**.

---

## Коды ошибок

| Код | HTTP | Описание |
|---|---|---|
| `FILE_NOT_FOUND` | 400 | `result.json` не найден по указанному пути |
| `INVALID_JSON` | 400 | Файл не является валидным JSON или повреждён |
| `GENERAL_ERROR` | 400 | Неклассифицированная ошибка экспортера |

**Формат ответа при ошибке:**
```json
{
  "error": "FILE_NOT_FOUND",
  "message": "Файл не найден: /path/to/result.json"
}
```

**Пример логов:**
```
14:30:15.123 [main] DEBUG com.tcleaner.TelegramExporter - Начало обработки файла: /path/result.json
14:30:15.234 [main] DEBUG com.tcleaner.MessageProcessor - Начало обработки 150 сообщений
14:30:15.456 [main] INFO  com.tcleaner.TelegramExporter - Обработано 142 сообщений из файла result.json
```

Уровень логирования настраивается в `application.properties`:
```properties
logging.level.com.tcleaner=DEBUG
```

---

## Лицензия

MIT License — см. файл [LICENSE](LICENSE).
