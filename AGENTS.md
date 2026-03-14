# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Команды

```bash
# Сборка проекта
mvn clean package

# Проверка стиля кода
mvn checkstyle:checkstyle

# Покрытие тестами
mvn test jacoco:report
```

## Стиль кода

- **Checkstyle**: конфигурация в [`checkstyle.xml`](checkstyle.xml)
- **Line length**: максимум 120 символов
- **Star imports запрещены** (AvoidStarImport в checkstyle)
- **Javadoc** обязателен для public методов
- **Именования**: camelCase для переменных, PascalCase для классов

## Архитектура

```
TelegramController (REST API) -> TelegramExporter -> MessageProcessor
                                              -> DateFormatter
                                              -> MarkdownParser
```

- **TelegramExporter** - основной класс для обработки, парсит result.json
- **MessageProcessor** - преобразует сообщения в формат "YYYYMMDD text"
- **TelegramController** - REST endpoints /api/convert, /api/health

## Особенности

- Язык комментариев и логов: русский
- Использует Jackson для JSON, JCommander для CLI
- Временные файлы обрабатываются через inner class TempDirectory (AutoCloseable)
- Кастомное исключение: TelegramExporterException с кодами ошибок FILE_NOT_FOUND, INVALID_JSON
