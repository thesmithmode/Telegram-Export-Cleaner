# REST API

Base URL: `http://localhost:8080`

## `POST /api/convert`

Конвертирует Telegram export JSON (`result.json`) в текстовый файл `output.txt`.

### Request

`multipart/form-data`

| Параметр | Тип | Обязательный | Описание |
|---|---|---:|---|
| `file` | file | ✅ | JSON-файл экспорта Telegram |
| `startDate` | `YYYY-MM-DD` | Нет | Нижняя граница даты |
| `endDate` | `YYYY-MM-DD` | Нет | Верхняя граница даты |
| `keywords` | CSV | Нет | Включать сообщения с ключевыми словами |
| `excludeKeywords` | CSV | Нет | Исключать сообщения с ключевыми словами |

### Response

- `200 OK`
  - `Content-Type: text/plain`
  - `Content-Disposition: attachment; filename=output.txt`

- `400 Bad Request`
  - Для невалидных дат/параметров или пустого файла.

- `500 Internal Server Error`
  - Для неожиданных ошибок.

### Примеры

```bash
curl -X POST http://localhost:8080/api/convert \
  -F "file=@result.json" \
  -o output.txt
```

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

### Response

`200 OK`

```json
{"status":"UP"}
```

---

## Формат ошибок

`ApiExceptionHandler` возвращает JSON вида:

```json
{
  "message": "...",
  "type": "ExceptionClass",
  "details": "..."
}
```

Для ошибок `TelegramExporterException` дополнительно есть поле `error`.
