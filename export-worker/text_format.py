import re
from datetime import datetime
from typing import Optional

from models import ExportedMessage


EXPORT_TEXT_FORMAT_VERSION = 1
_NEWLINE_RE = re.compile(r"\r\n|\r|\n")


def format_java_export_date(date_str: Optional[str]) -> str:
    if not date_str:
        return ""
    if len(date_str) > 19 and date_str[19] in ("+", "-", "Z"):
        return ""
    try:
        normalized = date_str[:19]
        dt = datetime.strptime(normalized, "%Y-%m-%dT%H:%M:%S")
        return dt.strftime("%Y%m%d")
    except (TypeError, ValueError):
        return ""


def parse_keywords(raw: Optional[str]) -> list[str]:
    if not raw:
        return []
    return [part.strip().lower() for part in raw.split(",") if part.strip()]


def normalize_export_text(text: str) -> str:
    return _NEWLINE_RE.sub(" ", text)


def build_export_cache_fields(msg: ExportedMessage) -> tuple[Optional[str], Optional[str], int]:
    if msg is None or msg.type == "service":
        return None, None, EXPORT_TEXT_FORMAT_VERSION
    date = format_java_export_date(msg.date)
    if not date:
        return None, None, EXPORT_TEXT_FORMAT_VERSION
    text = msg.text or ""
    if not text.strip():
        return None, text.lower(), EXPORT_TEXT_FORMAT_VERSION
    normalized = normalize_export_text(text)
    return f"{date} {normalized}", text.lower(), EXPORT_TEXT_FORMAT_VERSION


def line_matches_keywords(
    filter_text: Optional[str],
    include_keywords: Optional[list[str]] = None,
    exclude_keywords: Optional[list[str]] = None,
) -> bool:
    text = filter_text or ""
    if include_keywords and not any(kw in text for kw in include_keywords):
        return False
    if exclude_keywords and any(kw in text for kw in exclude_keywords):
        return False
    return True


def format_cached_message_line(
    msg: ExportedMessage,
    include_keywords: Optional[list[str]] = None,
    exclude_keywords: Optional[list[str]] = None,
) -> Optional[str]:
    line, filter_text, _ = build_export_cache_fields(msg)
    if line is None:
        return None
    if not line_matches_keywords(filter_text, include_keywords, exclude_keywords):
        return None
    return line
