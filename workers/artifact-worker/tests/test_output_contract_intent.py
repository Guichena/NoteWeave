"""4A 契约、规则严重级别和模型意图编译的行为回归。"""

from copy import deepcopy
from types import SimpleNamespace
import json
import pytest
from jsonschema.exceptions import SchemaError

from app.compiler import build_execution_plan
from app.intent_compiler import compile_execution_spec
from app.llm_client import FakeLlmClient, OpenAICompatibleLlmClient
from app.models import ArtifactTaskInput
from app.output_contract import validate_output_contract, evaluate_output_contract
from app.artifact_skill_catalog import validate_published_catalog, _CATALOG


def task(skill="resume_highlight", language=""):
    return ArtifactTaskInput.model_validate({
        "task_id": "contract-task", "workspace_id": "workspace", "target_id": "artifact",
        "source_scope": [{"source_id": "source", "title": "报告", "sample_text": "延迟降低 20%。"}],
        "control_pack": {"pack_type": "artifact", "target_key": skill, "task_neighborhood": "ARTIFACT",
                         "style_constraints": ["使用简洁措辞"], "forbidden_patterns": ["编造数据"]},
        "input_payload": {"skill_key": skill, "user_requirement": "面向初学者，解释设计依据与局限",
                          "inputs": {"language": language} if language else {}}})


def intent(**changes):
    return {"audience": "初学者", "focus_points": ["设计依据", "局限"],
            "style_directives": ["循序解释"], "constraints": ["保留资料边界"], "output_language": "zh", **changes}


def result(plan, bodies=None):
    bodies = bodies or ["- 延迟降低 20%。\n- 基于资料说明设计依据。\n- 说明局限。"] * len(plan.outline)
    return SimpleNamespace(result_title="草稿", result_payload={
        "markdown": "\n\n".join(f"## {h}\n{b}" for h, b in zip(plan.outline, bodies)),
        "sections": [{"heading": h, "body": b, "source_refs": ["报告"]} for h, b in zip(plan.outline, bodies)],
        "canonical_content_objects": [{"plain_text": "延迟降低 20%。"}]})


def test_intent_compilation_uses_model_without_changing_host_action_or_inputs():
    llm = FakeLlmClient({"artifact.compile_intent": json.dumps(intent(), ensure_ascii=False)})
    input_task = task()
    spec = compile_execution_spec(input_task, llm)
    assert spec.compilation_method == "LLM"
    assert spec.audience == "初学者" and spec.focus_points == ["设计依据", "局限"]
    assert spec.inputs == input_task.input_payload.inputs
    assert spec.resolved_action_key == "RESUME_HIGHLIGHT"
    assert "avoid=编造数据" in spec.constraints
    assert "使用简洁措辞" in spec.style_directives
    assert spec.compilation_trace["llm_calls"] == 1
    assert llm.calls[0][0] == "artifact.compile_intent"


def test_invalid_intent_retries_with_errors_once_and_can_recover():
    class SequenceClient:
        def __init__(self): self.calls = []
        def complete_json(self, purpose, payload):
            self.calls.append((purpose, payload))
            return "{}" if len(self.calls) == 1 else json.dumps(intent())
    llm = SequenceClient()
    spec = compile_execution_spec(task(), llm)
    assert spec.compilation_method == "LLM" and spec.compilation_trace["llm_calls"] == 2
    assert llm.calls[1][1]["validation_errors"]


@pytest.mark.parametrize("mutation", [
    lambda d: d.update(capabilities=["READ_WEB_PAGE"]),
    lambda d: d.update(focus_points="错误类型"),
    lambda d: d.update(audience=""),
    lambda d: d.update(output_language="\n"),
    lambda d: d.update(constraints=["x" * 301]),
])
def test_unusable_intent_falls_back_honestly_and_cannot_add_authority(mutation):
    raw = intent(); mutation(raw)
    llm = FakeLlmClient({"artifact.compile_intent": json.dumps(raw)})
    spec = compile_execution_spec(task(), llm)
    assert spec.compilation_method == "RULE"
    assert spec.compilation_trace["llm_calls"] == 2
    assert spec.compilation_trace["degradation_reasons"] == ["ARTIFACT_INTENT_LLM_OUTPUT_INVALID"]
    assert spec.resolved_action_key == "RESUME_HIGHLIGHT"


