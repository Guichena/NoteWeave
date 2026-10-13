from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
import threading

from pydantic import BaseModel, ConfigDict
import pytest

from app.ocr_pipeline import SelectiveOcrModel, audit_for


def cell(text, *, ocr=False, score=1.0):
    return SimpleNamespace(text=text, orig=text, from_ocr=ocr, confidence=score)


def page(number, texts=(), *, images=True):
    return SimpleNamespace(page_no=number, cells=list(texts),
        _backend=SimpleNamespace(get_bitmap_rects=lambda: [SimpleNamespace(area=lambda: 100.0)] if images else []))


def engine_with_records():
    state = {"factories": 0, "calls": []}

    def factory():
        state["factories"] += 1

        def model(result, pages):
            for current in pages:
                state["calls"].append((result, current.page_no))
                current.cells.append(cell("识别的真实文本", ocr=True, score=0.83))
                yield current
        return model

    return factory, state


def test_selects_only_short_native_text_pages_with_real_bitmaps_and_preserves_native_text():
    factory, state = engine_with_records()
    model = SelectiveOcrModel(factory)
    result = SimpleNamespace()
    pages = [page(1, [cell("a" * 20)]), page(2), page(3, images=False), page(4, [cell("页码1")])]
    assert list(model(result, pages)) == pages
    assert state["factories"] == 1 and [number for _, number in state["calls"]] == [2, 4]
    records, warnings = audit_for(result).snapshot()
    assert [source.page for source in records] == [1, 2, 3, 4]
    assert [source.ocr_attempted for source in records] == [False, True, False, True]
    assert [source.native_char_count for source in records] == [20, 0, 0, 3]
    assert records[0].text_origin == "TEXT_LAYER" and records[0].ocr_confidence is None
    assert records[1].text_origin == "OCR" and records[1].ocr_confidence == 0.83
    assert records[2].text_origin is None and records[2].has_images is False
    assert records[3].text_origin == "OCR" and records[3].ocr_confidence == 0.83
    assert pages[3].cells[0].text == "页码1" and pages[3].cells[0].from_ocr is False
    assert any("同时含文本层与 OCR" in warning for warning in warnings)


@pytest.mark.parametrize("enabled,images,text", [(False, True, ""), (True, False, ""), (True, True, "a" * 20)])
def test_does_not_initialize_or_download_ocr_for_unselected_pages(enabled, images, text):
    factory, state = engine_with_records()
    model = SelectiveOcrModel(factory, enabled=enabled)
    result = SimpleNamespace()
    list(model(result, [page(1, [cell(text)] if text else [], images=images)]))
    assert state["factories"] == 0 and state["calls"] == []
    assert audit_for(result).snapshot()[0][0].ocr_attempted is False


def test_empty_ocr_result_is_not_marked_as_ocr_and_does_not_invent_confidence():
    model = SelectiveOcrModel(lambda: lambda _result, pages: iter(pages))
    result = SimpleNamespace()
    list(model(result, [page(1)]))
    source = audit_for(result).snapshot()[0][0]
    assert source.ocr_attempted is True and source.text_origin is None and source.ocr_confidence is None


@pytest.mark.parametrize("score", [None, float("nan"), float("inf"), -0.1, 1.1, True])
def test_missing_or_invalid_true_ocr_score_remains_null(score):
    def engine(_result, pages):
        for current in pages:
            current.cells.append(cell("真实识别文本", ocr=True, score=score))
            yield current
    result = SimpleNamespace()
    list(SelectiveOcrModel(lambda: engine)(result, [page(1)]))
    source = audit_for(result).snapshot()[0][0]
    assert source.text_origin == "OCR" and source.ocr_confidence is None
    assert any("未补造分数" in warning for warning in audit_for(result).snapshot()[1])


def test_pdf_rendering_alone_does_not_count_as_an_embedded_image():
    factory, state = engine_with_records()
    current = page(1, images=False)
    current.image = object()
    result = SimpleNamespace()
    list(SelectiveOcrModel(factory)(result, [current]))
    assert state["factories"] == 0
    assert audit_for(result).snapshot()[0][0].has_images is False


def test_missing_bitmap_api_is_unknown_and_does_not_start_ocr():
    factory, state = engine_with_records()
    current = page(1)
    current._backend = None
    result = SimpleNamespace()
    list(SelectiveOcrModel(factory)(result, [current]))
    assert state["factories"] == 0
    assert audit_for(result).snapshot()[0][0].has_images is None
    assert any("未提供真实位图" in warning for warning in audit_for(result).snapshot()[1])


