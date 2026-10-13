"""章节隔离、并发结算、独立引用与局部一致性调整的回归。"""

from copy import deepcopy
import json
import threading
import time

import pytest

from app.node_executor import NodeExecutionError
from app.sectioned_runtime import (
    check_document_consistency, cite_sections, document_content_digest, paragraph_texts,
    plan_outline, validate_chapter_plan, write_sections,
)


def inputs(count=3):
    chapters = [{"section_id": f"section-{i}", "heading": f"章节{i}", "key_points": [f"要点{i}"],
        "chunk_ids": [f"chunk-{i}"]} for i in range(count)]
    materials = [{"chunk_id": f"chunk-{i}", "title": f"资料{i}", "content": f"真实材料{i}。隐藏后文{i}。"}
                 for i in range(count)]
    return {"chapter_plan": chapters, "material_passages": materials,
        "section_materials": [{"heading": f"章节{i}", "passages": [{"chunk_id": f"chunk-{i}",
            "title": f"资料{i}", "selected_chars": len(f"真实材料{i}。"), "truncated": True}]} for i in range(count)],
        "generation_context": {"outline": [chapter["heading"] for chapter in chapters],
            "goal": "按资料解释主题", "output_contract": {"citation": {"density": "PER_SECTION"}}}}


class Model:
    def __init__(self, factory=None):
        self.calls, self.active, self.max_active = [], 0, 0
        self.lock, self.local = threading.Lock(), threading.local()
        self.factory = factory
        self.review_count = 0

    def current_thread_call_count(self):
        return getattr(self.local, "calls", 0)

    def complete_json(self, purpose, payload):
        with self.lock:
            self.calls.append((purpose, deepcopy(payload)))
            self.active += 1
            self.max_active = max(self.active, self.max_active)
        self.local.calls = self.current_thread_call_count() + 1
        try:
            if self.factory:
                value = self.factory(purpose, payload)
            else:
                value = self.default(purpose, payload)
            return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
        finally:
            with self.lock:
                self.active -= 1

    def default(self, purpose, payload):
        projected = payload["inputs"]
        if purpose == "artifact.node.outline_planner":
            return {"chapter_plan": [{"section_id": f"section-{i}", "heading": heading,
                "key_points": ["依据资料提炼要点"], "chunk_ids": [pack["passages"][0]["chunk_id"]]}
                for i, (heading, pack) in enumerate(zip(projected["generation_context"]["outline"], projected["section_materials"]))]}
        if purpose == "artifact.node.section_writer":
            chapter = projected["chapter"]
            time.sleep(0.04 if chapter["section_id"] == "section-0" else 0.015)
            return {"section": {"heading": chapter["heading"], "body": projected["material_passages"][0]["content"],
                                "source_refs": []}}
        if purpose == "artifact.node.section_citer":
            return {"citation_assignment": {"heading": projected["section"]["heading"],
                "chunk_ids": [projected["material_passages"][0]["chunk_id"]]}}
        if purpose == "artifact.node.document_consistency":
            return {"issues": []}
        raise AssertionError("测试未声明该模型步骤")


def test_outline_has_fixed_order_real_chapter_chunks_and_schema_retry():
    model = Model()
    source = inputs()
    first = True
    def factory(purpose, payload):
        nonlocal first
        value = model.default(purpose, payload)
        if first:
            first = False
            value["chapter_plan"][0]["chunk_ids"] = ["其他章的片段"]
        return value
    model.factory = factory
    result = plan_outline(source, model)
    assert [c["heading"] for c in result.output["chapter_plan"]] == source["generation_context"]["outline"]
    assert result.trace["llm_calls"] == 2
    assert "未分配的片段" in model.calls[1][1]["validation_errors"][0]


@pytest.mark.parametrize("change", [
    lambda plan: plan.reverse(),
    lambda plan: plan[1].update(section_id=plan[0]["section_id"]),
    lambda plan: plan[0].update(chunk_ids=["chunk-1"]),
    lambda plan: plan[0].update(chunk_ids=[]),
])
def test_outline_rejects_reordered_missing_or_cross_chapter_material(change):
    source = inputs()
    plan = deepcopy(source["chapter_plan"])
    change(plan)
    with pytest.raises(ValueError):
        validate_chapter_plan({"chapter_plan": plan}, source)


def test_parallel_writers_isolate_chapter_material_budget_and_return_outline_order():
    source = inputs()
    model = Model()
    original = deepcopy(source)
    result = write_sections(source, model, max_workers=2)
    assert model.max_active == 2
    assert result.trace["llm_calls"] == len(model.calls) == 3
    assert result.trace["used_workers"] == 2
    assert [section["heading"] for section in result.output["sections"]] == source["generation_context"]["outline"]
    for _, payload in model.calls:
        projected = payload["inputs"]
        number = projected["chapter"]["section_id"].removeprefix("section-")
        assert projected["material_passages"] == [{"chunk_id": f"chunk-{number}", "title": f"资料{number}",
            "content": f"真实材料{number}。"}]
        assert "隐藏后文" not in json.dumps(projected, ensure_ascii=False)
    assert source == original and all(s["source_refs"] == [] for s in result.output["sections"])


