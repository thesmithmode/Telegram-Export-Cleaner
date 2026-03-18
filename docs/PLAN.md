# 📋 План: Telegram Bot для автоматизации экспорта и очистки чатов

**Статус:** 📝 Готов к реализации
**Дата:** 2026-03-18
**Временная шкала:** 19-26 часов

---

## 🎯 Обзор проекта

Расширение существующего приложения `Telegram-Export-Cleaner` с добавлением автоматизации через Telegram Bot:

**Текущее состояние:**
- Java REST API для загрузки и обработки `result.json` (экспорт Telegram Desktop)
- Очистка JSON и преобразование в текстовый формат
- Веб-интерфейс для загрузки файлов

**Целевое состояние:**
- Telegram Bot интерфейс вместо веб-сайта
- Бот сам скачивает экспорт по ID чата
- Асинхронная обработка 1000+ одновременных запросов
- Интерактивное меню с выбором параметров

---

## 🏗️ Архитектура системы

```
┌─────────────────────────────────────────────┐
│       Telegram Bot (Java/Spring)            │
│  • Интерактивное меню с кнопками            │
│  • Валидация публичности чатов              │
│  • Управление состояниями пользователя      │
└────────────┬────────────────────────────────┘
             │
      ┌──────▼──────────┐
      │  Redis Streams  │
      │  • Очередь      │
      │  • Результаты   │
      │  • Sessions     │
      └────────┬────────┘
               │
    ┌──────────▼───────────┐
    │  Python Worker       │
    │  (Pyrogram)          │
    │  • Экспорт чата      │
    │  • Преобразование    │
    │  • Управление сессией│
    └──────────┬───────────┘
               │
    ┌──────────▼───────────┐
    │  Java Processing     │
    │  (Существующий код) │
    │  • Очистка JSON     │
    │  • Генерация .md    │
    └──────────────────────┘
```

---

## 📦 Компоненты

### 1. Java Telegram Bot Service
- **Язык:** Java + Spring Boot
- **Библиотека:** `java-telegram-bot-api`
- **Функционал:**
  - Webhook для получения обновлений от Telegram
  - InlineKeyboard меню для выбора чата
  - Валидация (приватные чаты = ошибка)
  - Управление состояниями пользователя (выбор даты, фильтры)
  - Отправка задач в Redis Queue
  - Слушание результатов и отправка файлов

### 2. Python Export Worker
- **Язык:** Python 3.9+
- **Библиотеки:** Pyrogram, aioredis, requests
- **Функционал:**
  - Слушание Redis Stream для задач
  - Аутентификация в Telegram (phone account)
  - Скачивание истории чата через Takeout API
  - Преобразование в JSON (формат `result.json` Telegram Desktop)
  - HTTP POST на Java сервис для обработки
  - Обработка ошибок и retry logic

### 3. Redis Infrastructure
- **Версия:** 7.0+
- **Хранилище:**
  - `export-tasks` - очередь задач
  - `export-results` - готовые результаты
  - `user-sessions` - состояния пользователей
- **Конфигурация:** AOF для надежности

### 4. Интеграция с существующим кодом
- Использование существующего `FileController`
- Использование `FileStorageService` для обработки
- Использование `ProcessingStatusService` для мониторинга
- Redis уже интегрирован ✅

---

## 📋 Фазы реализации

### Фаза 1: Инфраструктура Redis (1-2 часа)
- Обновить docker-compose.yml
- Добавить AOF persistence
- Конфигурация в application.properties

**Файлы:**
- `docker-compose.yml`
- `application.properties`

---

### Фаза 2: Python Export Worker (8-10 часов)
**Статус:** ⏳ Начать с этой фазы

**Структура проекта:**
```
export-worker/
├── main.py
├── pyrogram_client.py
├── json_converter.py
├── queue_consumer.py
├── java_client.py
├── models.py
├── config.py
├── requirements.txt
├── Dockerfile
└── tests/
```

**Функции:**
- Слушание Redis Stream `export-tasks`
- Аутентификация Pyrogram (требует phone number)
- Экспорт истории чата через `get_chat_history()`
- Преобразование в JSON (совместимо с Telegram Desktop format)
- HTTP POST на `POST /api/files/upload`
- Сохранение результатов в Redis Stream `export-results`

---

### Фаза 3: Java Bot Service (6-8 часов)
**Статус:** ⏳ После Python Worker

**Структура классов:**
```
src/main/java/com/tcleaner/bot/
├── TelegramBotService.java
├── TelegramUpdateHandler.java
├── TelegramMenuBuilder.java
├── TelegramBotConfig.java
├── UserSessionManager.java
└── ExportResultListener.java
```

**Функции:**
- Webhook для получения сообщений от Telegram
- Интерактивное меню (выбор чата, параметры)
- Валидация (отклонить приватные чаты)
- Управление состояниями пользователя
- Добавление задач в Redis `export-tasks`
- Слушание и обработка результатов

---

### Фаза 4: Интеграция и тестирование (4-6 часов)
**Статус:** ⏳ В конце

- Docker Compose для всех сервисов
- Нагрузочное тестирование (1000 одновременных)
- Обработка edge cases
- Документация и примеры
- Мониторинг и логирование

---

## 🔐 Требования для старта

