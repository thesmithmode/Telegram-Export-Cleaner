# Development Guide

## Процесс разработки

### Git Workflow

1. **Ветки:**
   - `main` — production версия (только squash merge из dev)
   - `dev` — разработка, интеграция
   - `Review` — ревью и оптимизация

2. **Merge в main (ТОЛЬКО squash):**
   ```bash
   git checkout main
   git merge --squash dev
   git commit -m "FIX: описание на русском"
   git push origin main
   git checkout dev && git reset --hard main
   ```

### Commit Message Style

**Формат:** `<TYPE>: описание на РУССКОМ`

**Типы:**
- `FIX:` — багфиксы
- `FEAT:` — новые фичи
- `REFACTOR:` — рефакторинг
- `TEST:` — добавление/обновление тестов
- `DOCS:` — изменения документации
- `CHORE:` — обновление зависимостей, конфиг

**Примеры:**
```
FIX: исправить ошибку в парсинге JSON
FEAT: добавить поддержку отмены экспорта
REFACTOR: упростить логику в TelegramExporter
TEST: добавить тесты для ApiKeyFilter
```

**⚠️ НИКОГДА:**
- ❌ НЕ ДОБАВЛЯТЬ Co-Authored-By трейлеры
- ❌ НЕ ИСПОЛЬЗОВАТЬ английский язык
- ❌ НЕ ИСПОЛЬЗОВАТЬ lowercase префиксы (fix: вместо FIX:)

---

## Тестирование

### ⚠️ КРИТИЧЕСКОЕ ПРАВИЛО: Тесты ТОЛЬКО в CI!

**НЕ запускай локально:**
```bash
# ❌ НЕПРАВИЛЬНО:
mvn test
pytest tests/

# ✅ ПРАВИЛЬНО:
# Commit и push в dev/Review
# GitHub Actions CI запустит тесты автоматически
```

### Java тесты

**Фреймворки:**
- JUnit 5 (spring-boot-starter-test)
- AssertJ (assertion library)
- Mockito (mocking)
- Embedded Redis (интеграционные тесты)

**Структура:** `src/test/java/com/tcleaner/<package>/<Class>Test.java`

**Требования:**
- Минимум 80% покрытие (JaCoCo enforcement)
- Все public методы должны иметь тесты
- Используй @DisplayName для русских описаний

**Пример:**
```java
@SpringBootTest
@DisplayName("Экспорт сообщений")
class TelegramExporterTest {
    
    @Test
    @DisplayName("должен корректно парсить Tree Mode")
    void shouldParseTreeMode() {
        // Arrange, Act, Assert
    }
}
```

### Python тесты

**Фреймворки:**
- pytest (основной фреймворк)
- pytest-asyncio (async тесты)
- pytest-cov (coverage reports)
- unittest.mock (AsyncMock, patch)

**Структура:** `tests/test_<module>.py`

**Требования:**
- conftest.py для fixtures и моков
- Мокировать Redis и Pyrogram
- Type hints обязательны

**Пример:**
```python
import pytest
from unittest.mock import AsyncMock, patch

@pytest.mark.asyncio
async def test_export_worker_processes_job(mock_redis, mock_pyrogram):
    # Arrange, Act, Assert
    pass
```

### CI/CD (GitHub Actions)

Тесты запускаются автоматически:

**Java (build.yml, ci.yml):**
```yaml
- run: mvn clean package
```

**Python (ci.yml):**
```yaml
- run: python -m pytest tests/ --cov
```

---

## Code Style

### Java

**Checkstyle:**
```bash
# Проверка (не запускать локально, только в CI):
mvn checkstyle:check
```

- Максимум 120 символов в строке
- 4 пробела для отступов
- CamelCase для классов и методов

**JavaDoc (обязательна):**
- Все public классы должны иметь класс-уровневую документацию
- Все public методы должны иметь @param, @return, @throws
- Примеры кода приветствуются

```java
/**
 * Экспортирует сообщения из JSON в текстовый файл.
 * 
 * @param inputPath путь к файлу JSON экспорта
 * @param filter параметры фильтрации (даты, keywords)
 * @param outputStream поток для результатов
 * @return количество экспортированных сообщений
 * @throws IOException если ошибка при чтении/записи файла
 */
public int exportMessages(Path inputPath, MessageFilter filter, OutputStream outputStream) 
    throws IOException {
    // ...
}
```

