from types import SimpleNamespace

import rich_page_text
from pyrogram import raw
from rich_page_text import TRUNCATED_MARKER, render_cached_page, render_rich_text


def node(name, **kwargs):
    return type(name, (), kwargs)()


def plain(text):
    return node("TextPlain", text=text)


def caption(text, credit=""):
    return node("PageCaption", text=plain(text), credit=plain(credit))


def test_render_rich_text_preserves_concat_and_url():
    value = node(
        "TextConcat",
        texts=[
            plain("Read "),
            node("TextBold", text=plain("this")),
            node("TextUrl", text=plain(" guide"), url="https://example.test"),
        ],
    )

    assert render_rich_text(value) == "Read this guide (https://example.test)"


def test_render_rich_text_handles_empty_inline_and_future_wrappers():
    assert render_rich_text(None) == ""
    assert render_rich_text("plain") == "plain"
    assert render_rich_text(node("TextEmpty")) == ""
    assert render_rich_text(node("TextImage", document_id=1)) == "[Image]"
    assert render_rich_text(node("TextBold", text=plain("bold"))) == "bold"
    assert render_rich_text(node("FutureRichText", title=plain("future"))) == "future"
    assert render_rich_text(node("UnknownRichText")) == ""


def test_render_rich_text_does_not_duplicate_visible_link_target():
    value = node(
        "TextUrl",
        text=plain("https://example.test"),
        url="https://example.test",
    )

    assert render_rich_text(value) == "https://example.test"


def test_render_page_keeps_block_order_details_photo_and_table():
    page = SimpleNamespace(
        blocks=[
            node("PageBlockTitle", text=plain("Release notes")),
            node("PageBlockParagraph", text=plain("Before image")),
            node("PageBlockPhoto", caption=caption("Diagram")),
            node("PageBlockParagraph", text=plain("After image")),
            node(
                "PageBlockTable",
                title=plain("Compatibility"),
                rows=[
                    SimpleNamespace(cells=[SimpleNamespace(text=plain("Client")), SimpleNamespace(text=plain("Status"))]),
                    SimpleNamespace(cells=[SimpleNamespace(text=plain("Desktop")), SimpleNamespace(text=plain("OK"))]),
                ],
            ),
            node(
                "PageBlockDetails",
                title=plain("More"),
                blocks=[node("PageBlockParagraph", text=plain("Hidden details"))],
            ),
        ]
    )

    assert render_cached_page(page).splitlines() == [
        "Release notes",
        "Before image",
        "[Photo: Diagram]",
        "After image",
        "Compatibility",
        "Client | Status",
        "Desktop | OK",
        "More",
        "Hidden details",
    ]


def test_unknown_container_keeps_known_children():
    page = SimpleNamespace(
        blocks=[
            node(
                "PageBlockFutureContainer",
                blocks=[node("PageBlockParagraph", text=plain("Still visible"))],
            )
        ]
    )

    assert render_cached_page(page) == "Still visible"


def test_lists_media_wrappers_and_generic_blocks():
    page = SimpleNamespace(
        blocks=[
            node(
                "PageBlockList",
                items=[
                    node("PageListItemText", text=plain("Bullet")),
                    node(
                        "PageListItemBlocks",
                        blocks=[node("PageBlockParagraph", text=plain("Nested"))],
                    ),
                ],
            ),
            node(
                "PageBlockOrderedList",
                items=[node("PageListOrderedItemText", num="A.", text=plain("Ordered"))],
            ),
            node("PageBlockVideo", caption=caption("")),
            node("PageBlockEmbed", caption=caption("Embed caption")),
            node("PageBlockMap", caption=caption("")),
            node(
                "PageBlockCover",
                cover=node("PageBlockAudio", caption=caption("Audio caption")),
            ),
            node(
                "PageBlockFutureLeaf",
                title=plain("Title"),
                text=plain("Text"),
                author=plain("Author"),
                caption=caption("Caption"),
            ),
        ]
    )

    assert render_cached_page(page).splitlines() == [
        "- Bullet",
        "- Nested",
        "A. Ordered",
        "[Video]",
        "[Embed: Embed caption]",
        "[Map]",
        "[Audio: Audio caption]",
        "Title",
        "Text",
        "Author",
        "Caption",
    ]


def test_ignored_blocks_and_empty_page():
    page = SimpleNamespace(
        blocks=[
            node("PageBlockUnsupported"),
            node("PageBlockAnchor"),
            node("PageBlockDivider"),
            node("PageBlockRelatedArticles"),
            node("PageBlockChannel"),
            None,
        ]
    )

    assert render_cached_page(None) == ""
    assert render_cached_page(page) == ""


def test_container_captions_are_not_lost_but_surrounding_widgets_are_ignored():
    page = SimpleNamespace(
        blocks=[
            node(
                "PageBlockCollage",
                items=[node("PageBlockPhoto", caption=caption("First"))],
                caption=caption("Gallery caption"),
            ),
            node(
                "PageBlockEmbedPost",
                author="Author",
                blocks=[node("PageBlockParagraph", text=plain("Embedded text"))],
                caption=caption("Embedded caption"),
            ),
            node(
                "PageBlockRelatedArticles",
                title=plain("Related"),
                articles=[
                    SimpleNamespace(
                        title="Next article",
                        description="Summary",
                        url="https://example.test/next",
                    )
                ],
            ),
            node(
                "PageBlockChannel",
                channel=SimpleNamespace(title="News", username="news_channel"),
            ),
        ]
    )

    assert render_cached_page(page).splitlines() == [
        "[Photo: First]",
        "Gallery caption",
        "Author",
        "Embedded text",
        "Embedded caption",
    ]


def test_renderer_has_depth_and_size_guards(monkeypatch):
    monkeypatch.setattr(rich_page_text, "MAX_RICH_PAGE_CHARS", 5)
    page = SimpleNamespace(blocks=[node("PageBlockParagraph", text=plain("123456789"))])

    assert render_cached_page(page) == f"12345\n{TRUNCATED_MARKER}"


def test_renderer_has_node_guard(monkeypatch):
    monkeypatch.setattr(rich_page_text, "MAX_RICH_PAGE_NODES", 1)
    page = SimpleNamespace(blocks=[node("PageBlockParagraph", text=plain("hidden"))])

    assert render_cached_page(page) == TRUNCATED_MARKER


def test_real_pyrogram_raw_constructors_are_supported():
    empty = raw.types.TextEmpty()
    page = raw.types.Page(
        url="https://example.test/article",
        photos=[],
        documents=[],
        blocks=[
            raw.types.PageBlockParagraph(text=raw.types.TextPlain(text="Raw paragraph")),
            raw.types.PageBlockTable(
                title=raw.types.TextPlain(text="Raw table"),
                rows=[
                    raw.types.PageTableRow(
                        cells=[
                            raw.types.PageTableCell(text=raw.types.TextPlain(text="A")),
                            raw.types.PageTableCell(text=raw.types.TextPlain(text="B")),
                        ]
                    )
                ],
            ),
            raw.types.PageBlockPhoto(
                photo_id=1,
                caption=raw.types.PageCaption(
                    text=raw.types.TextPlain(text="Raw photo"), credit=empty
                ),
            ),
        ],
    )

    assert render_cached_page(page).splitlines() == [
        "Raw paragraph",
        "Raw table",
        "A | B",
        "[Photo: Raw photo]",
    ]
