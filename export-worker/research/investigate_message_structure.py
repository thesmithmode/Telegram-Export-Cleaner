#!/usr/bin/env python3
"""
🔬 Исследование структуры Pyrogram Message

Этот скрипт выводит:
1. Все поля Message объекта
2. Пример JSON структуры
3. Какие поля обязательные vs опциональные
4. Как парсить разные типы сообщений (text, photo, video, etc)

Использование:
    python3 investigate_message_structure.py
"""

import json
from typing import Any, Dict
from pyrogram import types as pyrogram_types


def analyze_message_fields() -> Dict[str, Any]:
    """Анализирует поля Message класса Pyrogram"""

    print("\n" + "="*80)
    print("📊 АНАЛИЗ PYROGRAM MESSAGE СТРУКТУРЫ")
    print("="*80 + "\n")

    # 1. Получить все поля Message
    message_class = pyrogram_types.Message

    print("1️⃣  ОСНОВНЫЕ ПОЛЯ MESSAGE\n")
    print(f"Message класс: {message_class}")
    print(f"Module: {message_class.__module__}")

    # Получить annotations (type hints)
    if hasattr(message_class, '__annotations__'):
        print(f"\nВсего полей: {len(message_class.__annotations__)}\n")

        for field_name, field_type in message_class.__annotations__.items():
            # Проверить обязательность (в Pyrogram используется Optional)
            is_optional = "Optional" in str(field_type) or "None" in str(field_type)
            marker = "❓" if is_optional else "✅"
            print(f"{marker} {field_name}: {field_type}")

    print("\n" + "="*80)
    print("2️⃣  ПРИМЕРЫ СООБЩЕНИЙ (ожидаемые структуры)\n")

    examples = {
        "Simple Text": {
            "id": 123,
            "date": "2025-06-24T15:29:46",
            "text": "Hello, world!",
            "from_user": {
                "id": 456,
                "first_name": "John"
            }
        },
        "With Photo": {
            "id": 124,
            "date": "2025-06-24T15:30:00",
            "text": "Check this photo",
            "photo": {
                "file_id": "abc123...",
                "width": 1024,
                "height": 768
            },
            "from_user": {
                "id": 456,
                "first_name": "John"
            }
        },
        "Forwarded Message": {
            "id": 125,
            "date": "2025-06-24T15:31:00",
            "text": "Interesting article",
            "forward_from": {
                "id": 789,
                "first_name": "Alice"
            },
            "forward_date": "2025-06-24T14:00:00"
        },
        "Edited Message": {
            "id": 126,
            "date": "2025-06-24T15:32:00",
            "text": "Updated text (edited)",
            "edit_date": "2025-06-24T15:33:00"
        },
        "With Entities": {
            "id": 127,
            "date": "2025-06-24T15:34:00",
            "text": "Check https://example.com",
            "entities": [
                {
                    "type": "url",
                    "offset": 6,
                    "length": 19
                }
            ]
        }
    }

    for msg_type, structure in examples.items():
        print(f"\n📌 {msg_type}:")
        print(json.dumps(structure, indent=2, ensure_ascii=False))

    print("\n" + "="*80)
    print("3️⃣  ОТОБРАЖЕНИЕ PYROGRAM → TELEGRAM DESKTOP FORMAT\n")

    print("""
Телеграм Desktop export (result.json) использует формат:
{
    "id": int,
    "type": "message",
    "date": "YYYY-MM-DDTHH:MM:SS",
    "from": "User Name",
    "text": "Message text",
    "text_entities": [...],  // optional
    "media_type": "photo",   // optional (photo, video, audio, etc)
    "file_name": "..."      // optional
}

Pyrogram Message использует формат:
{
    "id": int,
    "date": datetime,
    "text": str,
    "from_user": User object,
    "media": Media object,   // optional
    "entities": [Entity],    // optional
    "forward_from": User,    // optional
    "edit_date": datetime    // optional
}

МАППИНГ:
  result.json "date"       → Message.date (преобразовать str → datetime)
  result.json "from"       → Message.from_user.first_name + " " + Message.from_user.last_name
  result.json "text"       → Message.text
  result.json "text_entities" → Message.entities (но другой формат!)
  result.json "media_type" → Определить по Message.media типу
    """)

    print("\n" + "="*80)
    print("4️⃣  ОСОБЕННОСТИ PYROGRAM\n")

    print("""
❗ Важные моменты:

1. DATE HANDLING:
   - Pyrogram возвращает datetime объект
   - Telegram Desktop экспорт использует строку "YYYY-MM-DDTHH:MM:SS"
   - Нужно конвертировать: datetime → string ISO format

2. USER REPRESENTATION:
   - Pyrogram: Message.from_user это объект User class
   - result.json: "from" это строка (имя пользователя или ID)
   - Нужно собрать: f"{user.first_name} {user.last_name}".strip()

3. ENTITIES (links, mentions, bold, italic):
   - Pyrogram: entities это список Entity объектов с type, offset, length
   - result.json: text_entities это тоже список, но другой формат
   - Нужно преобразовать Entity → result.json format

4. MEDIA FILES:
   - Pyrogram может скачивать медиа (photo, video, audio, document)
   - result.json only contains metadata (file_name, media_type)
   - Для экспорта достаточно metadata, не нужно скачивать файлы

5. RATE LIMITING:
   - Telegram API rate limits: ~25 requests/second
   - Pyrogram может выбросить FloodWait исключение
   - Нужно реализовать exponential backoff + retry logic

6. SESSION MANAGEMENT:
   - Pyrogram сохраняет сессию в файл (session.dat)
   - Нужно использовать Docker volume для session/
   - Этот файл содержит auth token, нельзя потерять!
    """)

    print("\n" + "="*80)
    print("✅ ИССЛЕДОВАНИЕ ЗАВЕРШЕНО\n")

    return {
        "message_class": message_class,
        "annotations_count": len(message_class.__annotations__) if hasattr(message_class, '__annotations__') else 0
    }


if __name__ == "__main__":
    try:
        result = analyze_message_fields()
        print("\n📝 ВЫВОДЫ:\n")
        print("✅ Pyrogram Message структура проанализирована")
        print("✅ Формат Telegram Desktop понимаем")
        print("✅ Готовы к реализации JSON конвертера")
        print("✅ Готовы к реализации Pyrogram клиента")

    except ImportError as e:
        print(f"❌ Ошибка импорта: {e}")
        print("\nУстановите Pyrogram:")
        print("    pip install pyrogram==1.4.16")
        exit(1)
    except Exception as e:
        print(f"❌ Ошибка: {e}")
        import traceback
        traceback.print_exc()
        exit(1)