def test_unknown_thread_accounting_client_falls_back_to_serial_with_reason():
    base = Model()
    class UnknownClient:
        calls = base.calls
        def complete_json(self, purpose, payload):
            return base.complete_json(purpose, payload)
    result = write_sections(inputs(), UnknownClient(), max_workers=3)
    assert base.max_active == 1 and result.trace["used_workers"] == 1
    assert result.trace["llm_calls"] == len(base.calls) == 3
    assert result.trace["degradation_reasons"] == ["ARTIFACT_SECTIONED_THREAD_CALL_ACCOUNTING_UNAVAILABLE"]


def test_writer_failure_stops_new_submissions_and_waits_started_request():
    model = Model()
    second_started, second_finished = threading.Event(), threading.Event()
    def factory(purpose, payload):
        chapter = payload["inputs"]["chapter"]
        if chapter["section_id"] == "section-0":
            assert second_started.wait(2)
            return ""
        if chapter["section_id"] == "section-1":
            second_started.set()
            time.sleep(0.04)
            second_finished.set()
        return model.default(purpose, payload)
    model.factory = factory
    with pytest.raises(NodeExecutionError) as caught:
        write_sections(inputs(5), model, max_workers=2)
    assert second_finished.is_set() and model.active == 0
    assert len(model.calls) == caught.value.trace["llm_calls"] == 2
    assert caught.value.trace["failed_sections"] == ["章节0"]
    assert all(payload["inputs"]["chapter"]["section_id"] in {"section-0", "section-1"}
               for _, payload in model.calls)


def test_writer_invalid_heading_retries_once_then_fails_without_partial_result():
    model = Model(factory=lambda purpose, payload: {"section": {"heading": "其他章节", "body": "正文", "source_refs": []}})
    with pytest.raises(NodeExecutionError) as caught:
        write_sections(inputs(1), model)
    assert caught.value.trace["llm_calls"] == 2
    assert len(model.calls) == 2 and model.calls[1][1]["validation_errors"]


def test_cite_fills_actual_titles_and_chunk_ids_without_changing_body_or_order():
    source = inputs()
    source["sections"] = [{"heading": f"章节{i}", "body": f"原始正文{i}，保留换行\n及空白。", "source_refs": []} for i in range(3)]
    model = Model()
    before = deepcopy(source)
    result = cite_sections(source, model, max_workers=2)
    assert result.trace["llm_calls"] == 3
    assert [s["body"] for s in result.output["sections"]] == [s["body"] for s in before["sections"]]
    assert result.output["citation_assignments"] == [{"heading": f"章节{i}", "chunk_ids": [f"chunk-{i}"]} for i in range(3)]
    assert [s["source_refs"] for s in result.output["sections"]] == [[f"资料{i}"] for i in range(3)]
    assert all(set(payload["inputs"]) == {"section", "material_passages"} for _, payload in model.calls)
    assert source == before


def test_cite_accepts_repair_subset_and_does_not_touch_passed_chapters():
    source = inputs()
    source["sections"] = [{"heading": "章节1", "body": "仅此章改写后正文。", "source_refs": []}]
    source["chapter_plan"] = source["chapter_plan"][1:2]
    result = cite_sections(source, Model())
    assert len(result.output["sections"]) == 1 and result.output["sections"][0]["heading"] == "章节1"
    assert result.output["citation_assignments"] == [{"heading": "章节1", "chunk_ids": ["chunk-1"]}]


@pytest.mark.parametrize("change", [
    lambda value: value["citation_assignment"].update(chunk_ids=["chunk-2"]),
    lambda value: value.update(section={"heading": "章节0", "body": "偷偷改正文", "source_refs": []}),
])
def test_cite_rejects_cross_chapter_ids_and_attempted_body_rewrite(change):
    source = inputs(2)
    source["sections"] = [{"heading": "章节0", "body": "原正文。", "source_refs": []}]
    model = Model()
    def factory(purpose, payload):
        value = model.default(purpose, payload)
        change(value)
        return value
    model.factory = factory
    with pytest.raises(NodeExecutionError) as caught:
        cite_sections(source, model)
    assert caught.value.trace["llm_calls"] == 2


def consistency_inputs():
    return {"generation_context": {"outline": ["章节0", "章节1"]}, "sections": [
        {"heading": "章节0", "body": "第一段保留。\n \n问题段落。\n\n第三段保留。", "source_refs": ["资料0"]},
        {"heading": "章节1", "body": "其他章逐字保留。", "source_refs": ["资料1"]}]}


