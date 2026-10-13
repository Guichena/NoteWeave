"""局部修复的完整图行为、两轮上限及引用最终门控。"""

from copy import deepcopy
import json

import pytest

from app import runner
from app.models import ArtifactTaskInput
from app.compiler import build_execution_plan
from app.skill_graph import execute_skill_graph
from app.node_executor import NodeExecutionError


def task():
    return ArtifactTaskInput.model_validate({
        "task_id": "repair-task", "workspace_id": "workspace", "target_id": "artifact",
        "source_scope": [{"source_id": "source", "title": "报告", "sample_text": "延迟降低 20%。"}],
        "control_pack": {"pack_type": "artifact", "target_key": "study_guide", "task_neighborhood": "ARTIFACT"},
        "input_payload": {"skill_key": "study_guide", "user_requirement": "依据资料解释设计与局限"}})


class RepairProvider:
    def __init__(self, base, *, mode="recover", corrupt_after_repair=False):
        self.base, self.mode = base, mode
        self.calls = []
        self.original = None
        self.corrupt_after_repair = corrupt_after_repair
        self.repair_count = 0

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, deepcopy(payload)))
        if purpose == "artifact.node.section_repair":
            self.repair_count += 1
            sections = deepcopy(payload["inputs"]["failed_sections"])
            if self.mode == "recover" or (self.mode == "second_round" and self.repair_count == 2):
                for section in sections:
                    section["body"] = "延迟降低 20%。"
            if self.mode == "overwrite_passed":
                sections.append(deepcopy(self.original[-1]))
            return json.dumps({"sections": sections}, ensure_ascii=False)
        raw = self.base.complete_json(purpose, payload)
        if purpose == "artifact.node.generic_section_writer":
            output = json.loads(raw)
            self.original = deepcopy(output["sections"])
            output["sections"][0]["body"] = "延迟降低 99%。"
            return json.dumps(output, ensure_ascii=False)
        if purpose == "artifact.node.statement_support_judge":
            output = json.loads(raw)
            inputs = {item["statement_id"]: item for item in payload["inputs"]["statements"]}
            for assessment in output["assessments"]:
                if "99%" in inputs[assessment["statement_id"]]["text"] or (self.corrupt_after_repair and self.repair_count):
                    assessment.update(label="UNSUPPORTED", sufficiently_supported=False, reason="原文只记载 20%")
                    for citation in assessment["citations"]:
                        citation["label"] = "UNSUPPORTED"
            return json.dumps(output, ensure_ascii=False)
        return raw


def configured(monkeypatch, **options):
    provider = RepairProvider(runner.build_default_llm_client(), **options)
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: provider)
    return provider


