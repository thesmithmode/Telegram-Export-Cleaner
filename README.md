# Telegram Export Cleaner

Инструмент для очистки и сжатия экспорта Telegram чата в формат, оптимизированный для работы с LLM.

## Возможности

- Парсит `result.json` из Telegram Desktop экспорта (machine-readable format)
- Конвертирует дату в формат `YYYYMMDD` (компактный)
- Обрабатывает Markdown форматирование (bold, italic, code, spoiler и др.)
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
java -jar target/telegram-cleaner-1.0.0.jar --cli -i <путь_к_папке_экспорта>

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

После запуска доступны endpoints:

```bash
# Health check
curl http://localhost:8080/api/health

# Загрузка файла (multipart/form-data)
curl -X POST -F "file=@result.json" http://localhost:8080/api/convert

# Загрузка JSON напрямую (application/json)
curl -X POST -H "Content-Type: application/json" \
  -d @result.json \
  http://localhost:8080/api/convert/json
```

### Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Проверка здоровья сервиса |
| POST | `/api/convert` | Загрузка файла result.json |
| POST | `/api/convert/json` | Отправка JSON напрямую |

### Формат запросов и ответов

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

**Error Response (400):**
```json
{"error": "Файл пустой"}
```
или
```json
{"error": "Ожидается JSON файл"}
```

#### POST /api/convert/json

**Request:**
```
Content-Type: application/json
Body: {"messages": [...]}
```

**Success Response (200):**
```
Content-Type: text/plain
Body: (текстовый файл с результатом)
```

**Error Response (400):**
```json
{"error": "Пустое содержимое"}
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

# Загрузка файла
curl -X POST -F "file=@result.json" http://localhost:8080/api/convert -o output.txt

# Загрузка JSON
curl -X POST -H "Content-Type: application/json" \
  -d '{"messages":[{"id":1,"type":"message","date":"2025-06-24T10:00:00","text":"Hello"}]}' \
  http://localhost:8080/api/convert/json

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