def test_model_cannot_override_explicit_language_and_disabled_mode_calls_nothing(monkeypatch):
    llm = FakeLlmClient({"artifact.compile_intent": json.dumps(intent(output_language="en"))})
    spec = compile_execution_spec(task(language="zh"), llm)
    assert spec.compilation_method == "RULE" and spec.output_language == "zh"
    assert len(llm.calls) == 2
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_INTENT_ENABLED", "false")
    spec = compile_execution_spec(task(), llm)
    assert spec.compilation_trace["llm_calls"] == 0 and len(llm.calls) == 2
    assert spec.compilation_trace["degradation_reasons"] == ["ARTIFACT_INTENT_LLM_DISABLED"]


def test_absent_model_has_zero_calls_and_preserves_rule_fallback():
    spec = compile_execution_spec(task())
    assert spec.compilation_trace["llm_calls"] == 0
    assert spec.compilation_trace["degradation_reasons"] == ["ARTIFACT_INTENT_LLM_UNAVAILABLE"]


def test_contract_is_bound_to_plan_and_survives_checkpoint_without_recompilation():
    plan = build_execution_plan(task(), FakeLlmClient({"artifact.compile_intent": json.dumps(intent())}))
    restored = type(plan).model_validate(plan.model_dump(mode="json"))
    assert restored.declared_output_contract == plan.declared_output_contract
    assert restored.execution_spec.compilation_trace["llm_calls"] == 1
    assert restored.execution_spec.compilation_method == "LLM"


@pytest.mark.parametrize("change", [
    lambda c: c["rules"][0].update(name="unknown_rule"),
    lambda c: c["rules"][0].update(level="IGNORE"),
    lambda c: c["rules"][0].update(backtrack_to="not_a_graph_node"),
    lambda c: c["rules"][0]["parameters"].update(min=8, max=3),
    lambda c: c.update(json_schema={"$ref": "https://untrusted.example/schema"}),
    lambda c: c.update(fields={"value": {"type": "invalid"}}),
])
def test_invalid_or_unknown_contract_policy_is_rejected_at_publication(change):
    catalog = deepcopy(_CATALOG)
    change(catalog["skills"][0]["output_contract"])
    with pytest.raises((ValueError, SchemaError)):
        validate_published_catalog(catalog)


def test_assert_fails_but_suggest_only_warns_and_preserves_backtrack_target():
    plan = build_execution_plan(task())
    draft = result(plan, ["正文", "- 一条", "正文"])
    checks = evaluate_output_contract(draft, plan)
    bullet = next(c for c in checks if c.metadata.get("rule_name") == "bullet_count")
    assert bullet.status == "FAIL" and bullet.metadata["backtrack_to"] == "write"
    plan.declared_output_contract["rules"][0]["level"] = "SUGGEST"
    checks = evaluate_output_contract(draft, plan)
    assert next(c for c in checks if c.metadata.get("rule_name") == "bullet_count").status == "WARN"


def test_schema_checks_custom_field_types_and_required_headings():
    plan = build_execution_plan(task("study_guide"))
    plan.declared_output_contract["fields"] = {"bullets": {"type": "array", "min_items": 3}}
    draft = result(plan)
    draft.result_payload["bullets"] = "三个要点"
    draft.result_payload["sections"][0]["heading"] = "其他章节"
    checks = evaluate_output_contract(draft, plan)
    assert any(c.status == "FAIL" and c.metadata.get("path") == "bullets" for c in checks)
    assert any(c.status == "FAIL" and c.metadata.get("validator") == "contains" for c in checks)


def test_required_citation_density_is_structural_and_does_not_claim_semantic_support():
    plan = build_execution_plan(task("study_guide"))
    draft = result(plan)
    draft.result_payload["sections"][1]["source_refs"] = []
    checks = evaluate_output_contract(draft, plan)
    assert any(c.status == "FAIL" and c.metadata.get("rule_name") == "citation_density" for c in checks)
    assert all(c.metadata.get("rule_name") != "semantic_support" for c in checks)