def prevent_export(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("失败的正文不能导出或提交")
    monkeypatch.setattr(runner, "export_artifact_if_required", forbidden)
    monkeypatch.setattr(runner, "commit_artifact_result", forbidden)


def test_repair_changes_only_failed_section_and_rechecks_real_evidence(monkeypatch):
    provider = configured(monkeypatch)
    _, result = runner.run_artifact_task(task())
    verification = result.result_payload["verification"]
    assert verification["contract_status"] == "PASS" and verification["evidence_status"] == "PASS"
    assert len(verification["repair_rounds"]) == 1
    assert verification["repair_rounds"][0]["repaired_sections"] == [provider.original[0]["heading"]]
    assert result.result_payload["sections"][1:] == provider.original[1:]
    calls = [payload for purpose, payload in provider.calls if purpose == "artifact.node.section_repair"]
    assert len(calls) == 1 and len(calls[0]["inputs"]["failed_sections"]) == 1
    assert "99%" not in result.result_payload["markdown"]
    graph = result.result_payload["generation_trace"]
    assert graph["llm_calls"] == sum(purpose.startswith("artifact.node.") for purpose, _ in provider.calls)
    repair_inputs = calls[0]["inputs"]
    assert repair_inputs["failed_diagnostics"][0]["statements"][0]["text"] == "延迟降低 99%。"
    assert all(pack["heading"] == provider.original[0]["heading"] for pack in repair_inputs["section_materials"])


def test_second_round_can_recover_without_erasing_first_failure(monkeypatch):
    provider = configured(monkeypatch, mode="second_round")
    _, result = runner.run_artifact_task(task())
    rounds = result.result_payload["verification"]["repair_rounds"]
    assert provider.repair_count == 2 and [row["round"] for row in rounds] == [1, 2]
    assert rounds[0]["remaining_failed_checks"] and rounds[1]["remaining_failed_checks"] == []


@pytest.mark.parametrize("mode", ["unchanged", "overwrite_passed"])
def test_two_failed_rounds_never_export_or_modify_passed_sections(monkeypatch, mode):
    provider = configured(monkeypatch, mode=mode)
    prevent_export(monkeypatch)
    with pytest.raises(NodeExecutionError) as caught:
        runner.run_artifact_task(task())
    assert len(caught.value.trace["repair_trace"]["rounds"]) == 2
    assert provider.repair_count == (4 if mode == "overwrite_passed" else 2)
    if mode == "overwrite_passed":
        steps = caught.value.trace["repair_trace"]["execution_traces"]
        assert all("不能遗漏或改写已通过章节" in step["validation_errors"][0] for step in steps)


def test_repair_is_not_success_until_new_statement_verification_passes(monkeypatch):
    provider = configured(monkeypatch, corrupt_after_repair=True)
    prevent_export(monkeypatch)
    with pytest.raises(NodeExecutionError) as caught:
        runner.run_artifact_task(task())
    assert provider.repair_count == 2
    assert caught.value.trace["verification"]["evidence_status"] == "FAIL"


def test_missing_required_section_is_generated_without_rewriting_present_sections(monkeypatch):
    base = runner.build_default_llm_client()
    original = []
    calls = []
    class MissingSectionProvider:
        def complete_json(self, purpose, payload):
            calls.append((purpose, deepcopy(payload)))
            raw = base.complete_json(purpose, payload)
            if purpose == "artifact.node.generic_section_writer":
                output = json.loads(raw)
                original.extend(deepcopy(output["sections"]))
                output["sections"] = output["sections"][1:]
                return json.dumps(output, ensure_ascii=False)
            if purpose == "artifact.node.section_repair":
                return json.dumps({"sections": [{**section, "body": "延迟降低 20%。", "source_refs": ["报告"]}
                    for section in payload["inputs"]["failed_sections"]]}, ensure_ascii=False)
            return raw
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: MissingSectionProvider())
    _, result = runner.run_artifact_task(task())
    assert result.result_payload["sections"][1:] == original[1:]
    repairs = [payload for purpose, payload in calls if purpose == "artifact.node.section_repair"]
    assert [section["heading"] for section in repairs[0]["inputs"]["failed_sections"]] == [original[0]["heading"]]


def test_multiple_verify_repair_nodes_share_global_two_round_limit():
    provider = RepairProvider(runner.build_default_llm_client(), mode="unchanged")
    input_task = task()
    plan = build_execution_plan(input_task)
    verifier = next(node for node in plan.node_sequence if node.node_kind == "VERIFY")
    repair = next(node for node in plan.node_sequence if node.node_kind == "REPAIR")
    plan.node_sequence.extend([verifier.model_copy(update={"node_id": "second_verify"}),
                               repair.model_copy(update={"node_id": "second_repair"})])
    with pytest.raises(NodeExecutionError) as caught:
        execute_skill_graph(input_task, plan, llm_client=provider)
    assert provider.repair_count == 2
    assert [record["round"] for record in caught.value.trace["repair_trace"]["rounds"]] == [1, 2]
    second = caught.value.trace["completed_nodes"][-1]
    assert second["llm_calls"] == 0 and not second["repaired"] and not second["repair_actions"]
