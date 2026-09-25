"""Render Telegram Instant View ``Page`` objects as bounded plain text.

Pyrogram 2.0 intentionally drops ``WebPage.cached_page`` from its high-level
``WebPage`` object.  The worker therefore obtains the raw page separately and
passes it here.  This module deliberately uses structural attribute lookup
instead of importing every raw constructor: Telegram can add new container
types without making an otherwise readable article disappear.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable, Optional


MAX_RICH_PAGE_DEPTH = 32
MAX_RICH_PAGE_NODES = 20_000
MAX_RICH_PAGE_CHARS = 1_000_000
TRUNCATED_MARKER = "[content truncated]"


@dataclass
class _RenderState:
    nodes: int = 0
    truncated: bool = False


def _class_name(value: Any) -> str:
    return value.__class__.__name__ if value is not None else ""


def _clean(value: Any) -> str:
    if value is None:
        return ""
    return " ".join(str(value).split())


def _visit(state: _RenderState, depth: int) -> bool:
    if depth > MAX_RICH_PAGE_DEPTH or state.nodes >= MAX_RICH_PAGE_NODES:
        state.truncated = True
        return False
    state.nodes += 1
    return True


def render_rich_text(value: Any, state: Optional[_RenderState] = None, depth: int = 0) -> str:
    """Return the visible text from a raw Telegram ``RichText`` tree."""
    state = state or _RenderState()
    if value is None or not _visit(state, depth):
        return ""
    if isinstance(value, str):
        return value

    name = _class_name(value)
    if name in {"TextEmpty", "RichTextEmpty"}:
        return ""
    if name in {"TextPlain", "RichTextPlain"}:
        return str(getattr(value, "text", "") or "")
    if name == "TextImage":
        return "[Image]"

    children = getattr(value, "texts", None)
    if children is not None:
        return "".join(render_rich_text(child, state, depth + 1) for child in children)

    child = getattr(value, "text", None)
    if child is not None:
        text = render_rich_text(child, state, depth + 1)
        if name in {"TextUrl", "TextEmail", "TextPhone"}:
            target = (
                getattr(value, "url", None)
                or getattr(value, "email", None)
                or getattr(value, "phone", None)
            )
            if target and str(target) not in text:
                return f"{text} ({target})" if text else str(target)
        return text

    # Future wrappers may rename their child while retaining a single RichText.
    for attr in ("title", "caption", "credit"):
        candidate = getattr(value, attr, None)
        if candidate is not None:
            return render_rich_text(candidate, state, depth + 1)
    return ""


def _render_caption(caption: Any, state: _RenderState, depth: int) -> str:
    if caption is None:
        return ""
    text = render_rich_text(getattr(caption, "text", caption), state, depth + 1)
    credit = render_rich_text(getattr(caption, "credit", None), state, depth + 1)
    return " — ".join(part for part in (_clean(text), _clean(credit)) if part)


def _render_blocks(blocks: Optional[Iterable[Any]], state: _RenderState, depth: int) -> list[str]:
    result: list[str] = []
    for block in blocks or ():
        result.extend(_render_block(block, state, depth + 1))
        if state.truncated:
            break
    return result


def _render_block(block: Any, state: _RenderState, depth: int) -> list[str]:
    if block is None or not _visit(state, depth):
        return []

    name = _class_name(block)

    if name in {
        "PageBlockUnsupported",
        "PageBlockAnchor",
        "PageBlockDivider",
        # Recommendations and channel widgets are surrounding Instant View UI,
        # not the publication body the user asked to export.
        "PageBlockRelatedArticles",
        "PageBlockChannel",
    }:
        return []

    if name in {"PageBlockPhoto", "PageBlockVideo", "PageBlockAudio"}:
        label = name.removeprefix("PageBlock")
        caption = _render_caption(getattr(block, "caption", None), state, depth)
        return [f"[{label}: {caption}]" if caption else f"[{label}]"]

    if name in {"PageBlockCollage", "PageBlockSlideshow"}:
        result = _render_blocks(getattr(block, "items", None), state, depth)
        caption = _render_caption(getattr(block, "caption", None), state, depth)
        if caption:
            result.append(caption)
        return result

    if name == "PageBlockDetails":
        title = _clean(render_rich_text(getattr(block, "title", None), state, depth + 1))
        return ([title] if title else []) + _render_blocks(getattr(block, "blocks", None), state, depth)

    if name == "PageBlockTable":
        result: list[str] = []
        title = _clean(render_rich_text(getattr(block, "title", None), state, depth + 1))
        if title:
            result.append(title)
        for row in getattr(block, "rows", None) or ():
            cells = []
            for cell in getattr(row, "cells", None) or ():
                cells.append(_clean(render_rich_text(getattr(cell, "text", None), state, depth + 1)))
            if cells:
                result.append(" | ".join(cells))
        return result

    if name in {"PageBlockList", "PageBlockOrderedList"}:
        result: list[str] = []
        ordered = name == "PageBlockOrderedList"
        for index, item in enumerate(getattr(block, "items", None) or (), start=1):
            item_number = getattr(item, "num", None)
            prefix = str(item_number) if item_number else f"{index}." if ordered else "-"
            item_text = _clean(render_rich_text(getattr(item, "text", None), state, depth + 1))
            nested = _render_blocks(getattr(item, "blocks", None), state, depth)
            if item_text:
                result.append(f"{prefix} {item_text}")
            elif nested:
                result.append(f"{prefix} {nested[0]}")
                result.extend(nested[1:])
        return result

    if name == "PageBlockEmbedPost":
        result = []
        author = _clean(getattr(block, "author", None))
        if author:
            result.append(author)
        result.extend(_render_blocks(getattr(block, "blocks", None), state, depth))
        caption = _render_caption(getattr(block, "caption", None), state, depth)
        if caption:
            result.append(caption)
        return result

    if name in {"PageBlockEmbed", "PageBlockMap"}:
        label = name.removeprefix("PageBlock")
        caption = _render_caption(getattr(block, "caption", None), state, depth)
        return [f"[{label}: {caption}]" if caption else f"[{label}]"]

    # Cover and similar wrappers contain one block; future containers generally
    # expose ``blocks``.  Prefer children before generic text extraction.
    nested_block = getattr(block, "cover", None)
    if nested_block is not None:
        return _render_block(nested_block, state, depth + 1)
    nested_blocks = getattr(block, "blocks", None)
    if nested_blocks is not None:
        return _render_blocks(nested_blocks, state, depth)

    result: list[str] = []
    for attr in ("title", "text", "author"):
        text = _clean(render_rich_text(getattr(block, attr, None), state, depth + 1))
        if text and text not in result:
            result.append(text)
    caption = _render_caption(getattr(block, "caption", None), state, depth)
    if caption and caption not in result:
        result.append(caption)
    return result


def render_cached_page(page: Any) -> str:
    """Render a raw ``Page`` with deterministic limits and whitespace."""
    if page is None:
        return ""
    state = _RenderState()
    parts = _render_blocks(getattr(page, "blocks", None), state, 0)
    text = "\n".join(part for part in parts if part)
    if len(text) > MAX_RICH_PAGE_CHARS:
        text = text[:MAX_RICH_PAGE_CHARS].rstrip()
        state.truncated = True
    if state.truncated:
        text = f"{text}\n{TRUNCATED_MARKER}" if text else TRUNCATED_MARKER
    return text
