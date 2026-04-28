# REST API

Base URL (локально): `http://localhost:8080`

> В приложении доступны только два HTTP endpoint-а: `POST /api/convert` и `GET /api/health`.

---

## `POST /api/convert`

Конвертирует Telegram JSON export в текстовый файл.

### Аутентификация

Требует заголовок `X-API-Key: <JAVA_API_KEY>`. Без него — `401 Unauthorized`.

### Формат запроса

`multipart/form-data`

| Параметр | Тип | Обязательный | Комментарий |
|---|---|---:|---|
| `file` | file | ✅ | JSON export (`result.json`) |
| `startDate` | `YYYY-MM-DD` | Нет | Нижняя граница по дате |
| `endDate` | `YYYY-MM-DD` | Нет | Верхняя граница по дате |
| `keywords` | CSV string | Нет | Include-фильтр по словам (≤4096 символов) |
| `excludeKeywords` | CSV string | Нет | Exclude-фильтр по словам (≤4096 символов) |
| `taskId` | string | Нет | ID задачи в Redis-очереди; используется для связи события экспорта с job'ом при публикации метрик в `stats:events`. |
| `botUserId` | long (≥0) | Нет | Telegram ID пользователя бота — для атрибуции экспорта в дашборде статистики. |
| `chatTitle` | string | Нет | Человекочитаемое имя чата, попадает в `export_events.chat_title`. |
| `messagesCount` | long (≥0) | Нет | Количество исходных сообщений в экспорте (до фильтров); пишется в событие `EXPORT_COMPLETED`. |
| `subscriptionId` | long (>0) | Нет | ID подписки, если экспорт запущен периодическим триггером. Используется для обновления lifecycle подписки (`recordSuccess` / `recordFailure`). |

> Параметры `taskId`/`botUserId`/`chatTitle`/`messagesCount`/`subscriptionId` опциональны и используются исключительно для телеметрии дашборда. Если не передать, экспорт выполнится полноценно, но соответствующее событие не попадёт в Redis Stream `stats:events` (запись в `export_events` для этого задания не появится).

### Успешный ответ

- `200 OK`
- `Content-Type: text/plain`
- `Content-Disposition: attachment; filename=output.txt`

Тело ответа — готовый текстовый экспорт.

### Ошибки

- `400 Bad Request`
  - пустой `file`;
  - невалидные даты;
  - логические ошибки параметров фильтра.
- `500 Internal Server Error`
  - непредвиденные ошибки обработки.

Формат body ошибок (JSON):

- `4xx`: `{"message": "..."}`. Для `TelegramExporterException` и ConstraintViolation добавляется `"error": "<code>"`.
- `500`: `{"message": "Внутренняя ошибка сервера", "type": "InternalError"}`.

Полей `type: ExceptionClass` и `details: stacktrace` нет — убраны для предотвращения fingerprinting.

### Примеры

Базовый:

```bash
curl -X POST http://localhost:8080/api/convert \
  -H "X-API-Key: $JAVA_API_KEY" \
  -F "file=@result.json" \
  -o output.txt
```

С диапазоном и include/exclude:

```bash
curl -X POST http://localhost:8080/api/convert \
  -H "X-API-Key: $JAVA_API_KEY" \
  -F "file=@result.json" \
  -F "startDate=2024-01-01" \
  -F "endDate=2024-12-31" \
  -F "keywords=release,note" \
  -F "excludeKeywords=spam" \
  -o output.txt
```

С метриками для дашборда (вызов из Python-worker'а):

```bash
curl -X POST http://localhost:8080/api/convert \
  -H "X-API-Key: $JAVA_API_KEY" \
  -F "file=@result.json" \
  -F "taskId=b3c9f1e2-..." \
  -F "botUserId=123456789" \
  -F "chatTitle=My Channel" \
  -F "messagesCount=42000" \
  -F "subscriptionId=17" \
  -o output.txt
```

---

## `GET /api/health`

Проверка доступности Java-сервиса. **Публичный endpoint** — `ApiKeyFilter`
явно пропускает его без `X-API-Key` (нужен для health-probe Docker и
worker-а при холодном старте). Безопасно открыт, т.к. возвращает только
`{"status":"UP"}` без чувствительных данных. Доступен только внутри
Docker-сети (Traefik не публикует `/api/**` наружу).

### Ответ

`200 OK`

```json
{"status":"UP"}
```

### Пример

```bash
curl http://localhost:8080/api/health
```

---

## Actuator endpoints

Spring Boot Actuator (без аутентификации, детали компонентов скрыты).

| Endpoint | Описание |
|---|---|
| `GET /actuator/health` | Общий статус приложения |
| `GET /actuator/health/liveness` | Liveness probe (Kubernetes/Docker) |
| `GET /actuator/health/readiness` | Readiness probe (Kubernetes/Docker) |

### Пример

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}

curl http://localhost:8080/actuator/health/liveness
# {"status":"UP"}
```
