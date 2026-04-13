# Setup

## 1) Подготовка

Нужно:
- Docker + Docker Compose
- Telegram API credentials (`api_id`, `api_hash`) с https://my.telegram.org/apps
- Bot token от @BotFather

## 2) Настройка `.env`

```bash
cp .env.example .env
```

Минимально заполните:
- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`
- `TELEGRAM_BOT_TOKEN`

Рекомендуется также заполнить:
- `TELEGRAM_SESSION_STRING` (для стабильного production-режима worker-а)

## 3) Запуск

```bash
docker compose up -d
```

Проверка Java API:

```bash
curl http://localhost:8080/api/health
```

Просмотр логов:

```bash
docker compose logs -f java-bot
docker compose logs -f export-worker
docker compose logs -f redis
```

## 4) Проверка бота

1. Откройте бота в Telegram.
2. Отправьте `/start`.
3. Отправьте `@username` или `https://t.me/...`.
4. Выберите диапазон и дождитесь `output.txt`.

---

## Production compose

Для деплоя через registry есть `docker-compose.prod.yml`.
В нём Java сервис слушает `127.0.0.1:8081` и использует готовые образы GHCR.

---

## Типовые проблемы

### `api/health` не отвечает
- Проверьте контейнер `java-bot` и его логи.
- Проверьте, не занят ли локальный порт 8080.

### Бот не экспортирует чат
- Убедитесь, что worker поднят и авторизован в Telegram.
- Для приватных чатов аккаунт worker-а должен быть участником.

### Ошибка валидации у worker при старте
- Чаще всего не заданы `TELEGRAM_API_ID` или `TELEGRAM_API_HASH`.
