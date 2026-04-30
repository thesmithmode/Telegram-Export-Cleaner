"""TD-058: контракт ENTITY_TYPE_MAP с актуальной Pyrogram-версией.

Если pyrogram bump'нет MessageEntityType (rename/add), .name.lower() вернёт
строку, отсутствующую в нашем маппинге, и convert_entities молча отдаст
unmapped значение. Тест ловит drift на CI и заставляет обновить маппинг.
"""

from pyrogram.enums import MessageEntityType

from json_converter import MessageConverter


def test_all_pyrogram_entity_types_present_in_map():
    """Все enum-значения MessageEntityType (lower-case) есть в ENTITY_TYPE_MAP.

    Если падает — pyrogram добавил/переименовал тип. Update ENTITY_TYPE_MAP
    в json_converter.py чтобы entity не сериализовался под raw enum-name.
    """
    # UNKNOWN — служебный тип Pyrogram для неопознанных entities, маппинг
    # не обязателен (туда попадает любой raw type fallback'ом в convert_entities).
    pyrogram_types = {
        m.name.lower() for m in MessageEntityType if m.name != "UNKNOWN"
    }
    mapped = set(MessageConverter.ENTITY_TYPE_MAP.keys())

    missing = pyrogram_types - mapped
    assert not missing, (
        f"Pyrogram MessageEntityType типы не покрыты ENTITY_TYPE_MAP: {sorted(missing)}. "
        "Обновите json_converter.ENTITY_TYPE_MAP."
    )
