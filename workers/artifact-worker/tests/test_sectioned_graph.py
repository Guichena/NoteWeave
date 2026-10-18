"""章节图的实际写作、引用分离、局部修复与等待恢复回归。"""

from copy import deepcopy
import json

import pytest

from app import runner
from app.compiler import build_execution_plan
from app.models import ArtifactTaskInput
from app.node_executor import NodeExecutionError


def task(skill="study_guide"):
    return ArtifactTaskInput.model_validate({
        "task_id": f"sectioned-{skill}", "workspace_id": "workspace", "target_id": "artifact",
        "source_scope": [
            {"source_id": "source-a", "title": "延迟报告", "sample_text": "延迟降低 20%。"},
            {"source_id": "source-b", "title": "恢复报告", "sample_text": "恢复流程按日志序号重放。"},
        ],
        "control_pack": {"pack_type": "artifact", "target_key": skill,
                         "task_neighborhood": "ARTIFACT", "forbidden_patterns": ["编造数据"]},
        "input_payload": {"skill_key": skill, "user_requirement": "依据原文说明设计及适用边界"},
    })


@pytest.mark.parametrize("skill", ["study_guide", "quiz_pack", "wiki_page"])
def test_sectioned_graph_writes_each_chapter_and_cites_without_changing_text(skill):
    provider = runner.build_default_llm_client()
    input_task = task(skill)
    plan = build_execution_plan(input_task)

    _, result = runner.run_artifact_task(input_task)

    assert plan.generation_mode == "SECTIONED"
    assert [section["heading"] for section in result.result_payload["sections"]] == plan.outline
    writer_inputs = [payload["inputs"] for purpose, payload in provider.calls
                     if purpose == "artifact.node.section_writer"]
    citation_inputs = [payload["inputs"] for purpose, payload in provider.calls
                       if purpose == "artifact.node.section_citer"]
    assert len(writer_inputs) == len(citation_inputs) == len(plan.outline)
    assert {inputs["chapter"]["heading"] for inputs in writer_inputs} == set(plan.outline)
    for inputs in writer_inputs:
        assert {passage["chunk_id"] for passage in inputs["material_passages"]} == set(inputs["chapter"]["chunk_ids"])
        assert "sections" not in inputs
    by_heading = {section["heading"]: section for section in result.result_payload["sections"]}
    for inputs in citation_inputs:
        draft = inputs["section"]
        assert draft["source_refs"] == []
        assert by_heading[draft["heading"]]["body"] == draft["body"]
        assert set(by_heading[draft["heading"]]["source_refs"]) <= {
            passage["title"] for passage in inputs["material_passages"]}
    assert result.result_payload["verification"]["status"] == "PASS"
    assert result.result_payload["generation_trace"]["llm_calls"] == sum(
        purpose.startswith("artifact.node.") for purpose, _ in provider.calls)
    assert not any(purpose == "artifact.node.paragraph_reviser" for purpose, _ in provider.calls)


class FaultProvider:
    def __init__(self, base, mode="recover"):
        self.base, self.mode = base, mode
        self.calls, self.original = [], {}

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, deepcopy(payload)))
        if purpose == "artifact.node.section_repair":
            sections = deepcopy(payload["inputs"]["failed_sections"])
            if self.mode == "recover":
                for section in sections:
                    section["body"] = "延迟降低 20%。"
            return json.dumps({"sections": sections}, ensure_ascii=False)
        raw = self.base.complete_json(purpose, payload)
        if purpose == "artifact.node.section_writer":
            output = json.loads(raw)
            section = output["section"]
            self.original[section["heading"]] = deepcopy(section)
            if section["heading"] == "学习目标":
                section["body"] = "延迟降低 99%。"
            return json.dumps(output, ensure_ascii=False)
        if purpose == "artifact.node.statement_support_judge":
            output = json.loads(raw)
            statements = {statement["statement_id"]: statement for statement in payload["inputs"]["statements"]}
            for assessment in output["assessments"]:
                if "99%" in statements[assessment["statement_id"]]["text"]:
                    assessment.update(label="UNSUPPORTED", sufficiently_supported=False, reason="原文只记载 20%")
                    for citation in assessment["citations"]:
                        citation["label"] = "UNSUPPORTED"
            return json.dumps(output, ensure_ascii=False)
        return raw


