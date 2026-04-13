# REST API

Base URL (локально): `http://localhost:8080`

> В приложении доступны только два HTTP endpoint-а: `POST /api/convert` и `GET /api/health`.

---

## `POST /api/convert`

Конвертирует Telegram JSON export в текстовый файл.

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
  -F "file=@result.json" \
  -o output.txt
```

С диапазоном и include/exclude:

```bash
curl -X POST http://localhost:8080/api/convert \
  -F "file=@result.json" \
  -F "startDate=2024-01-01" \
  -F "endDate=2024-12-31" \
  -F "keywords=release,note" \
  -F "excludeKeywords=spam" \
  -o output.txt
```

---

## `GET /api/health`

Проверка доступности Java-сервиса.

### Ответ

`200 OK`

```json
{"status":"UP"}
```

### Пример

```bash
curl http://localhost:8080/api/health
```
