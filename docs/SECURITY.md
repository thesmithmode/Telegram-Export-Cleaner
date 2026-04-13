# Security

## Threat model (production)

Главные риски при серверном деплое:

1. **DoS по времени/размеру запроса** (бесконечные стримы, слишком большие multipart).
2. **Компрометация Redis** (чтение/подмена очереди и статусов задач).
3. **Контейнерный breakout/privilege escalation** при компрометации одного из сервисов.
4. **Избыточная диагностическая информация во внешних ошибках API**.

---

## Что уже зафиксировано в коде

### 1) Ограничение DoS-поверхности

- Убраны бесконечные таймауты:
  - `spring.mvc.async.request-timeout` теперь конечный (по умолчанию 15 минут).
  - `server.tomcat.connection-timeout` вынесен в env и по умолчанию 5 минут.
- Ограничен размер swallow buffer Tomcat (`server.tomcat.max-swallow-size`).
- Лимит upload уменьшен до `MAX_UPLOAD_SIZE` (по умолчанию 512MB, configurable).

### 2) Усиление Redis в production

- В `docker-compose.prod.yml` Redis запускается с `--requirepass`.
- Java/worker получают `REDIS_PASSWORD`.
- Healthcheck Redis использует аутентификацию.

### 3) Hardening контейнеров в production compose

- `security_opt: no-new-privileges:true` для всех сервисов.
- `cap_drop: [ALL]` для `java-bot`, `python-worker`, `redis`.
- `read_only: true` для `java-bot` и `python-worker`.
- `tmpfs` для `/tmp` (noexec/nosuid) в runtime-контейнерах.

### 4) Уменьшение утечки деталей ошибок

- Для `500` больше не возвращаются внутренние `details`.
- Для валидационных `400` детали остаются (удобно для клиента и безопаснее по профилю риска).

---

## Production checklist

1. Сгенерировать сильный `REDIS_PASSWORD`.
2. Убедиться, что Java порт открыт только на loopback/reverse-proxy (как в `127.0.0.1:8081`).
3. Не публиковать Redis наружу (никаких `ports:` для Redis в production).
4. Хранить `.env` только на сервере, не в репозитории.
5. Включить log-rotation (уже задано в compose).

---

## Дополнительные рекомендации (вне текущего патча)

- Поставить reverse proxy rate-limits (Nginx/Caddy) на внешние HTTP точки.
- Добавить IDS/alerting на рост размеров очереди Redis и повторяющиеся 5xx.
- Периодически ротировать `REDIS_PASSWORD`.
