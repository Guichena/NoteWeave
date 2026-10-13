from types import SimpleNamespace

from pydantic import ValidationError
import pytest

from app.errors import ParseFailure
from app.models import BoundingBox, ParseResponse
from app.serializer import serialize_document
from conftest import FakeDocument, FakeTable, enum, item, params, provenance


METADATA = {key: value for key, value in params().items() if key != "file_name"}


def serialize(rows, *, max_chars=20_000_000, max_elements=100_000, pages=None):
    document = FakeDocument(rows, pages)
    result = serialize_document(document, METADATA, max_chars, max_elements)
    assert document.iteration_options == [{"with_groups": False, "traverse_pictures": True}]
    return result


def test_structural_order_and_real_table_export():
    table = FakeTable(prov=[provenance(2, "BOTTOMLEFT")])
    result = serialize([item("title", "真实标题", prov=[provenance(1)]),
        item("section_header", "真实章节", level=2, prov=[provenance(2)]),
        item(text="含 https://example.org/a//b 的原文"), table,
        item("caption", "真实图注"), item("formula", "E=mc^2"),
        item("code", "print('真实代码')", code_language=enum("python")),
        item("list_item", "真实条目", marker="1."), item("footnote", "真实脚注"), item("picture", "")])
    assert [row.element_type for row in result.elements] == ["TITLE", "SECTION_HEADER", "TEXT", "TABLE",
        "CAPTION", "FORMULA", "CODE", "LIST_ITEM", "FOOTNOTE", "PICTURE"]
    assert table.exports == 1
    assert result.markdown == "\n\n".join(["# 真实标题", "## 真实章节", "含 https://example.org/a//b 的原文",
        table.text, "真实图注", "$$\nE=mc^2\n$$", "```python\nprint('真实代码')\n```", "1. 真实条目", "真实脚注", "<!-- image -->"])
    assert result.elements[0].page == 1 and result.elements[1].heading_level == 2
    assert result.elements[3].bbox.model_dump() == {"left": 10.0, "top": 70.0, "right": 150.0,
        "bottom": 20.0, "coord_origin": "BOTTOMLEFT"}
    assert result.page_count == 2 and result.truncated is False


def test_missing_locations_are_null_and_page_count_uses_real_records():
    result = serialize([item()], pages={})
    assert result.elements[0].page is None and result.elements[0].bbox is None
    assert result.elements[0].heading_level is None and result.page_count == 0
    assert result.warnings


def test_formula_with_empty_reading_order_text_preserves_original_content():
    result = serialize([item("formula", "", orig="E=mc^2", prov=[provenance(1)])])
    assert result.markdown == "$$\nE=mc^2\n$$"
    assert len(result.elements) == 1
    assert result.elements[0].text == "E=mc^2" and result.elements[0].page == 1


def test_formula_existing_text_takes_precedence_and_plain_text_does_not_use_original():
    result = serialize([item("formula", "x=y", orig="原始草稿"), item("text", "", orig="不可补入正文")])
    assert result.markdown == "$$\nx=y\n$$" and len(result.elements) == 1


@pytest.mark.parametrize("original", [None, 123, ["E=mc^2"]])
def test_formula_with_non_text_original_is_rejected(original):
    with pytest.raises(ParseFailure) as failure:
        serialize([item("formula", "", orig=original)])
    assert failure.value.code == "INVALID_DOCLING_ELEMENT"


def test_multi_page_table_keeps_complete_text_and_first_real_position():
    table = FakeTable(prov=[provenance(2), provenance(3)])
    result = serialize([table], pages={1: object(), 2: object(), 3: object()})
    assert len(result.elements) == 1 and result.elements[0].text == table.text
    assert result.elements[0].page == 2 and result.page_count == 3
    assert any("首个真实来源" in warning for warning in result.warnings)


@pytest.mark.parametrize("label,text", [("table", "完整表格不能从中间截断"),
    ("caption", "完整图注不能从中间截断"), ("formula", "a^2+b^2=c^2")])
def test_atomic_limit_keeps_prefix_and_never_partial_structure(label, text):
    atom = FakeTable(text) if label == "table" else item(label, text)
    result = serialize([item("title", "标题"), atom, item(text="不应越过被截断元素继续输出")], max_chars=8)
    assert result.truncated is True
    assert result.markdown == "# 标题"
    assert [element.element_type for element in result.elements] == ["TITLE"]
    assert any("完整元素边界" in warning for warning in result.warnings)