def test_consistency_changes_only_problem_paragraph_and_rechecks_updated_document():
    model = Model()
    checks = 0
    def factory(purpose, payload):
        nonlocal checks
        if purpose == "artifact.node.document_consistency":
            checks += 1
            return {"issues": [{"section_index": 0, "paragraph_index": 1,
                "issue_kind": "TERM_INCONSISTENCY", "detail": "统一术语"}]} if checks == 1 else {"issues": []}
        assert purpose == "artifact.node.paragraph_reviser"
        assert len(payload["inputs"]["problem_paragraphs"]) == 1
        return {"patches": [{"section_index": 0, "paragraph_index": 1, "text": "修正段落。"}]}
    model.factory = factory
    source = consistency_inputs()
    before = deepcopy(source)
    result = check_document_consistency(source, model)
    assert result.output["sections"][0]["body"] == "第一段保留。\n \n修正段落。\n\n第三段保留。"
    assert result.output["sections"][1] == before["sections"][1]
    assert result.output["sections"][0]["source_refs"] == []
    trace = result.output["consistency_trace"]
    assert trace["status"] == "PASS" and trace["revised_paragraphs"] == [{"section_index": 0, "paragraph_index": 1}]
    assert trace["content_digest"] == trace["checked_content_digest"] == document_content_digest(result.output["sections"])
    assert trace["before_content_digest"] == document_content_digest(before["sections"])
    assert result.trace["llm_calls"] == 3 and checks == 2
    assert source == before
    assert paragraph_texts(before["sections"][0]["body"]) == ["第一段保留。", "问题段落。", "第三段保留。"]


def test_consistency_audit_only_returns_diagnostics_and_never_revises():
    model = Model(factory=lambda purpose, payload: {"issues": [{"section_index": 0, "paragraph_index": 1,
        "issue_kind": "DUPLICATE", "detail": "重复段落"}]})
    source = consistency_inputs()
    result = check_document_consistency(source, model, repair=False)
    assert result.output["sections"] == source["sections"]
    assert result.output["consistency_trace"]["status"] == "FAIL" and result.trace["llm_calls"] == 1


@pytest.mark.parametrize("patches", [
    [{"section_index": 0, "paragraph_index": 0, "text": "改写未判问题的段落"}], [],
    [{"section_index": 0, "paragraph_index": 1, "text": "修正"}, {"section_index": 0, "paragraph_index": 1, "text": "重复修正"}],
])
def test_consistency_reviser_rejects_missing_duplicate_or_unreported_patches(patches):
    def factory(purpose, payload):
        if purpose == "artifact.node.document_consistency":
            return {"issues": [{"section_index": 0, "paragraph_index": 1, "issue_kind": "DUPLICATE", "detail": "重复"}]}
        return {"patches": patches}
    model = Model(factory)
    with pytest.raises(NodeExecutionError) as caught:
        check_document_consistency(consistency_inputs(), model)
    assert caught.value.trace["llm_calls"] == 3
    assert model.calls[-1][1]["validation_errors"]


def test_content_digest_ignores_reference_updates_but_detects_real_body_change():
    source = consistency_inputs()["sections"]
    same_body = deepcopy(source)
    same_body[0]["source_refs"] = ["另一引用"]
    assert document_content_digest(source) == document_content_digest(same_body)
    same_body[0]["body"] += "新正文"
    assert document_content_digest(source) != document_content_digest(same_body)


def test_unchanged_paragraph_patch_does_not_clear_true_references_or_claim_revision():
    checks = 0
    def factory(purpose, payload):
        nonlocal checks
        if purpose == "artifact.node.document_consistency":
            checks += 1
            return {"issues": [{"section_index": 0, "paragraph_index": 1,
                "issue_kind": "TERM_INCONSISTENCY", "detail": "检查术语"}]} if checks == 1 else {"issues": []}
        return {"patches": [{"section_index": 0, "paragraph_index": 1, "text": "问题段落。"}]}
    source = consistency_inputs()
    result = check_document_consistency(source, Model(factory))
    assert result.output["sections"] == source["sections"]
    assert result.output["consistency_trace"]["revised_paragraphs"] == []


def test_consistency_rejects_unknown_paragraph_positions_after_one_retry():
    model = Model(factory=lambda purpose, payload: {"issues": [{"section_index": 0, "paragraph_index": 99,
        "issue_kind": "DUPLICATE", "detail": "并不存在的段落"}]})
    with pytest.raises(NodeExecutionError) as caught:
        check_document_consistency(consistency_inputs(), model)
    assert caught.value.trace["llm_calls"] == 2 and len(model.calls) == 2
    assert "真实且唯一" in model.calls[1][1]["validation_errors"][0]


def test_consistency_limit_after_revision_preserves_all_actual_call_counts():
    def factory(purpose, payload):
        if purpose == "artifact.node.document_consistency":
            return {"issues": [{"section_index": 0, "paragraph_index": 1,
                "issue_kind": "TERM_INCONSISTENCY", "detail": "统一术语"}]}
        return {"patches": [{"section_index": 0, "paragraph_index": 1, "text": "长" * 64_001}]}
    model = Model(factory)
    with pytest.raises(NodeExecutionError) as caught:
        check_document_consistency(consistency_inputs(), model)
    assert caught.value.trace["llm_calls"] == len(model.calls) == 2
    assert caught.value.trace["status"] == "FAILED"