def test_sectioned_repair_preserves_passed_chapters_and_rechecks_evidence(monkeypatch):
    provider = FaultProvider(runner.build_default_llm_client())
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: provider)

    _, result = runner.run_artifact_task(task())

    rounds = result.result_payload["verification"]["repair_rounds"]
    assert len(rounds) == 1 and rounds[0]["repaired_sections"] == ["学习目标"]
    assert "99%" not in result.result_payload["markdown"]
    for section in result.result_payload["sections"]:
        if section["heading"] != "学习目标":
            assert section["body"] == provider.original[section["heading"]]["body"]
    repairs = [payload["inputs"] for purpose, payload in provider.calls if purpose == "artifact.node.section_repair"]
    assert len(repairs) == 1 and [section["heading"] for section in repairs[0]["failed_sections"]] == ["学习目标"]
    assert len([purpose for purpose, _ in provider.calls if purpose == "artifact.node.section_writer"]) == 3
    assert result.result_payload["verification"]["evidence_status"] == "PASS"
    assert result.result_payload["generation_trace"]["llm_calls"] == sum(
        purpose.startswith("artifact.node.") for purpose, _ in provider.calls)


def test_sectioned_two_failed_repairs_are_delivered_with_the_verification(monkeypatch):
    provider = FaultProvider(runner.build_default_llm_client(), mode="unchanged")
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: provider)

    _, result = runner.run_artifact_task(task())
    state = result.result_payload["generation_trace"]["state"]
    # 修复轮次用完后剩下的证据问题不再让任务失败，产物带着校验结果交付
    assert state["verification"]["delivery"] == "DELIVERED_WITH_UNRESOLVED_CHECKS"

    assert len(state["repair_trace"]["rounds"]) == 2
    assert len([purpose for purpose, _ in provider.calls if purpose == "artifact.node.section_repair"]) == 2
    assert state["verification"]["evidence_status"] == "FAIL"


def test_sectioned_consistency_patch_changes_only_identified_paragraph(monkeypatch):
    base = runner.build_default_llm_client()
    calls, snapshots = [], []
    class ConsistencyProvider:
        def complete_json(self, purpose, payload):
            calls.append((purpose, deepcopy(payload)))
            if purpose == "artifact.node.document_consistency":
                paragraphs = payload["inputs"]["paragraphs"]
                if not snapshots:
                    snapshots.extend(deepcopy(paragraphs))
                first = paragraphs[0]
                issues = [] if first["text"] == "延迟降低 20%。" else [{
                    "section_index": first["section_index"], "paragraph_index": first["paragraph_index"],
                    "issue_kind": "TERM_INCONSISTENCY", "detail": "该段落需要统一表述。"}]
                return json.dumps({"issues": issues}, ensure_ascii=False)
            if purpose == "artifact.node.paragraph_reviser":
                return json.dumps({"patches": [{"section_index": paragraph["section_index"],
                    "paragraph_index": paragraph["paragraph_index"], "text": "延迟降低 20%。"}
                    for paragraph in payload["inputs"]["problem_paragraphs"]]}, ensure_ascii=False)
            return base.complete_json(purpose, payload)
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: ConsistencyProvider())

    _, result = runner.run_artifact_task(task())

    from app.sectioned_runtime import paragraph_texts
    paragraphs = [(section_index, paragraph_index, text)
                  for section_index, section in enumerate(result.result_payload["sections"])
                  for paragraph_index, text in enumerate(paragraph_texts(section["body"]))]
    for index, paragraph in enumerate(snapshots):
        expected = "延迟降低 20%。" if index == 0 else paragraph["text"]
        assert paragraphs[index] == (paragraph["section_index"], paragraph["paragraph_index"], expected)
    assert len([purpose for purpose, _ in calls if purpose == "artifact.node.paragraph_reviser"]) == 1
    assert len([purpose for purpose, _ in calls if purpose == "artifact.node.document_consistency"]) == 2
    assert result.result_payload["verification"]["status"] == "PASS"


