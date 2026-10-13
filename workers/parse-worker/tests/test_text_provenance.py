from types import SimpleNamespace

from pydantic import ValidationError
import pytest

from app.models import PageSource, ParsedElement
from app.ocr_pipeline import SelectiveOcrModel
from app.serializer import serialize_document
from conftest import FakeConverter, FakeDocument, FakeTable, enum, item, params
from test_ocr_pipeline import cell, page


METADATA = {key: value for key, value in params().items() if key != "file_name"}


def box(left=10.0, top=20.0, right=150.0, bottom=70.0, *, origin="TOPLEFT"):
    return SimpleNamespace(l=left, t=top, r=right, b=bottom, coord_origin=enum(origin))


def cluster(cells, bbox=None, children=()):
    return SimpleNamespace(cells=list(cells), bbox=bbox or box(), children=list(children), confidence=1.0)


def provenance(number=1, bbox=None):
    return SimpleNamespace(page_no=number, bbox=bbox or box(top=80.0, bottom=30.0, origin="BOTTOMLEFT"))


def conversion(rows, clusters, *, heights=None):
    heights = heights or {1: 100.0}
    document = FakeDocument(rows, {number: SimpleNamespace(size=SimpleNamespace(height=height))
        for number, height in heights.items()})
    return SimpleNamespace(document=document, assembled=SimpleNamespace(elements=[
        SimpleNamespace(page_no=number, cluster=value) for number, value in clusters]))


def serialize(result, **kwargs):
    return serialize_document(result.document, METADATA, kwargs.pop("max_chars", 20_000_000),
        conversion_result=result, **kwargs)


def test_exact_bottomleft_provenance_associates_real_ocr_scores_not_layout_default():
    result = conversion([item(text="真实识别的两行", prov=[provenance()])],
        [(1, cluster([cell("识别甲", ocr=True, score=0.2), cell("识别乙", ocr=True, score=0.8)]))])
    parsed = serialize(result)
    assert parsed.elements[0].text_origin == "OCR" and parsed.elements[0].ocr_confidence == 0.5
    assert parsed.elements[0].bbox.top == 80.0 and parsed.elements[0].bbox.bottom == 30.0
    assert parsed.page_sources == []


def test_native_element_does_not_expose_pdf_default_confidence_as_ocr_confidence():
    result = conversion([item(prov=[provenance()])], [(1, cluster([cell("真实原生文本")]))])
    parsed = serialize(result)
    assert parsed.elements[0].text_origin == "TEXT_LAYER" and parsed.elements[0].ocr_confidence is None


def test_mixed_page_does_not_change_native_element_or_falsely_label_a_mixed_element():
    native, recognized = cell("页码1"), cell("真实识别正文", ocr=True, score=0.73)
    native_box = box(left=20.0, top=5.0, right=60.0, bottom=10.0)
    rows = [item(text="页码1", prov=[provenance(bbox=box(left=20.0, top=95.0, right=60.0,
        bottom=90.0, origin="BOTTOMLEFT"))]), item(text="页码1 真实识别正文", prov=[provenance()])]
    result = conversion(rows, [(1, cluster([native], native_box)), (1, cluster([native, recognized]))])
    def engine(_result, pages):
        for current in pages:
            current.cells.append(recognized)
            yield current
    list(SelectiveOcrModel(lambda: engine)(result, [page(1, [native])]))
    parsed = serialize(result)
    assert parsed.page_sources[0].text_origin == "OCR" and parsed.page_sources[0].ocr_confidence == 0.73
    assert parsed.elements[0].text_origin == "TEXT_LAYER" and parsed.elements[0].ocr_confidence is None
    assert parsed.elements[1].text_origin is None and parsed.elements[1].ocr_confidence is None
    assert any("元素混合" in warning for warning in parsed.warnings)


def test_page_identity_is_part_of_matching_and_merged_elements_use_every_real_provenance():
    result = conversion([item(text="跨页真实识别正文", prov=[provenance(1), provenance(2)])],
        [(1, cluster([cell("甲", ocr=True, score=0.4)])), (2, cluster([cell("乙", ocr=True, score=0.8)]))],
        heights={1: 100.0, 2: 100.0})
    parsed = serialize(result)
    assert parsed.elements[0].page == 1 and parsed.elements[0].text_origin == "OCR"
    assert parsed.elements[0].ocr_confidence == pytest.approx(0.6)
    native_second = conversion([item(text="跨页混合正文", prov=[provenance(1), provenance(2)])],
        [(1, cluster([cell("甲", ocr=True, score=0.4)])), (2, cluster([cell("乙")]))], heights={1: 100.0, 2: 100.0})
    assert serialize(native_second).elements[0].text_origin is None


