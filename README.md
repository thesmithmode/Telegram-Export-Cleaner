# Telegram Export Cleaner

Инструмент для очистки и сжатия экспорта Telegram чата в формат, оптимизированный для работы с LLM.

## Возможности

- Парсит `result.json` из Telegram Desktop экспорта (machine-readable format)
- Конвертирует дату в формат `YYYYMMDD` (компактный)
- Обрабатывает Markdown форматирование (bold, italic, code, spoiler и др.)
- Корректно парсит mention-сущности: не дублирует `@`, если Telegram уже включает его в текст
- Каждое сообщение на отдельной строке
- Пропускает служебные сообщения (service messages)

## Качество кода

- **Checkstyle** - проверка стиля кода
- **JaCoCo** - покрытие тестами

### Запуск проверок

```bash
# Checkstyle запускается автоматически при сборке
mvn validate

# Покрытие тестами
mvn test

# Отчёт о покрытии
open target/site/jacoco/index.html
```

## Установка

```bash
# Сборка
mvn package

# Запуск в режиме CLI
java -jar target/telegram-cleaner-1.0.0.jar -i <путь_к_папке_экспорта>

# Запуск в режиме Web API (по умолчанию)
java -jar target/telegram-cleaner-1.0.0.jar
```

## Использование

```bash
# Базовая обработка
java -jar target/telegram-cleaner-1.0.0.jar -i ./ChatExport

# С указанием выходного файла
java -jar target/telegram-cleaner-1.0.0.jar -i ./ChatExport -o output.txt

# Подробный вывод
java -jar target/telegram-cleaner-1.0.0.jar -i ./ChatExport -v
```

### Опции CLI

| Опция | Описание | По умолчанию |
|-------|---------|--------------|
| `-i, --input` | Папка с result.json | Текущая папка |
| `-o, --output` | Выходной файл | tcleaner_output.txt |
| `-s, --start-date` | Начальная дата (YYYY-MM-DD) | - |
| `-e, --end-date` | Конечная дата (YYYY-MM-DD) | - |
| `-k, --keyword` | Ключевые слова для включения (через запятую) | - |
| `-x, --exclude` | Ключевые слова для исключения (через запятую) | - |
| `-v, --verbose` | Подробный вывод | false |
| `--help` | Показать справку | - |

### Примеры фильтрации

```bash
# Фильтр по дате
java -jar target/telegram-cleaner-1.0.0.jar -i ./ChatExport -s 2025-01-01 -e 2025-06-30

# Фильтр по ключевым словам
java -jar target/telegram-cleaner-1.0.0.jar -i ./ChatExport -k "привет,пока"

# Исключить ключевые слова
java -jar target/telegram-cleaner-1.0.0.jar -i ./ChatExport -x "спам,реклама"

# Комбинированный фильтр
java -jar target/telegram-cleaner-1.0.0.jar -i ./ChatExport -s 2025-01-01 -k "важно"
```

## Формат вывода

```
YYYYMMDD Текст сообщения
20250624 Привет, как дела?
20250625 Что делаешь?
```

## Пример

**Вход (result.json):**
```json
{
  "messages": [
    {
      "id": 1,
      "type": "message",
      "date": "2025-06-24T15:29:46",
      "from": "User",
      "text": "Привет!"
    }
  ]
}
```

**Выход:**
```
20250624 Привет!
```

## Требования

- Java 21+
- Maven 3.6+

## Сборка

```bash
mvn clean package
```

## Docker

### Быстрый старт

```bash
# Собрать и запустить
docker-compose up -d

# Проверить статус
docker-compose ps

# Остановить
docker-compose down
```

### Использование API

> **Аутентификация не требуется** — все endpoints публичные.

После запуска доступны endpoints:

```bash
# Health check
curl http://localhost:8080/api/health

# Загрузка файла (асинхронная обработка)
curl -X POST -F "file=@result.json" http://localhost:8080/api/files/upload

# Проверка статуса обработки
curl http://localhost:8080/api/files/<fileId>/status

# Скачивание результата
curl http://localhost:8080/api/files/<fileId>/download -o output.md

# Синхронная конвертация (ответ сразу)
curl -X POST -F "file=@result.json" http://localhost:8080/api/convert -o output.txt

# Конвертация JSON напрямую
curl -X POST -H "Content-Type: application/json" \
  -d @result.json \
  http://localhost:8080/api/convert/json
```

### Ограничения

- Максимальный размер файла: **50 МБ**
- Rate limit: **1 запрос в 15 секунд** (при превышении — 429 Too Many Requests)

### Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Проверка здоровья сервиса |
| POST | `/api/convert` | Синхронная конвертация файла result.json |
| POST | `/api/convert/json` | Синхронная конвертация JSON напрямую |
| POST | `/api/files/upload` | Загрузка файла с асинхронной обработкой |
| GET | `/api/files/{id}/status` | Проверка статуса обработки |
| GET | `/api/files/{id}/download` | Скачивание обработанного файла (.md) |

### Формат запросов и ответов

#### POST /api/files/upload

**Request:**
```
Content-Type: multipart/form-data
Body: file=@result.json
```

**Success Response (202 Accepted):**
```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "message": "Файл принят в обработку"
}
```

**Rate limit Response (429):**
```json
{"error": "Слишком частые запросы. Подождите 13 сек."}
```

**Error Response (400):**
```json
{"error": "Файл пустой"}
```

#### GET /api/files/{id}/status

**Response (200):**
```json
{"fileId": "...", "status": "COMPLETED", "exists": true}
```

Возможные статусы: `PENDING`, `COMPLETED`, `FAILED`, `NOT_FOUND`

#### POST /api/convert

**Request:**
```
Content-Type: multipart/form-data
Body: file=@result.json
```

**Success Response (200):**
```
Content-Type: text/plain
Body: (текстовый файл с результатом)
```

#### GET /api/health

**Success Response (200):**
```json
{"status": "UP"}
```

### Примеры использования

```bash
# Health check
curl http://localhost:8080/api/health

# Загрузка файла (асинхронно)
RESPONSE=$(curl -s -X POST -F "file=@result.json" http://localhost:8080/api/files/upload)
FILE_ID=$(echo $RESPONSE | python3 -c "import sys,json; print(json.load(sys.stdin)['fileId'])")

# Ожидание готовности и скачивание
curl -s http://localhost:8080/api/files/$FILE_ID/status
curl http://localhost:8080/api/files/$FILE_ID/download -o output.md

# Синхронная конвертация (проще для скриптов)
curl -X POST -F "file=@result.json" http://localhost:8080/api/convert -o output.txt

# С фильтрацией по дате
curl -X POST -F "file=@result.json" \
  -F "startDate=2025-01-01" -F "endDate=2025-06-30" \
  http://localhost:8080/api/convert -o filtered.txt

# С фильтрацией по ключевым словам
curl -X POST -F "file=@result.json" \
  -F "keywords=привет,пока" \
  http://localhost:8080/api/convert -o filtered.txt

# С исключением ключевых слов
curl -X POST -F "file=@result.json" \
  -F "excludeKeywords=спам,реклама" \
  http://localhost:8080/api/convert -o filtered.txt
```

## Тесты

```bash
mvn test
```

## Обработка ошибок

Приложение логирует все операции и ошибки. Уровень логирования можно настроить в `logback.xml`.

### Коды ошибок

| Код | Описание |
|-----|----------|
| `FILE_NOT_FOUND` | Файл result.json не найден |
| `INVALID_JSON` | Невалидный JSON файл |

### Пример логов

```
14:30:15.123 [main] DEBUG com.tcleaner.TelegramExporter - Начало обработки файла: /path/to/result.json
14:30:15.234 [main] DEBUG com.tcleaner.MessageProcessor - Начало обработки 150 сообщений
14:30:15.456 [main] INFO  com.tcleaner.TelegramExporter - Обработано 142 сообщений из файла result.json
```

## Лицензия

MIT License
