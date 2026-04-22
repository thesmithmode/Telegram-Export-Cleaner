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
| `keywords` | CSV string | Нет | Include-фильтр по словам |
| `excludeKeywords` | CSV string | Нет | Exclude-фильтр по словам |

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

```json
{
  "message": "...",
  "type": "ExceptionClass",
  "details": "..."
}
```

Для `TelegramExporterException` добавляется поле `error`.

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