def test_table_formula_and_caption_keep_atomic_content_and_real_text_origin():
    table = FakeTable(prov=[provenance()])
    formula_box = box(left=40.0, top=70.0, right=150.0, bottom=80.0)
    caption_box = box(left=10.0, top=81.0, right=150.0, bottom=90.0)
    formula = item("formula", "", orig="E=mc^2", prov=[provenance(bbox=box(left=40.0, top=30.0,
        right=150.0, bottom=20.0, origin="BOTTOMLEFT"))])
    caption = item("caption", "真实图注", prov=[provenance(bbox=box(top=19.0, bottom=10.0, origin="BOTTOMLEFT"))])
    result = conversion([table, formula, caption], [(1, cluster([cell("原文120%", ocr=True, score=0.7)])),
        (1, cluster([cell("E=mc^2", ocr=True, score=0.5)], formula_box)),
        (1, cluster([cell("真实图注")], caption_box))])
    parsed = serialize(result)
    assert parsed.markdown == table.text + "\n\n$$\nE=mc^2\n$$\n\n真实图注"
    assert [element.text_origin for element in parsed.elements] == ["OCR", "OCR", "TEXT_LAYER"]
    assert [element.ocr_confidence for element in parsed.elements] == [0.7, 0.5, None]
    limited = serialize(result, max_chars=len(table.text) + 2)
    assert limited.markdown == table.text and limited.truncated is True


@pytest.mark.parametrize("score", [None, float("nan"), float("inf"), -0.2, 1.2, True])
def test_unknown_or_invalid_element_ocr_confidence_is_never_replaced_with_one(score):
    result = conversion([item(prov=[provenance()])], [(1, cluster([cell("原文", ocr=True, score=score)]))])
    parsed = serialize(result)
    assert parsed.elements[0].text_origin == "OCR" and parsed.elements[0].ocr_confidence is None
    assert any("未补造分数" in warning for warning in parsed.warnings)


def test_unmatched_rich_table_position_keeps_content_but_source_unknown():
    table = FakeTable(prov=[provenance(bbox=box(left=12.0, top=80.0, right=150.0, bottom=30.0, origin="BOTTOMLEFT"))])
    result = conversion([table], [(1, cluster([cell("真实OCR", ocr=True, score=0.9)]))])
    parsed = serialize(result)
    assert parsed.markdown == table.text
    assert parsed.elements[0].text_origin is None and parsed.elements[0].ocr_confidence is None
    assert any("无法" in warning for warning in parsed.warnings)


@pytest.mark.parametrize("flag", [None, "true", 1])
def test_non_boolean_source_flag_is_not_interpreted_as_ocr(flag):
    result = conversion([item(prov=[provenance()])], [(1, cluster([cell("原文", ocr=flag, score=0.9)]))])
    assert serialize(result).elements[0].text_origin is None


def test_child_cluster_can_be_associated_without_losing_its_true_native_source():
    child = cluster([cell("真实图注")])
    parent = cluster([], box(left=0.0, top=0.0, right=160.0, bottom=100.0), children=[child])
    parsed = serialize(conversion([item("caption", "真实图注", prov=[provenance()])], [(1, parent)]))
    assert parsed.elements[0].text_origin == "TEXT_LAYER"


def test_duplicate_real_bounding_boxes_with_different_cells_are_ambiguous():
    result = conversion([item(prov=[provenance()])], [(1, cluster([cell("原生正文")])),
        (1, cluster([cell("识别正文", ocr=True, score=0.9)]))])
    parsed = serialize(result)
    assert parsed.elements[0].text_origin is None and parsed.elements[0].ocr_confidence is None
    assert any("无法" in warning for warning in parsed.warnings)


def test_service_returns_real_audit_and_element_provenance_in_the_http_response(make_app):
    from test_parse_api import post
    recognized = cell("真实OCR正文", ocr=True, score=0.67)
    prepared = conversion([item(text="真实OCR正文", prov=[provenance()])], [(1, cluster([recognized]))])
    class ProvenanceConverter(FakeConverter):
        def convert(self, *args, **kwargs):
            result = super().convert(*args, **kwargs)
            result.assembled = prepared.assembled
            def engine(_result, pages):
                for current in pages:
                    current.cells.append(recognized)
                    yield current
            list(SelectiveOcrModel(lambda: engine)(result, [page(1)]))
            return result
    app, _ = make_app(ProvenanceConverter(prepared.document))
    response = post(app)
    assert response.status_code == 200
    body = response.json()
    assert body["elements"][0]["text_origin"] == "OCR"
    assert body["elements"][0]["ocr_confidence"] == 0.67
    assert body["page_sources"] == [{"page": 1, "text_origin": "OCR", "ocr_confidence": 0.67,
        "native_char_count": 0, "has_images": True, "ocr_attempted": True}]


@pytest.mark.parametrize("values", [{"page": 0, "ocr_attempted": False},
    {"page": "1", "ocr_attempted": False}, {"page": 1, "ocr_attempted": "true"},
    {"page": 1, "ocr_attempted": False, "extra": 1},
    {"page": 1, "ocr_attempted": False, "text_origin": "TEXT_LAYER", "ocr_confidence": 1.0},
    {"page": 1, "ocr_attempted": True, "text_origin": "OCR", "ocr_confidence": float("nan")}])
def test_page_source_schema_refuses_fake_values(values):
    with pytest.raises(ValidationError):
        PageSource.model_validate(values)


def test_element_schema_does_not_allow_confidence_without_an_ocr_source():
    with pytest.raises(ValidationError):
        ParsedElement(element_type="TEXT", text="原文", text_origin="TEXT_LAYER", ocr_confidence=1.0)