def test_sectioned_unknown_citation_is_retried_and_never_published(monkeypatch):
    base = runner.build_default_llm_client()
    calls = []
    class UnknownCitationProvider:
        def complete_json(self, purpose, payload):
            calls.append((purpose, deepcopy(payload)))
            raw = base.complete_json(purpose, payload)
            if purpose == "artifact.node.section_citer" and payload["inputs"]["section"]["heading"] == "学习目标":
                output = json.loads(raw)
                output["citation_assignment"]["chunk_ids"] = ["unknown-chunk"]
                return json.dumps(output, ensure_ascii=False)
            return raw
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: UnknownCitationProvider())
    def forbidden(*args, **kwargs):
        raise AssertionError("未知引用不能发布")
    monkeypatch.setattr(runner, "commit_artifact_result", forbidden)
    monkeypatch.setattr(runner, "export_artifact_if_required", forbidden)

    with pytest.raises(NodeExecutionError):
        runner.run_artifact_task(task())

    invalid_calls = [payload for purpose, payload in calls if purpose == "artifact.node.section_citer"
                     and payload["inputs"]["section"]["heading"] == "学习目标"]
    assert len(invalid_calls) == 2
    assert invalid_calls[1]["validation_errors"]


def test_sectioned_empty_writer_response_is_not_replaced_by_successful_fixture(monkeypatch):
    base = runner.build_default_llm_client()
    calls = []
    class EmptyWriterProvider:
        def complete_json(self, purpose, payload):
            calls.append((purpose, deepcopy(payload)))
            raw = base.complete_json(purpose, payload)
            if purpose == "artifact.node.section_writer" and payload["inputs"]["chapter"]["heading"] == "学习目标":
                return ""
            return raw
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: EmptyWriterProvider())
    def forbidden(*args, **kwargs):
        raise AssertionError("章节生成未完成不能导出")
    monkeypatch.setattr(runner, "export_artifact_if_required", forbidden)

    with pytest.raises(NodeExecutionError):
        runner.run_artifact_task(task())

    writer_calls = [payload for purpose, payload in calls if purpose == "artifact.node.section_writer"
                    and payload["inputs"]["chapter"]["heading"] == "学习目标"]
    assert len(writer_calls) == 1
    assert not any(purpose == "artifact.node.section_citer" for purpose, _ in calls)


def test_sectioned_waiting_resume_keeps_frozen_intent_and_does_not_recompile(monkeypatch):
    from app.capability_provider import reset_capability_provider_status, set_capability_provider_status
    from app.capability_wait_queue import clear_waiting_tasks, get_waiting_task, wake_waiting_task
    from tests.test_runner import _build_mixed_context_task_input
    clear_waiting_tasks()
    reset_capability_provider_status()
    try:
        set_capability_provider_status("READ_WEB_PAGE", "UNAVAILABLE")
        _, waiting = runner.run_artifact_task(_build_mixed_context_task_input())
        stored = get_waiting_task(waiting.job_snapshot.task_id)
        assert stored is not None
        frozen_plan = deepcopy(stored["resume_checkpoint"]["execution_plan"])
        assert frozen_plan["generation_mode"] == "SECTIONED"
        def reject_recompile(*args, **kwargs):
            raise AssertionError("章节恢复不能重编已冻结计划")
        monkeypatch.setattr(runner, "build_execution_plan", reject_recompile)
        set_capability_provider_status("READ_WEB_PAGE", "AVAILABLE")

        _, result = wake_waiting_task(waiting.job_snapshot.task_id)

        assert result.job_snapshot.status == "COMPLETED"
        assert result.result_payload["execution_plan"]["execution_spec"] == frozen_plan["execution_spec"]
        assert result.result_payload["execution_plan"]["generation_mode"] == "SECTIONED"
        assert result.result_payload["execution_plan"]["lazy_loaded_capabilities"] == frozen_plan["lazy_loaded_capabilities"]
    finally:
        reset_capability_provider_status()
        clear_waiting_tasks()


