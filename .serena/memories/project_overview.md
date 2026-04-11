---
name: Project Overview
description: Telegram Export Cleaner — назначение, стек и архитектура
type: project
---

# Telegram Export Cleaner

**Назначение:** Telegram-бот для экспорта истории чатов в чистый текстовый файл с фильтрацией по датам и ключевым словам.

## Основные возможности
- ✅ Экспорт публичных каналов, групп и приватных чатов
- 📅 Фильтрация по диапазону дат
- 🔍 Фильтрация по ключевым словам
- 🎨 Форматирование Telegram-разметки (bold, italic, code, ссылки)
- ⚡ Кэширование результатов
- ❌ Отмена экспорта во время выполнения

## Архитектура (микро-сервисная)
```
Telegram Bot (Java Spring Boot)
    ↓
Redis Queue (очередь задач)
    ↓
Python Worker (Pyrogram экспортер)
    ↓
REST API /api/convert (Java Spring Boot)
    ↓
Результат пользователю
```

## Tech Stack
- **Java:** 21, Spring Boot 3.4.4, Spring Security, Spring Data Redis
- **Python:** 3.11, Pyrogram 2.0.106, Pydantic, aiosqlite
- **Redis:** 7 (очереди задач)
- **SQLite:** кэш сообщений на диске (worker)
- **Docker:** контейнеризация (nginx reverse proxy, Java bot, Python worker)
- **SSL:** Let's Encrypt (certbot)

## Основные компоненты
1. **Java Bot** (`ExportBot`) — Telegram бот с wizard UI, управление заданиями
2. **Redis Queue** (`ExportJobProducer`) — SET NX для single-execution, выраженная очередь
3. **Python Worker** (`ExportWorker`) — Pyrogram экспортер, 3-path кэширование (date/id/fallback)
4. **REST API** (`TelegramController`) — конвертация JSON в текст, multipart streaming
5. **Message Processing** (`MessageProcessor`, `MarkdownParser`) — фильтрация, форматирование разметки

## Данные и кэширование
- **Java:** Redis (очереди, кэш)
- **Python:** SQLite (HDD-кэш сообщений, LRU по чатам, merge intervals, WAL mode)
- **Результаты:** файлы в личных сообщениях Telegram

## Безопасность
- API Key авторизация (`ApiKeyFilter`)
- Spring Security конфиг
- Basic Auth на nginx (на production сервере)
