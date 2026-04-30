# Setup

> **Это инструкция для локальной разработки.** Для деплоя на VPS с
> доменом / TLS / Traefik см. [`SERVER_SETUP.md`](SERVER_SETUP.md).

## 1) Требования

- Docker + Docker Compose
- Telegram API credentials (`api_id`, `api_hash`) с https://my.telegram.org/apps
- Bot token от @BotFather

---

## 2) Подготовка `.env`

```bash
cp .env.example .env
```

### Обязательные поля

- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`
- `TELEGRAM_BOT_TOKEN`
- `JAVA_API_KEY` — секрет между java-bot и export-worker. Сервер не стартует без него. Сгенерировать: `openssl rand -hex 32`

### Рекомендуемые поля

- `TELEGRAM_SESSION_STRING` — для надежной авторизации worker-а в production.
- `CACHE_DB_PATH`, `CACHE_MAX_DISK_GB` — под размер вашего диска.
- `REDIS_*` / `JAVA_API_BASE_URL` — если не используете значения по умолчанию.

---

## 3) Получение `TELEGRAM_SESSION_STRING` (опционально, но желательно)

Вариант локально:

```bash
cd export-worker
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python get_session.py
```

`requirements.txt` уже включает `pyrogram` и `TgCrypto`, поэтому отдельный `pip install pyrogram` не обязателен.
Если нужен минимальный вариант только для генерации сессии, можно поставить вручную:

```bash
pip install pyrogram TgCrypto
```

Сгенерированную строку сохраните в `.env` как `TELEGRAM_SESSION_STRING`.

---

## 4) Запуск

```bash
docker compose up -d
```

Проверка API:

```bash
curl http://localhost:8080/api/health
```

Логи:

```bash
docker compose logs -f java-bot
docker compose logs -f export-worker
docker compose logs -f redis
```

---

## 5) Smoke-check бота

1. Откройте бота в Telegram.
2. Отправьте `/start`.
3. Отправьте `@username` или `https://t.me/...`.
4. Выберите диапазон дат.
5. Проверьте, что пришел `output.txt`.

---

## Production compose

Для серверного деплоя есть `docker-compose.prod.yml`.

Отличия:
- Java API доступен через Traefik (порты 80/443), не экспонируется напрямую.
- Используются prebuilt образы из GHCR.
- Важно корректно задать `.env` на сервере.

---

## Troubleshooting

### `curl /api/health` не отвечает
- Проверьте контейнер `java-bot`.
- Проверьте конфликт порта 8080.

### Worker падает при старте
- Часто причина: пустые/невалидные `TELEGRAM_API_ID` или `TELEGRAM_API_HASH`.
- Проверьте, что `TELEGRAM_SESSION_STRING` актуальна.

### Экспорт приватного чата не работает
- Аккаунт worker-а (не бот) должен быть участником чата/канала.

### `/cancel` говорит, что активного экспорта нет
- Проверьте TTL ключа `active_export:{userId}` и текущий статус job в Redis.

---

## Ротация JAVA_API_KEY

`JAVA_API_KEY` — shared secret между java-bot и export-worker. Хранится в
GitHub Secrets, инжектится в `.env` на сервере при CI deploy. Ротация:

1. Сгенерировать новый: `openssl rand -hex 32`.
2. Обновить GitHub Secret `JAVA_API_KEY` (Settings → Secrets and variables → Actions).
3. Дождаться следующего push в `main` ИЛИ вручную retrigger workflow.
4. Проверить, что оба контейнера (java-bot + python-worker) перезапустились
   и `docker compose logs java-bot` не содержит `401 Invalid API Key`.

Деплой атомарный (rollback при health-fail), поэтому пауза в окне обновления
не нужна — старый ключ работает до момента up'а нового image. После — оба
контейнера читают новый из `.env`.
