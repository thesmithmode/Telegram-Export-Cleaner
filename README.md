# Telegram Export Cleaner

Инструмент для очистки и сжатия экспорта Telegram чата в формат, оптимизированный для работы с LLM.

## Возможности

- Парсит `result.json` из Telegram Desktop экспорта (machine-readable format)
- Конвертирует дату в формат `YYYYMMDD` (компактный)
- Обрабатывает Markdown форматирование (bold, italic, code, spoiler и др.)
- Каждое сообщение на отдельной строке
- Пропускает служебные сообщения (service messages)

## Установка

```bash
# Сборка
mvn package

# Запуск
java -jar target/telegram-cleaner-1.0.0.jar -i <путь_к_папке_экспорта>
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
| `-v, --verbose` | Подробный вывод | false |
| `--help` | Показать справку | - |

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

- Java 17+
- Maven 3.6+

## Сборка

```bash
mvn clean package
```

## Тесты

```bash
mvn test
```

## Лицензия

MIT License