### Python

**Type hints (обязательны):**
```python
from typing import Optional, AsyncGenerator

async def get_messages(chat_id: int, limit: Optional[int] = None) -> AsyncGenerator[Message, None]:
    """
    Получить сообщения из чата.
    
    Args:
        chat_id: ID чата
        limit: максимум сообщений (None = все)
        
    Yields:
        Message объекты
    """
```

**Docstrings (обязательны для функций):**
- Module-level docstring для каждого файла
- Function docstring с Args, Returns, Raises

```python
"""
Export worker для обработки задач из Redis очереди.

Читает JSON из очереди, экспортирует сообщения через Pyrogram API,
кэширует результаты в Redis для повторных запросов.
"""
```

---

## Локальная разработка

### Что можно делать

✅ **Развёртывание:**
```bash
docker compose up -d
```

✅ **Быстрая проверка работоспособности:**
```bash
curl http://localhost:8080/api/health
```

✅ **Логирование контейнеров:**
```bash
docker logs st_java
docker logs st_python
```

✅ **Редактирование кода и файлов** (обычное развитие)

### Что НЕЛЬЗЯ делать

❌ **Запуск тестов локально**
```bash
# НЕПРАВИЛЬНО:
mvn test
pytest

# Тесты работают ТОЛЬКО в CI (GitHub Actions)
```

❌ **Пересборка контейнеров вручную**
```bash
# НЕПРАВИЛЬНО:
docker compose build

# Контейнеры собираются ТОЛЬКО в CI (GitHub Actions)
```

❌ **Использование --no-verify или --no-gpg-sign для commit**
```bash
# НЕПРАВИЛЬНО:
git commit --no-verify -m "test"

# Используй git hooks и все checks проходят через CI
```

---

## Структура проекта

```
src/main/java/com/tcleaner/
├── bot/                    # Java Bot + Redis queue + session management
│   ├── ExportBot.java
│   ├── ExportJobProducer.java
│   ├── BotMessenger.java
│   └── UserSession.java
├── api/                    # REST API + Security
│   ├── TelegramController.java
│   ├── ApiKeyFilter.java
│   └── SecurityConfig.java
├── core/                   # Основная логика экспорта
│   ├── TelegramExporter.java (Tree + Streaming)
│   ├── MessageProcessor.java
│   ├── MessageFilter.java
│   └── TelegramExporterException.java
└── format/                 # Форматирование и парсинг
    ├── MarkdownParser.java
    ├── DateFormatter.java
    ├── UrlValidator.java
    └── MessageFormatter.java

export-worker/             # Python worker
├── main.py               # ExportWorker (3 paths: date/id/fallback)
├── pyrogram_client.py    # Async Pyrogram client
├── message_cache.py      # Redis sorted sets
├── queue_consumer.py     # BLPOP consumer
└── java_client.py        # HTTP client для /api/convert
```

---

## Dependencies

### Java 21, Spring Boot 3.4.4

- `spring-boot-starter-web` — REST API
- `spring-boot-starter-security` — API Key filter
- `spring-boot-starter-data-redis` — Redis integration
- `telegrambots-springboot-longpolling-starter:9.5.0` — Telegram Bot API

### Python 3.11

- `pyrogram==2.0.106` — Telegram API async client
- `redis==5.0.1` — Redis async client
- `pydantic` — Configuration validation
- `pytest` — Testing framework

### Проверить версии

```bash
# Java
mvn dependency:tree | grep -E "telegrambots|spring-boot"

# Python
pip list | grep -E "pyrogram|redis|pydantic"
```

---

## Контрибьютинг

1. Создай branch от `dev`: `git checkout -b feature/my-feature`
2. Коммитуй с правильным форматом на русском
3. Push в свой fork/branch
4. GitHub Actions автоматически запустит тесты
5. Merge в `dev` через PR
6. Merge из `dev` в `main` через squash

---

## Полезные ссылки

- 📖 [ARCHITECTURE.md](ARCHITECTURE.md) — система архитектура
- 📡 [API.md](API.md) — REST API документация
- 🤖 [BOT.md](BOT.md) — Java Bot документация
- 🐍 [PYTHON_WORKER.md](PYTHON_WORKER.md) — Python Worker документация
- 🔧 [SETUP.md](SETUP.md) — установка и конфигурация
