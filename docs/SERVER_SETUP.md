# Деплой на чистый сервер

> **Это инструкция для production VPS** (HTTPS через Traefik+ACME).
> Для локального запуска на dev-машине см. [`SETUP.md`](SETUP.md).

Пошаговая инструкция для разворачивания Telegram Export Cleaner на VPS
с доменом `your-domain.example.com`.

## Требования

- Ubuntu 22.04 / Debian 12
- Docker 24+ + Compose Plugin v2 (`docker compose`, не `docker-compose`)
- 2 GB RAM минимум (рекомендуется 4 GB)
- 20 GB диск (cache-data + dashboard.db)
- Открытые порты: **22** (SSH), **80** (HTTP), **443** (HTTPS)
- DNS: A-запись домена → IP сервера

## 1. DNS

Создайте A-запись:
```
your-domain.example.com → <IP сервера>
```
Проверка (DNS TTL может занять до 30 мин):
```bash
dig +short your-domain.example.com
```

## 2. Подготовка сервера

```bash
# Установка Docker (официальный способ)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker

# Firewall
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
```

## 3. Клонирование и настройка

```bash
cd /root
git clone https://github.com/thesmithmode/telegram-export-cleaner.git
cd telegram-export-cleaner

# Создать .env из шаблона
cp .env.example .env
nano .env          # заполнить секреты (см. ниже)
```

### Обязательные переменные в .env

```dotenv
# Telegram API (получить на https://my.telegram.org/apps)
TELEGRAM_API_ID=...
TELEGRAM_API_HASH=...
TELEGRAM_SESSION_STRING=...   # pyrogram string session
TELEGRAM_BOT_TOKEN=...

# Dashboard (Telegram Mini App auth, паролей нет)
DASHBOARD_ENABLE_BOOTSTRAP=true
DASHBOARD_ADMIN_TG_ID=<Telegram user_id админа, узнать у @userinfobot>

# Java↔Python API (общий ключ для X-API-Key, java-bot ApiKeyFilter и Python worker)
JAVA_API_KEY=<random_64_chars>

# Host data path (bind mounts для cache + dashboard)
HOST_DATA_PATH=/root/telegram-cleaner

# Traefik / HTTPS
TRAEFIK_ACME_EMAIL=your@email.com
TRAEFIK_DASHBOARD_DOMAIN=your-domain.example.com
```

## 4. Запуск

```bash
docker compose -f docker-compose.prod.yml --env-file .env pull
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

Дождаться получения сертификата (обычно 30–60 сек):
```bash
docker logs telegram-cleaner-traefik -f
# Ищите: "certificate obtained successfully"
```

Проверка:
```bash
curl -I https://your-domain.example.com/dashboard/login
# → HTTP/2 200
```

## 5. Обновление

CI/CD (GitHub Actions) автоматически пушит новые образы в GHCR при мёрдже в `main`.
Для обновления на сервере:
```bash
cd $SERVER_DEPLOY_PATH
docker compose -f docker-compose.prod.yml --env-file .env pull
docker compose -f docker-compose.prod.yml --env-file .env up -d --remove-orphans
```

## 6. Бэкапы

```bash
# Добавить в crontab (crontab -e):
# Ежедневно в 3:00 — бэкап dashboard.db и redis RDB
0 3 * * * docker exec telegram-export-redis redis-cli BGSAVE && \
    cp ${HOST_DATA_PATH}/dashboard/dashboard.db \
       ~/backups/dashboard-$(date +%F).db && \
    find ~/backups -name "dashboard-*.db" -mtime +30 -delete
```

## 7. Мониторинг

```bash
# Статус всех контейнеров
docker compose -f docker-compose.prod.yml ps

# Логи java-bot (Liquibase, Spring, ingestion)
docker logs telegram-export-java-bot -f --tail 100

# Redis: длина стрима и pending-list
docker exec telegram-export-redis redis-cli XLEN stats:events
docker exec telegram-export-redis redis-cli XINFO GROUPS stats:events

# Health-check
curl -sf http://localhost:8080/api/health    # внутренний
curl -sf https://your-domain.example.com/dashboard/login  # внешний
```

## 8. Ротация логов

Все контейнеры настроены с `max-size: 10m, max-file: 3` (суммарно ≤30 MB на сервис).
Для дополнительного контроля:
```bash
# Посмотреть размер логов
docker system df
```

## 9. Troubleshooting

| Проблема | Решение |
|---------|---------|
| Traefik не получает сертификат | Проверить DNS (dig), firewall (ufw status), логи Traefik |
| Let's Encrypt rate-limit (5 ошибок за час) | Подождать 1 час, исправить DNS/порты, потом перезапустить |
| Порт 80 занят | `ss -tlnp \| grep :80` — найти и остановить nginx/apache |
| java-bot не стартует | `docker logs telegram-export-java-bot` — Liquibase error? |
| Ошибка Pyrogram | Обновить `TELEGRAM_SESSION_STRING` через `python get_session.py` |
| dashboard.db permission denied | `chown -R 100:101 ${HOST_DATA_PATH}/dashboard` |

## 10. Изоляция /api от интернета

`/api/**` не публикуется через Traefik (в labels нет соответствующего router'а).
Доступ к API только через внутренний Docker network (`internal`).

Если нужен временный доступ к API с локальной машины:
```bash
ssh -L 8080:localhost:8080 user@server
curl http://localhost:8080/api/health
```