@pytest.mark.parametrize("digest", sorted(runner.PRE_SECTION_CATALOG_DIGESTS))
@pytest.mark.parametrize("skill,previous_graph", [
    ("study_guide", "generic_artifact_v1"), ("quiz_pack", "quiz_artifact_v1"),
    ("wiki_page", "wiki_artifact_v1"),
])
def test_previous_single_builtin_plan_migrates_to_sectioned_without_changing_frozen_authority(
    monkeypatch, digest, skill, previous_graph,
):
    input_task = task(skill)
    input_task.catalog_digest = digest
    plan = build_execution_plan(input_task)
    plan.skill_graph_key = previous_graph
    plan.generation_mode = "SINGLE"
    plan.node_sequence = []
    plan.execution_spec.compilation_trace["llm_calls"] = 2
    frozen_input = deepcopy(input_task.model_dump(mode="json"))
    frozen_spec = deepcopy(plan.execution_spec.model_dump(mode="json"))
    frozen_capabilities = list(plan.lazy_loaded_capabilities)
    def forbidden(*args, **kwargs):
        raise AssertionError("旧章节计划迁移不能重新编译意图或调用模型")
    monkeypatch.setattr(runner, "build_default_llm_client", forbidden)

    runner._migrate_frozen_builtin_graph(input_task, plan)

    assert plan.generation_mode == "SECTIONED"
    assert [node.node_id for node in plan.node_sequence] == [
        "digest", "outline", "design" if skill == "quiz_pack" else "write",
        "consistency", "cite", "guard", "repair",
    ]
    assert input_task.model_dump(mode="json") == frozen_input
    assert plan.execution_spec.model_dump(mode="json") == frozen_spec
    assert plan.lazy_loaded_capabilities == frozen_capabilities


def test_previous_custom_graph_binding_is_preserved_during_builtin_migration():
    input_task = task()
    input_task.catalog_digest = next(iter(runner.PRE_SECTION_CATALOG_DIGESTS))
    plan = build_execution_plan(input_task)
    plan.skill_graph_key = "custom-guide-graph"
    plan.generation_mode = "SINGLE"
    frozen = deepcopy(plan.model_dump(mode="json"))

    runner._migrate_frozen_builtin_graph(input_task, plan)

    assert plan.model_dump(mode="json") == frozen


def test_sectioned_real_chunk_identifier_is_unambiguous_when_another_source_has_same_title(monkeypatch):
    input_task = task()
    input_task.input_snapshot_id = "sectioned-frozen-input"
    input_task.source_scope[0].source_snapshot_id = "snapshot-a"
    input_task.source_scope[1].source_snapshot_id = "snapshot-b"
    input_task.source_scope[1].title = "collision"
    base = runner.build_default_llm_client()
    class CollisionProvider:
        def complete_json(self, purpose, payload):
            raw = base.complete_json(purpose, payload)
            if purpose == "artifact.node.outline_planner":
                output = json.loads(raw)
                for chapter in output["chapter_plan"]:
                    chapter["chunk_ids"] = ["collision"]
                return json.dumps(output, ensure_ascii=False)
            return raw
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: CollisionProvider())
    def search(*args):
        return {"task_id": input_task.task_id, "input_snapshot_id": input_task.input_snapshot_id,
                "degraded": False, "degradation_reasons": [], "passages": [
                    {"chunk_id": key, "source_id": source.source_id,
                     "source_snapshot_id": source.source_snapshot_id, "chunk_no": 0,
                     "title": source.title, "location": "原文位置", "content": source.sample_text, "score": 1.0}
                    for key, source in zip(("collision", "other"), input_task.source_scope)
                ]}

    _, result = runner.run_artifact_task(input_task, passage_search=search)

    assert result.result_payload["verification"]["status"] == "PASS"
    assert all(section["chunk_ids"] == ["collision"] for section in result.result_payload["verification"]["sections"])
    assert all(section["source_refs"] == ["延迟报告"] for section in result.result_payload["sections"])


