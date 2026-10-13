"""类型化图的事实链路、拒绝发布与旧检查点迁移回归。"""

from copy import deepcopy
import json

import pytest

from app import runner
from app.compiler import build_execution_plan
from app.composer import render_markdown
from app.models import ArtifactSectionDraft, ArtifactTaskInput
from app.node_executor import NodeExecutionError


def resume_task():
    return ArtifactTaskInput.model_validate({
        "task_id": "typed-graph-task", "workspace_id": "workspace", "target_id": "artifact",
        "source_scope": [{"source_id": "source", "title": "报告", "sample_text": "延迟降低 20%。"}],
        "control_pack": {"pack_type": "artifact", "target_key": "resume_highlight",
                         "task_neighborhood": "ARTIFACT", "forbidden_patterns": ["编造数据"]},
        "input_payload": {"skill_key": "resume_highlight", "user_requirement": "依据资料概括贡献"},
    })


class MutatingProvider:
    def __init__(self, base, mutation):
        self.base = base
        self.mutation = mutation
        self.calls = []

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, deepcopy(payload)))
        raw = self.base.complete_json(purpose, payload)
        if purpose.startswith("artifact.node."):
            output = json.loads(raw)
            self.mutation(purpose, output)
            return json.dumps(output, ensure_ascii=False)
        return raw


def forbid_render(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("无效正文不能渲染或导出")
    monkeypatch.setattr(runner, "render_markdown", forbidden)
    monkeypatch.setattr(runner, "export_artifact_if_required", forbidden)


def test_resume_graph_keeps_original_facts_and_independent_model_steps():
    _, result = runner.run_artifact_task(resume_task())
    generation = result.result_payload["generation_trace"]
    assert generation["purposes"] == [
        "artifact.node.resume_highlight_extractor",
        "artifact.node.resume_impact_normalizer",
        "artifact.node.resume_bullet_writer",
    ]
    assert generation["llm_calls"] == 3
    state = generation["state"]
    facts = {fact["fact_id"]: fact for fact in state["facts"]}
    assert facts and all(fact["quote"] == "延迟降低 20%。" for fact in facts.values())
    assert all(fact["quote"] == facts[fact["fact_id"]]["quote"] for fact in state["normalized_facts"])
    assert all(bullet["fact_ids"] and set(bullet["fact_ids"]) <= facts.keys() for bullet in state["bullets"])
    assert state["repair_trace"]["status"] == "SKIPPED"


def test_invented_metric_retries_with_error_and_never_runs_writer_or_export(monkeypatch):
    def mutation(purpose, output):
        if purpose == "artifact.node.resume_impact_normalizer":
            output["normalized_facts"][0]["text"] = "延迟降低 99%。"
    provider = MutatingProvider(runner.build_default_llm_client(), mutation)
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: provider)
    forbid_render(monkeypatch)
    with pytest.raises(NodeExecutionError) as caught:
        runner.run_artifact_task(resume_task())
    calls = [payload for purpose, payload in provider.calls if purpose == "artifact.node.resume_impact_normalizer"]
    assert len(calls) == 2 and "原文没有的数字或指标" in calls[1]["validation_errors"][0]
    assert caught.value.trace["llm_calls"] == 2
    assert not any(purpose == "artifact.node.resume_bullet_writer" for purpose, _ in provider.calls)


def test_forbidden_content_is_rejected_without_rewriting_generated_facts(monkeypatch):
    def mutation(purpose, output):
        if purpose == "artifact.node.resume_bullet_writer":
            for bullet in output["bullets"]:
                bullet["text"] += " 编造数据"
            for section in output["sections"]:
                if section["heading"] == "简历亮点":
                    section["body"] = "\n".join("- " + bullet["text"] for bullet in output["bullets"])
    provider = MutatingProvider(runner.build_default_llm_client(), mutation)
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: provider)
    forbid_render(monkeypatch)
    with pytest.raises(NodeExecutionError) as caught:
        runner.run_artifact_task(resume_task())
    assert caught.value.trace["reason_code"] == "ARTIFACT_NODE_CONTRACT_FAILED"
    assert any("明确禁止的内容" in failure for failure in caught.value.trace["failed_checks"])
    repair = caught.value.trace["completed_nodes"][-1]
    assert repair["node_kind"] == "REPAIR" and repair["verification_status"] == "NOT_RUN"


def test_old_builtin_checkpoint_migrates_only_graph_metadata(monkeypatch):
    task = resume_task()
    task.catalog_digest = runner.PRE_TYPED_NODE_CATALOG_DIGEST
    plan = build_execution_plan(task, llm_client=runner.build_default_llm_client()).model_copy(deep=True)
    task_before = task.model_dump(mode="json")
    spec_before = plan.execution_spec.model_dump(mode="json")
    capabilities_before = list(plan.lazy_loaded_capabilities)
    plan.required_phrases = ["NoteWeave 固定主题"]
    plan.prompt_recipe.system_intent = "旧内置固定主题"
    plan.node_sequence = []
    plan.runtime_plan.activated_nodes = ["style_polisher"]
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: pytest.fail("迁移不能重新编译意图"))
    runner._migrate_frozen_builtin_graph(task, plan)
    assert task.model_dump(mode="json") == task_before
    assert plan.execution_spec.model_dump(mode="json") == spec_before
    assert plan.lazy_loaded_capabilities == capabilities_before
    assert plan.required_phrases == [] and plan.prompt_recipe.system_intent != "旧内置固定主题"
    assert [node.node_kind for node in plan.node_sequence] == ["RETRIEVE", "LLM_STEP", "LLM_STEP", "LLM_STEP", "VERIFY", "REPAIR"]
    assert "style_polisher" not in plan.runtime_plan.activated_nodes


@pytest.mark.parametrize("body, expected", [
    ("技能：资料中的可靠性措施使用日志恢复。", "技能：资料中的可靠性措施使用日志恢复"),
    ("-20% 表示本期变化。", "-20% 表示本期变化"),
])
def test_mindmap_renderer_preserves_real_content_with_previous_template_prefix(body, expected):
    task = resume_task()
    task.input_payload.skill_key = "mindmap_from_workspace"
    task.control_pack.target_key = "mindmap_from_workspace"
    plan = build_execution_plan(task)
    markdown = render_markdown(task, plan, [ArtifactSectionDraft(
        heading="主题中心", body=body, source_refs=["报告"])])
    assert expected in markdown
    assert "梳理当前工作台资料的主题全景" not in markdown
