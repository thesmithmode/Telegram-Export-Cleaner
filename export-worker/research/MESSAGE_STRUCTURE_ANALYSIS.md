# 🔬 Анализ структуры Pyrogram Message

**Дата:** 2026-03-18
**Статус:** Research Phase (Шаг 1 и 2)

## 📊 Исходные данные

Основываюсь на:
1. Кода TelegramExporter.java (Java side)
2. Документации Pyrogram 1.4.16
3. Telegram Desktop export format

---

## 🔍 TELEGRAM DESKTOP FORMAT (result.json)

Структура сообщения в Telegram Desktop экспорте:

```json
{
  "id": 123,
  "type": "message",
  "date": "2025-06-24T15:29:46",
  "from": "John Doe",
  "from_id": {"peer_type": "user", "peer_id": 456},
  "text": "Hello world",
  "text_entities": [
    {
      "type": "bold",
      "from": 0,
      "to": 5
    },
    {
      "type": "link",
      "from": 6,
      "to": 11,
      "href": "https://example.com"
    }
  ],
  "photo": "photo_1234.jpg",
  "photo_height": 768,
  "photo_width": 1024,
  "width": 1024,
  "height": 768,
  "media_type": "photo"
}
```

**Обязательные поля:**
- ✅ `id` - уникальный ID сообщения
- ✅ `type` - всегда "message"
- ✅ `date` - ISO 8601 строка (YYYY-MM-DDTHH:MM:SS)
- ✅ `text` - текст сообщения (может быть пустой/отсутствовать)

**Опциональные поля:**
- ❓ `from` - имя пользователя/группы
- ❓ `text_entities` - форматирование текста (bold, link, code, etc)
- ❓ `photo`, `video`, `audio`, `document` - медиафайлы
- ❓ `width`, `height` - размеры медиа
- ❓ `media_type` - тип медиа

---

## 🐍 PYROGRAM MESSAGE STRUCTURE

Pyrogram is a Python Telegram API wrapper. Message класс:

```python
class Message:
    # Обязательные
    id: int                           # Уникальный ID
    date: datetime                    # Дата сообщения (datetime object!)
    chat: Chat                        # Информация о чате

    # Опциональные текстовые
    text: str                         # Основной текст
    caption: str                      # Подпись к медиа
    entities: List[MessageEntity]     # Форматирование текста
    caption_entities: List[MessageEntity]  # Форматирование подписи

    # Пользователь
    from_user: User                   # Отправитель (может быть None в каналах)
    author_signature: str             # Подпись анонимного администратора

    # Медиа
    media: Union[                     # Один из типов медиа
        Document,
        Photo,
        Video,
        Audio,
        Voice,
        Video Note,
        Sticker,
        Animation,
        Game,
        Contact,
        Location,
        Poll,
        Venue,
        WebPage
    ]

    # Пересылка
    forward_from: User                # От кого пересланное (если пересланное)
    forward_from_chat: Chat           # Из какого чата пересланное
    forward_from_message_id: int      # ID оригинального сообщения
    forward_date: datetime            # Дата оригинального сообщения
    forward_sender_name: str          # Имя скрытого отправителя

    # Редактирование
    edit_date: datetime               # Дата редактирования (если редактировалось)

    # Ответы
    reply_to_message: Message         # Сообщение на которое ответили
    reply_to_message_id: int          # ID сообщения на которое ответили

    # Другое
    has_protected_content: bool       # Защищено от копирования
    service_message_type: ServiceMessage  # Тип служебного сообщения (leave, join, etc)
```

**Основное отличие от result.json:**
- ✅ `date` это datetime, не строка
- ✅ `from_user` это User объект, не строка
- ✅ Медиа объекты, а не просто filenames
- ✅ Entities это MessageEntity объекты

---

## 🔄 МАППИНГ PYROGRAM → TELEGRAM DESKTOP FORMAT