def test_sectioned_reference_only_repair_recites_failed_chapter_with_identical_display_title(monkeypatch):
    input_task = task()
    input_task.source_scope[0].title = input_task.source_scope[1].title = "报告"
    input_task.source_scope[0].sample_text = "旧报告记录延迟降低 20%。"
    input_task.source_scope[1].sample_text = "新报告记录延迟降低 99%。"
    base = runner.build_default_llm_client()
    calls, target_citations, repaired_inputs = [], [], []
    class ReferenceProvider:
        def complete_json(self, purpose, payload):
            calls.append((purpose, deepcopy(payload)))
            if purpose == "artifact.node.section_repair":
                repaired_inputs.append(deepcopy(payload["inputs"]))
                return json.dumps({"sections": payload["inputs"]["failed_sections"]}, ensure_ascii=False)
            raw = base.complete_json(purpose, payload)
            output = json.loads(raw) if raw else None
            if purpose == "artifact.node.section_writer" and payload["inputs"]["chapter"]["heading"] == "学习目标":
                output["section"]["body"] = "新报告记录延迟降低 99%。"
            if purpose == "artifact.node.section_citer" and payload["inputs"]["section"]["heading"] == "学习目标":
                fragment = "20%" if not target_citations else "99%"
                chunk = next(passage["chunk_id"] for passage in payload["inputs"]["material_passages"]
                             if fragment in passage["content"])
                output["citation_assignment"]["chunk_ids"] = [chunk]
                target_citations.append(chunk)
            if purpose == "artifact.node.statement_support_judge":
                texts = {statement["statement_id"]: statement["text"] for statement in payload["inputs"]["statements"]}
                for assessment in output["assessments"]:
                    text = texts[assessment["statement_id"]]
                    number = "99%" if "99%" in text else "20%" if "20%" in text else ""
                    if not number:
                        continue
                    for citation in assessment["citations"]:
                        citation["label"] = "SUPPORTED" if number in citation["quote"] else "UNSUPPORTED"
                    supported = any(citation["label"] == "SUPPORTED" for citation in assessment["citations"])
                    assessment.update(label="SUPPORTED" if supported else "UNSUPPORTED",
                                      sufficiently_supported=supported, reason="逐引用核对报告中的实际数字。")
            return json.dumps(output, ensure_ascii=False) if output is not None else raw
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: ReferenceProvider())

    _, result = runner.run_artifact_task(input_task)

    assert len(target_citations) == 2 and target_citations[0] != target_citations[1]
    assert len(repaired_inputs) == 1
    target = next(section for section in result.result_payload["sections"] if section["heading"] == "学习目标")
    assert target == {"heading": "学习目标", "body": "新报告记录延迟降低 99%。", "source_refs": ["报告"]}
    verification = result.result_payload["verification"]
    assert verification["status"] == "PASS"
    assert verification["repair_rounds"][0]["repaired_sections"] == ["学习目标"]
    assert next(section for section in verification["sections"] if section["heading"] == "学习目标")["chunk_ids"] == [target_citations[1]]
    for heading in ("核心概念", "练习路径"):
        assert len([payload for purpose, payload in calls if purpose == "artifact.node.section_citer"
                    and payload["inputs"]["section"]["heading"] == heading]) == 1


@pytest.mark.parametrize("graph_key,mode", [("sectioned_artifact_v1", "SECTIONED"), ("generic_artifact_v1", "SINGLE")])
def test_custom_action_freezes_mode_from_its_registered_graph(graph_key, mode):
    from app.models import ArtifactExecutionPlan, CustomProductionActionRegistration
    from app.registry import register_custom_action, reset_custom_actions
    reset_custom_actions()
    try:
        action = register_custom_action(CustomProductionActionRegistration.model_validate({
            "action_key": "custom_guide_4e", "display_name": "自定义学习指南",
            "base_action_key": "study_guide", "default_skill_graph_key": graph_key}))
        input_task = task("")
        input_task.catalog_digest = next(iter(runner.PRE_SECTION_CATALOG_DIGESTS))
        input_task.input_payload.action_key = action.action_key
        input_task.control_pack.target_key = action.action_key
        plan = build_execution_plan(input_task)
        restored = ArtifactExecutionPlan.model_validate(plan.model_dump(mode="json"))

        runner._migrate_frozen_builtin_graph(input_task, restored)

        assert restored.generation_mode == mode
        assert restored.skill_graph_key == graph_key
        assert restored.execution_spec.model_dump(mode="json") == plan.execution_spec.model_dump(mode="json")
    finally:
        reset_custom_actions()