### Telegram API Credentials
1. Зайти на https://my.telegram.org/apps
2. Создать приложение (любое название)
3. Получить:
   - `API_ID` (число)
   - `API_HASH` (строка)

### Phone Number для экспорта
- Может быть отдельный номер (рекомендуется)
- Или существующий личный номер
- Понадобится для одноразовой аутентификации (SMS код)

### Telegram Bot Token
- Создать бота у @BotFather в Telegram
- Получить token (будет использоваться в Java сервисе)
- Команда: `/newbot`

---

## 📊 Сравнение: Redis vs RabbitMQ

**Рекомендация: Redis Streams** ⭐⭐⭐⭐⭐

| Параметр | Redis | RabbitMQ |
|----------|-------|----------|
| Latency | **0.1-1ms** ✅ | 2-5ms |
| Memory (1000 задач) | **400KB** ✅ | 13MB |
| Setup время | **5 мин** ✅ | 30 мин |
| Пропускная способность | 100K msg/s | 100K msg/s |
| Надежность | 95% (с AOF) | 99% |
| Уже установлен | **ДА** ✅ | НЕТ |
| **Общий рейтинг** | **95/100** ✅ | **60/100** |

**Вывод:** Redis на **10x быстрее**, **30x меньше памяти**, **6x проще setup**, **стоит $0**

Полное сравнение в файле [`QUEUE_COMPARISON.md`](./QUEUE_COMPARISON.md)

---

## 🔄 Flow пользователя

```
Пользователь пишет боту
         ↓
Bot показывает меню "Выбрать чат"
         ↓
User нажимает кнопку
         ↓
Bot: "Получаю список ваших чатов..." (API Telegram)
         ↓
Bot отправляет список (InlineKeyboard)
         ↓
User выбирает публичный чат/канал
         ↓
Bot: "Выбрать период? (по умолчанию вся история)"
         ↓
User нажимает "Период" и выбирает даты
         ↓
Bot: "⏳ Обрабатываю... (номер в очереди)"
         ↓
Задача добавлена в Redis
         ↓
Python Worker берет задачу
         ↓
Pyrogram скачивает историю
         ↓
Преобразует в JSON
         ↓
Отправляет на Java сервис
         ↓
Java очищает JSON и генерирует .md
         ↓
Результат в Redis
         ↓
Bot получает результат
         ↓
Bot: "✅ Готово! Скачай файл"
         ↓
User скачивает cleaned_export.md
```

---

## ⚠️ Ограничения и особенности

### Telegram API
- ❌ Bot API НЕ может экспортировать историю (только новые сообщения)
- ✅ Pyrogram (Client API) может экспортировать полную историю
- ⚠️ Требует phone account для аутентификации
- ⚠️ Приватные чаты не будут доступны боту без приглашения

### Масштабируемость
- Redis Streams подходит до **100K одновременных задач**
- Для 1M+ нужен Redis Cluster
- Python Worker ограничен CPU/Memory хоста
- Рекомендуется: **Max 1000 одновременных** для стабильности

### Безопасность
- ⚠️ Session file (session.dat) должен быть в `.gitignore`
- API_ID и API_HASH должны быть в `.env`
- Bot token должен быть в `.env` (не в коде)
- Все пароли в конфигах (secrets, vault)

---

## 📈 Метрики и мониторинг

### Redis Streams
```
Команды для мониторинга:
- XLEN export-tasks      # Размер очереди
- XLEN export-results    # Размер результатов
- DBSIZE                 # Общий размер БД
- INFO memory            # Использование памяти
```

### Python Worker
```
Логирование:
- Время начала/конца задачи
- Размер экспортированного чата
- Ошибки Pyrogram
- Memory usage
```

### Java Bot
```
Метрики:
- Количество активных пользователей
- Среднее время обработки
- Ошибки валидации
- Успешные экспорты
```

---

## 🚀 Следующие шаги

1. **Собрать Telegram API credentials**
   - API_ID и API_HASH из my.telegram.org

2. **Начать с Фазы 1 (Redis) + Фазы 2 (Python Worker)**
   - Создать структуру проекта
   - Реализовать Pyrogram интеграцию
   - Тестирование отдельно

3. **Параллельно Фаза 3 (Java Bot)**
   - TelegramBotService
   - Menu builder
   - Integration с Redis

4. **Фаза 4: Интеграция**
   - Docker Compose
   - Нагрузочное тестирование
   - Documentation

---

## 📚 Связанные документы

- [`RESEARCH_FINDINGS.md`](./RESEARCH_FINDINGS.md) - Выводы исследования
- [`ARCHITECTURE.md`](./ARCHITECTURE.md) - Детальная архитектура
- [`QUEUE_COMPARISON.md`](./QUEUE_COMPARISON.md) - Redis vs RabbitMQ
- [`IMPLEMENTATION/`](./IMPLEMENTATION/) - Детальные инструкции по фазам
- [`API_DESIGN.md`](./API_DESIGN.md) - API между компонентами
- [`SETUP_REQUIREMENTS.md`](./SETUP_REQUIREMENTS.md) - Требования и setup
- [`CHECKLIST.md`](./CHECKLIST.md) - Чеклист реализации

---

**Версия:** 1.0
**Дата обновления:** 2026-03-18