```python
# Pyrogram Message → result.json message

message_json = {
    # Прямое маппирование
    "id": message.id,
    "type": "message",
    "date": message.date.isoformat(),  # ⚠️ datetime → ISO string

    # Пользователь
    "from": get_user_name(message.from_user),  # ⚠️ User → string
    "from_id": {
        "peer_type": "user",
        "peer_id": message.from_user.id if message.from_user else None
    },

    # Текст и форматирование
    "text": message.text or "",
    "text_entities": convert_entities(message.entities),  # ⚠️ MessageEntity[] → [{type, from, to}]

    # Медиа
    "media_type": get_media_type(message.media),  # ⚠️ Media object → string
    "photo": message.media.file_name if isinstance(message.media, Photo) else None,
    "video": message.media.file_name if isinstance(message.media, Video) else None,
    # ... и т.д.

    # Пересылка
    "forward_from": message.forward_sender_name or (
        get_user_name(message.forward_from) if message.forward_from else None
    ),

    # Редактирование
    "edited": message.edit_date is not None,
}
```

---

## 🎯 КЛЮЧЕВЫЕ ОПЕРАЦИИ ПРЕОБРАЗОВАНИЯ

### 1. Дата преобразование

```python
from datetime import datetime

# Pyrogram возвращает: datetime(2025, 6, 24, 15, 29, 46)
# result.json нужно: "2025-06-24T15:29:46"

date_str = message.date.isoformat()
# Результат: "2025-06-24T15:29:46"
```

### 2. Пользователь преобразование

```python
def get_user_display_name(user):
    """Преобразует User объект в строку"""
    if not user:
        return "Unknown"

    parts = []
    if user.first_name:
        parts.append(user.first_name)
    if user.last_name:
        parts.append(user.last_name)

    name = " ".join(parts).strip()
    return name if name else f"@{user.username}" if user.username else f"ID:{user.id}"
```

### 3. Entities преобразование

```python
def convert_entities(entities):
    """MessageEntity[] → [{type, from, to}, ...]"""
    if not entities:
        return []

    result = []
    for entity in entities:
        result.append({
            "type": entity.type.lower(),  # "bold", "italic", "code", "url", etc
            "from": entity.offset,        # начало (в символах)
            "to": entity.offset + entity.length  # конец
        })

    return result
```

### 4. Медиа тип определение

```python
from pyrogram import types

def get_media_type(media):
    """Определяет тип медиа и возвращает строку"""
    if media is None:
        return None

    media_type_map = {
        types.Photo: "photo",
        types.Video: "video",
        types.Audio: "audio",
        types.Voice: "voice",
        types.Document: "document",
        types.Animation: "animation",
        types.Sticker: "sticker",
        types.VideoNote: "video_note",
        types.Contact: "contact",
        types.Location: "location",
        types.Poll: "poll",
    }

    for media_class, type_name in media_type_map.items():
        if isinstance(media, media_class):
            return type_name

    return "unknown"
```

### 5. Полная конвертация сообщения

```python
def convert_message_to_json(message: Message) -> dict:
    """Полная конвертация Pyrogram Message → result.json format"""

    # Основное
    result = {
        "id": message.id,
        "type": "message",
        "date": message.date.isoformat(),
        "text": message.text or "",
    }

    # Пользователь
    if message.from_user:
        result["from"] = get_user_display_name(message.from_user)
        result["from_id"] = {
            "peer_type": "user",
            "peer_id": message.from_user.id
        }

    # Форматирование текста
    if message.entities:
        result["text_entities"] = convert_entities(message.entities)

    # Медиа
    if message.media:
        media_type = get_media_type(message.media)
        result["media_type"] = media_type

        # Имя файла (если есть)
        if hasattr(message.media, 'file_name'):
            result[media_type] = message.media.file_name

        # Размеры (для фото и видео)
        if hasattr(message.media, 'width'):
            result["width"] = message.media.width
        if hasattr(message.media, 'height'):
            result["height"] = message.media.height

    # Пересылка
    if message.forward_from or message.forward_sender_name:
        if message.forward_from:
            result["forward_from"] = get_user_display_name(message.forward_from)
        else:
            result["forward_from"] = message.forward_sender_name

    if message.forward_date:
        result["forward_date"] = message.forward_date.isoformat()

    # Редактирование
    if message.edit_date:
        result["edited"] = True
        result["edit_date"] = message.edit_date.isoformat()

    # Ответ на сообщение
    if message.reply_to_message_id:
        result["reply_to_message_id"] = message.reply_to_message_id

    return result
```