@pytest.mark.parametrize("label", ["text", "table", "caption", "formula"])
def test_first_atomic_element_over_limit_is_error(label):
    row = FakeTable("超长完整表格") if label == "table" else item(label, "超长完整正文")
    with pytest.raises(ParseFailure) as failure:
        serialize([row], max_chars=1)
    assert failure.value.status_code == 413 and failure.value.code == "PARSE_OUTPUT_LIMIT_EXCEEDED"


def test_exact_char_boundary_includes_whole_element():
    result = serialize([item(text="abc"), item(text="de")], max_chars=7)
    assert result.markdown == "abc\n\nde" and result.truncated is False


def test_element_limit_is_visible_atomic_truncation():
    result = serialize([item(text="第一元素"), item(text="第二元素")], max_elements=1)
    assert result.markdown == "第一元素" and len(result.elements) == 1 and result.truncated is True
    assert result.warnings


def test_table_descendant_text_is_not_duplicated_and_caption_is_preserved():
    table = FakeTable()
    parent = SimpleNamespace(cref="#/tables/0", resolve=lambda _doc: table)
    result = serialize([table, item(text="原文", parent=parent), item("caption", "表格图注", parent=parent)])
    assert [row.element_type for row in result.elements] == ["TABLE", "CAPTION"]
    assert result.markdown == table.text + "\n\n表格图注"


def test_high_heading_level_preserves_metadata_and_warns():
    result = serialize([item("section_header", "真实九级标题", level=9)])
    assert result.elements[0].heading_level == 9
    assert result.markdown == "###### 真实九级标题" and result.warnings


def test_invalid_source_metadata_stays_null():
    source = provenance(page=True)
    source.bbox.l = float("nan")
    result = serialize([item(prov=[source])])
    assert result.elements[0].page is None and result.elements[0].bbox is None
    assert len(result.warnings) == 2


@pytest.mark.parametrize("origin,top,bottom", [("TOPLEFT", 70.0, 20.0), ("BOTTOMLEFT", 20.0, 70.0)])
def test_invalid_coordinate_direction_is_not_silently_swapped(origin, top, bottom):
    source = provenance(origin=origin)
    source.bbox.t, source.bbox.b = top, bottom
    result = serialize([item(prov=[source])])
    assert result.elements[0].page == 2 and result.elements[0].bbox is None
    assert any("坐标方向无效" in warning for warning in result.warnings)


def test_document_tree_cycle_is_real_error():
    parent = SimpleNamespace(cref="#/groups/0")
    value = SimpleNamespace(label=enum("group"), parent=parent)
    parent.resolve = lambda _doc: value
    with pytest.raises(ParseFailure) as failure:
        serialize([item(parent=parent)])
    assert failure.value.code == "INVALID_DOCUMENT_TREE"


def test_picture_with_real_text_preserves_it_and_counts_towards_limit():
    result = serialize([item(text="正文"), item("picture", "图像真实文字")], max_chars=5)
    assert result.truncated is True and result.markdown == "正文"
    result = serialize([item("picture", "图像真实文字"), item(text="正文")])
    assert result.markdown == "图像真实文字\n\n正文"


@pytest.mark.parametrize("rows", [[], [item("picture", "")], [item(text="  ")]])
def test_no_text_content_is_error(rows):
    with pytest.raises(ParseFailure, match="未启用 OCR") as failure:
        serialize(rows)
    assert failure.value.code == "NO_TEXT_CONTENT"


def test_code_language_absent_does_not_invent_none():
    assert serialize([item("code", "x=1")]).markdown == "```\nx=1\n```"


def test_response_schema_refuses_unknown_fields_and_coerced_metadata():
    value = serialize([item()]).model_dump()
    with pytest.raises(ValidationError):
        ParseResponse.model_validate({**value, "fake": True})
    value["elements"][0]["page"] = "1"
    with pytest.raises(ValidationError):
        ParseResponse.model_validate(value)
    with pytest.raises(ValidationError):
        BoundingBox(left=float("inf"), top=0.0, right=1.0, bottom=1.0, coord_origin="TOPLEFT")