def test_bitmap_lookup_failure_propagates_without_running_ocr():
    factory, state = engine_with_records()
    current = page(1)
    def broken_lookup():
        raise RuntimeError("PDF 位图记录读取失败")
    current._backend.get_bitmap_rects = broken_lookup
    with pytest.raises(RuntimeError, match="PDF 位图记录读取失败"):
        list(SelectiveOcrModel(factory)(SimpleNamespace(), [current]))
    assert state["factories"] == 0


@pytest.mark.parametrize("number", [0, -1, True, "1"])
def test_invalid_page_number_is_not_changed_into_page_one(number):
    result = SimpleNamespace()
    list(SelectiveOcrModel(lambda: lambda _result, pages: iter(pages), enabled=False)(result, [page(number)]))
    records, warnings = audit_for(result).snapshot()
    assert records == [] and any("未补造页面来源" in warning for warning in warnings)


def test_private_per_result_audit_is_not_part_of_sdk_public_model_schema():
    class FakeResult(BaseModel):
        model_config = ConfigDict(extra="forbid")
        status: str = "success"

    result = FakeResult()
    list(SelectiveOcrModel(lambda: None, enabled=False)(result, [page(1, [cell("原生正文")])]))
    assert result.model_dump() == {"status": "success"}
    assert set(FakeResult.model_json_schema()["properties"]) == {"status"}
    assert audit_for(result).snapshot()[0][0].text_origin == "TEXT_LAYER"


def test_internal_threads_keep_audit_state_in_their_own_document():
    barrier = threading.Barrier(2)

    def engine(result, pages):
        for current in pages:
            current.cells.append(cell(result.marker, ocr=True, score=result.score))
            yield current
    model = SelectiveOcrModel(lambda: engine)
    first, second = SimpleNamespace(marker="文档甲", score=0.3), SimpleNamespace(marker="文档乙", score=0.9)
    def run(result):
        barrier.wait(timeout=3)
        return list(model(result, [page(1)]))
    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(run, result) for result in (first, second)]
        for future in futures:
            future.result(timeout=5)
    assert audit_for(first) is not audit_for(second)
    assert audit_for(first).snapshot()[0][0].ocr_confidence == 0.3
    assert audit_for(second).snapshot()[0][0].ocr_confidence == 0.9


def test_shared_ocr_engine_stays_serial_while_the_previous_document_is_still_running():
    entered = threading.Event()
    release = threading.Event()
    second_ready = threading.Event()
    second_entered = threading.Event()
    counts_lock = threading.Lock()
    state = {"active": 0, "maximum": 0, "factory_calls": 0}
    first, second = SimpleNamespace(marker="first"), SimpleNamespace(marker="second")

    def engine(result, pages):
        with counts_lock:
            state["active"] += 1
            state["maximum"] = max(state["maximum"], state["active"])
        try:
            if result is first:
                entered.set()
                assert release.wait(timeout=3)
            else:
                second_entered.set()
            for current in pages:
                current.cells.append(cell("真实识别文本", ocr=True, score=0.8))
                yield current
        finally:
            with counts_lock:
                state["active"] -= 1

    def factory():
        state["factory_calls"] += 1
        return engine

    model = SelectiveOcrModel(factory)
    def second_run():
        # 引擎已经初始化；后一文档开始尝试进入同一个实际推理阶段。
        assert model._model() is engine
        second_ready.set()
        return list(model(second, [page(1)]))

    with ThreadPoolExecutor(max_workers=2) as executor:
        first_future = executor.submit(lambda: list(model(first, [page(1)])))
        assert entered.wait(timeout=3)
        second_future = executor.submit(second_run)
        try:
            assert second_ready.wait(timeout=3)
            assert not second_entered.wait(timeout=0.1)
            assert state["maximum"] == 1
        finally:
            release.set()
        first_future.result(timeout=3)
        second_future.result(timeout=3)
    assert second_entered.is_set() and state["maximum"] == 1 and state["factory_calls"] == 1


def test_audit_attachment_is_unique_when_multiple_stage_threads_share_one_result():
    result = SimpleNamespace()
    with ThreadPoolExecutor(max_workers=4) as executor:
        values = list(executor.map(lambda _: audit_for(result, create=True), range(20)))
    assert all(value is values[0] for value in values)


def test_ocr_failure_propagates_instead_of_returning_an_empty_success():
    def factory():
        raise RuntimeError("OCR 依赖未就绪")
    with pytest.raises(RuntimeError, match="OCR 依赖未就绪"):
        list(SelectiveOcrModel(factory)(SimpleNamespace(), [page(1)]))