---

## 📋 ТЕСТОВЫЕ СЛУЧАИ

### Тест 1: Простое текстовое сообщение

**Input (Pyrogram):**
```python
Message(
    id=123,
    date=datetime(2025, 6, 24, 15, 29, 46),
    text="Hello world",
    from_user=User(id=456, first_name="John", last_name="Doe")
)
```

**Output (result.json):**
```json
{
  "id": 123,
  "type": "message",
  "date": "2025-06-24T15:29:46",
  "text": "Hello world",
  "from": "John Doe",
  "from_id": {"peer_type": "user", "peer_id": 456}
}
```

### Тест 2: Сообщение с фото

**Input (Pyrogram):**
```python
Message(
    id=124,
    date=datetime(2025, 6, 24, 15, 30, 0),
    text="Check this",
    media=Photo(file_name="photo_123.jpg", width=1024, height=768),
    from_user=User(id=456, first_name="John")
)
```

**Output (result.json):**
```json
{
  "id": 124,
  "type": "message",
  "date": "2025-06-24T15:30:00",
  "text": "Check this",
  "from": "John",
  "from_id": {"peer_type": "user", "peer_id": 456},
  "media_type": "photo",
  "photo": "photo_123.jpg",
  "width": 1024,
  "height": 768
}
```

### Тест 3: Сообщение с форматированием

**Input (Pyrogram):**
```python
Message(
    id=125,
    date=datetime(2025, 6, 24, 15, 31, 0),
    text="Bold text and link",
    entities=[
        MessageEntity(type="bold", offset=0, length=4),
        MessageEntity(type="url", offset=14, length=4)
    ],
    from_user=User(id=456, first_name="John")
)
```

**Output (result.json):**
```json
{
  "id": 125,
  "type": "message",
  "date": "2025-06-24T15:31:00",
  "text": "Bold text and link",
  "from": "John",
  "text_entities": [
    {"type": "bold", "from": 0, "to": 4},
    {"type": "url", "from": 14, "to": 18}
  ]
}
```

---

## ⚠️ ВАЖНЫЕ ПРИМЕЧАНИЯ

### 1. Дата и время

- **Pyrogram**: возвращает `datetime` объект (может быть в UTC)
- **result.json**: ожидает ISO 8601 строку `"YYYY-MM-DDTHH:MM:SS"`
- **Решение**: используй `message.date.isoformat()`

### 2. Пользователи

- **Pyrogram**: `message.from_user` это User объект или None
- **result.json**: ожидает строку с именем
- **Решение**: собри имя из `first_name` + `last_name`

### 3. Entities (форматирование)

- **Pyrogram**: offset и length в символах (UTF-16)
- **Telegram Desktop**: from и to в символах (UTF-8)
- **Важно**: UTF-16 и UTF-8 могут считать эмодзи по-разному!
- **Решение**: тестировать с эмодзи и мультибайтными символами

### 4. Медиа файлы

- **Pyrogram**: может скачивать медиа (photo.download())
- **result.json**: only содержит метаданные (filename, размер)
- **Для экспорта**: достаточно только file_name и metadata
- **Скачивание**: опционально, добавим позже если нужно

### 5. Rate Limiting

- **Telegram API**: максимум ~25 запросов в секунду
- **Pyrogram**: может выбросить `FloodWait` исключение
- **Нужно**: реализовать exponential backoff + retry logic
- **Примерно**: 1s, 2s, 4s, 8s, 16s, 32s (макс)

### 6. Session Management

- **Pyrogram**: сохраняет сессию в `session_name.session` файл
- **Содержит**: auth token, account info, etc
- **Docker**: нужен volume для сохранения между запусками
- **Секретность**: никогда не коммитим session файлы в git!

---

## ✅ ГОТОВНОСТЬ К ШАГУ 3

- ✅ Понимаю структуру Pyrogram Message
- ✅ Понимаю структуру result.json
- ✅ Знаю как преобразовать одно в другое
- ✅ Знаю о всех edge cases
- ✅ Готовы к реализации JSON конвертера

Переходим на **Шаг 3: Создать структуру проекта export-worker** 🚀