def test_metric_literal_cannot_match_part_of_a_different_source_number():
    plan = build_execution_plan(task())
    draft = result(plan)
    draft.result_payload["canonical_content_objects"][0]["plain_text"] = "延迟降低 120%。"
    checks = evaluate_output_contract(draft, plan)
    metric = next(c for c in checks if c.metadata.get("rule_name") == "no_fabricated_metrics")
    assert metric.status == "FAIL"
    assert metric.metadata["unsupported_metrics"] == ["20%"]


@pytest.mark.parametrize("answer,expected", [("A", "PASS"), ("C", "FAIL")])
def test_quiz_answer_must_be_one_of_declared_options(answer, expected):
    plan = build_execution_plan(task("quiz_pack"))
    draft = result(plan)
    draft.result_payload["sections"][1]["body"] = f"1. 选择正确结论\nA. 第一项\nB. 第二项\n答案：{answer}"
    checks = evaluate_output_contract(draft, plan)
    check = next(c for c in checks if c.metadata.get("rule_name") == "quiz_answer_in_options")
    assert check.status == expected


def test_wiki_rule_has_no_dependency_on_specific_product_or_runtime_terms():
    plan = build_execution_plan(task("wiki_page"))
    draft = result(plan, ["定义：独立主题。边界：仅讨论本资料。", "该主题通过分工组织流程。", "- 资料甲\n- 资料乙\n- 资料丙"])
    assert all(c.status == "PASS" for c in evaluate_output_contract(draft, plan))


def test_model_budget_counts_real_attempts_and_checkpoint_calls():
    client = OpenAICompatibleLlmClient(api_key="test", model="test", max_total_calls=2)
    seen = []
    def complete(**kwargs):
        seen.append(kwargs)
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(intent())))],
                               usage=SimpleNamespace(prompt_tokens=5, completion_tokens=3))
    client.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=complete)))
    client.reserve_prior_calls(1)
    spec = compile_execution_spec(task(), client)
    assert spec.compilation_trace["llm_calls"] == 1
    assert client.complete_json("artifact.generate", {}) == ""
    assert len(seen) == 1 and client.usage_summary()["call_count"] == 2
    assert "five fields" in seen[0]["messages"][0]["content"]


def test_rejected_capability_union_does_not_call_intent_model():
    input_task = task()
    input_task.input_payload.requested_capabilities = ["CUSTOM_MCP_NETWORK"]
    llm = FakeLlmClient({"artifact.compile_intent": json.dumps(intent())})
    with pytest.raises(ValueError, match="capability union policy rejected plan"):
        build_execution_plan(input_task, llm)
    assert llm.calls == []


def test_provider_exception_records_one_attempt_and_falls_back():
    class FailingClient:
        def __init__(self): self.calls = []
        def complete_json(self, purpose, payload):
            self.calls.append((purpose, payload))
            raise RuntimeError("调用失败")
    spec = compile_execution_spec(task(), FailingClient())
    assert spec.compilation_method == "RULE"
    assert spec.compilation_trace["llm_calls"] == 1
    assert spec.compilation_trace["degradation_reasons"] == ["ARTIFACT_INTENT_LLM_CALL_FAILED"]


@pytest.mark.parametrize("skill,action", [("knowledge_blog", "KNOWLEDGE_BLOG"),
    ("interview_qa", "INTERVIEW_QA"), ("video_learning_deck", "SLIDE_DECK")])
def test_native_output_has_dynamic_headings_but_still_requires_body_and_evidence(skill, action):
    from app.output_contract import verify_native_skill_result
    draft = SimpleNamespace(result_title="视频产物", result_payload={"markdown": "# 动态主题", "sections": [
        {"heading": "来自冻结计划的主题", "body": "冻结证据结论", "source_refs": ["subtitle:segment-1"]}],
        "verification": {"status": "PASS"}})
    verify_native_skill_result(draft, skill, action)
    assert draft.result_payload["output_contract_trace"]["status"] == "PASS"
    draft.result_payload["sections"][0]["source_refs"] = []
    with pytest.raises(ValueError, match="契约未通过"):
        verify_native_skill_result(draft, skill, action)
