"""节点字段隔离、失败重试与简历原文关联的执行测试。"""

from copy import deepcopy
import json
from types import SimpleNamespace

import pytest

from app.llm_client import FakeLlmClient, OpenAICompatibleLlmClient
from app.node_executor import (
    NodeExecutionError, NodeExecutor, validate_extracted_facts,
    validate_normalized_facts, validate_resume_bullets,
)
from app.node_contracts import node_contract_for_skill


def schema(properties, required=None):
    return {"type": "object", "properties": properties,
            "required": list(properties) if required is None else required,
            "additionalProperties": False}


def node(kind="LLM_STEP", recipe="node.generic_section_writer.v1"):
    return SimpleNamespace(node_id="write", node_kind=kind, prompt_recipe_id=recipe,
        input_schema=schema({"topic": {"type": "string"}}),
        output_schema=schema({"count": {"type": "integer", "minimum": 1}}))


class SequenceClient:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.calls = []

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, deepcopy(payload)))
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


def test_llm_projects_only_declared_fields_and_never_mutates_shared_state():
    client = FakeLlmClient({"artifact.node.generic_section_writer": '{"count":1}'})
    shared = {"topic": "资料主题", "host_token": "宿主秘密", "workspace_id": "权限范围"}
    result = NodeExecutor(client).execute(node(), shared)
    assert result.output == {"count": 1}
    assert result.trace["input_fields"] == ["topic"]
    assert client.calls[0][1]["inputs"] == {"topic": "资料主题"}
    assert "host_token" not in json.dumps(result.trace)
    assert shared["host_token"] == "宿主秘密"


def test_invalid_output_retries_once_with_original_validation_error():
    client = SequenceClient(['{"count":"1"}', '{"count":1}'])
    result = NodeExecutor(client).execute(node(), {"topic": "资料主题"})
    assert result.trace["llm_calls"] == 2
    assert result.trace["status"] == "COMPLETED"
    errors = client.calls[1][1]["validation_errors"]
    assert "not of type 'integer'" in errors[0]
    assert result.trace["validation_errors"] == errors


@pytest.mark.parametrize("raw", ['{"count":1,"extra":true}', '{"count":1.0}',
    '{"count":true}', '{"count":NaN}', '[]', '不是 JSON'])
def test_invalid_output_never_passes_after_two_attempts(raw):
    client = SequenceClient([raw, raw])
    with pytest.raises(NodeExecutionError, match="两次校验失败") as caught:
        NodeExecutor(client).execute(node(), {"topic": "资料主题"})
    assert caught.value.trace["status"] == "FAILED"
    assert caught.value.trace["llm_calls"] == 2
    assert len(client.calls) == 2


def test_guard_errors_are_retried_and_do_not_return_first_invalid_content():
    def guard(output, inputs):
        if output["count"] == 1:
            raise ValueError("事实引文不属于对应片段的连续原文")
    client = SequenceClient(['{"count":1}', '{"count":2}'])
    result = NodeExecutor(client, output_guards={node().prompt_recipe_id: guard}).execute(
        node(), {"topic": "资料主题"})
    assert result.output == {"count": 2}
    assert client.calls[1][1]["validation_errors"] == ["事实引文不属于对应片段的连续原文"]


@pytest.mark.parametrize("wrap", [
    lambda content: content,
    lambda content: f"```json\n{content}\n```",
    lambda content: f"节点输出如下：\n{content}\n输出结束。",
])
def test_valid_json_keeps_source_quote_urls_and_comment_markers_unchanged(wrap):
    plan = node()
    plan.output_schema = schema({"quote": {"type": "string"}})
    quote = "引用 https://example.test/doc 的 // 原文标记"
    client = SequenceClient([wrap(json.dumps({"quote": quote}, ensure_ascii=False))])
    result = NodeExecutor(client).execute(plan, {"topic": "资料主题"})
    assert result.output == {"quote": quote}
    assert result.trace["llm_calls"] == 1


@pytest.mark.parametrize("kind", ["RETRIEVE", "VERIFY", "REPAIR", "TOOL"])
def test_non_llm_kinds_dispatch_only_projected_deepcopy_to_handler(kind):
    plan = node(kind)
    plan.input_schema = schema({"items": {"type": "array", "items": {"type": "string"}}})
    state = {"items": ["资料"], "token": "不能读取"}
    seen = []
    def handler(projected, declared_node):
        seen.append(set(projected))
        projected["items"].append("局部调整")
        assert declared_node is plan
        return {"count": len(projected["items"])}
    result = NodeExecutor(handlers={kind: handler}).execute(plan, state)
    assert result.output == {"count": 2}
    assert state["items"] == ["资料"] and seen == [{"items"}]
    assert result.trace["llm_calls"] == 0


def test_unknown_handler_or_prompt_is_rejected_before_model_call():
    client = FakeLlmClient({})
    for invalid in [node("UNKNOWN"), node("TOOL"), node(recipe="node.unregistered.v1")]:
        with pytest.raises(NodeExecutionError):
            NodeExecutor(client).execute(invalid, {"topic": "资料主题"})
    assert client.calls == []


def test_input_validation_failure_does_not_expose_sensitive_value_or_call_model():
    client = FakeLlmClient({})
    with pytest.raises(NodeExecutionError) as caught:
        NodeExecutor(client).execute(node(), {"topic": {"credential": "输入秘密"}})
    assert "输入秘密" not in str(caught.value) + json.dumps(caught.value.trace)
    assert client.calls == []


@pytest.mark.parametrize("change", [
    lambda plan: plan.input_schema.update(additionalProperties=True),
    lambda plan: plan.output_schema["properties"]["count"].update({"$ref": "https://external/schema"}),
    lambda plan: plan.input_schema["properties"]["topic"].update({"$dynamicRef": "#recursive"}),
])
def test_open_or_referenced_schema_is_rejected_before_call(change):
    plan = node()
    change(plan)
    client = FakeLlmClient({})
    with pytest.raises(NodeExecutionError):
        NodeExecutor(client).execute(plan, {"topic": "资料主题"})
    assert client.calls == []


def test_unavailable_or_failing_client_cannot_produce_pass_and_does_not_retry_empty():
    with pytest.raises(NodeExecutionError) as caught:
        NodeExecutor().execute(node(), {"topic": "资料主题"})
    assert caught.value.trace["llm_calls"] == 0
    for response in ["", RuntimeError("不能带出连接凭证")]:
        client = SequenceClient([response])
        with pytest.raises(NodeExecutionError) as caught:
            NodeExecutor(client).execute(node(), {"topic": "资料主题"})
        assert caught.value.trace["llm_calls"] == 1
        assert len(client.calls) == 1
        assert "连接凭证" not in str(caught.value)


def test_sdk_budget_counts_actual_calls_and_uses_node_schema_prompt():
    client = OpenAICompatibleLlmClient(api_key="test", model="test", max_total_calls=2)
    seen = []
    def complete(**kwargs):
        seen.append(kwargs)
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content='{"count":1}'))],
            usage=SimpleNamespace(prompt_tokens=5, completion_tokens=3))
    client.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=complete)))
    client.reserve_prior_calls(1)
    result = NodeExecutor(client).execute(node(), {"topic": "资料主题"})
    assert result.trace["llm_calls"] == 1
    assert client.usage_summary()["call_count"] == 2
    assert "matching the supplied output_schema" in seen[0]["messages"][0]["content"]
    with pytest.raises(NodeExecutionError) as caught:
        NodeExecutor(client).execute(node(), {"topic": "资料主题"})
    assert caught.value.trace["llm_calls"] == 0
    assert len(seen) == 1


def resume_data():
    passage = {"chunk_id": "chunk-1", "title": "项目资料", "content": "新增索引后延迟降低20%，上线3个接口。"}
    fact = {"fact_id": "fact-1", "text": "新增索引后延迟降低20%", "chunk_id": "chunk-1",
            "quote": "新增索引后延迟降低20%"}
    normalized = {**fact, "metrics": ["20%"]}
    output = {"bullets": [{"text": "新增索引后延迟降低20%", "fact_ids": ["fact-1"]}],
        "sections": [
            {"heading": "一句话定位", "body": "新增索引", "source_refs": ["项目资料"]},
            {"heading": "简历亮点", "body": "- 新增索引后延迟降低20%", "source_refs": ["项目资料"]},
            {"heading": "关键词", "body": "索引", "source_refs": ["项目资料"]},
        ]}
    return passage, fact, normalized, output


def test_resume_facts_normalization_and_bullets_preserve_original_links():
    passage, fact, normalized, output = resume_data()
    validate_extracted_facts({"facts": [fact]}, {"material_passages": [passage]})
    validate_normalized_facts({"normalized_facts": [normalized]},
        {"facts": [fact], "material_passages": [passage]})
    validate_resume_bullets(output, {"normalized_facts": [normalized], "material_passages": [passage]})


def test_resume_nodes_execute_real_declared_contracts_and_pass_only_declared_state():
    passage, fact, normalized, written = resume_data()
    responses = {
        "artifact.node.resume_highlight_extractor": json.dumps({"facts": [fact]}, ensure_ascii=False),
        "artifact.node.resume_impact_normalizer": json.dumps({"normalized_facts": [normalized]}, ensure_ascii=False),
        "artifact.node.resume_bullet_writer": json.dumps(written, ensure_ascii=False),
    }
    client = FakeLlmClient(responses)
    executor = NodeExecutor(client)
    state = {"material_passages": [passage], "generation_context": {
        "goal": "提取真实贡献", "outline": ["一句话定位", "简历亮点", "关键词"]},
        "callback_token": "不能发送给模型", "workspace_id": "宿主权限范围"}
    for skill in ("resume_highlight_extractor", "resume_impact_normalizer", "resume_bullet_writer"):
        declared = SimpleNamespace(node_id=skill, **node_contract_for_skill(skill))
        result = executor.execute(declared, state)
        state.update(result.output)
        assert result.trace["status"] == "COMPLETED"
        assert result.trace["llm_calls"] == 1
    assert state["bullets"] == written["bullets"]
    assert all("callback_token" not in payload["inputs"] and "workspace_id" not in payload["inputs"]
               for _, payload in client.calls)
    assert [purpose for purpose, _ in client.calls] == list(responses)


def test_fact_guard_failure_retries_on_real_contract_with_continuous_quote_error():
    passage, fact, _, _ = resume_data()
    invalid = {**fact, "quote": "延迟降低20%，新增索引"}
    client = SequenceClient([json.dumps({"facts": [invalid]}, ensure_ascii=False),
        json.dumps({"facts": [fact]}, ensure_ascii=False)])
    declared = SimpleNamespace(node_id="extract", **node_contract_for_skill("resume_highlight_extractor"))
    result = NodeExecutor(client).execute(declared,
        {"material_passages": [passage], "generation_context": {}})
    assert result.output["facts"] == [fact]
    assert result.trace["llm_calls"] == 2
    assert "连续原文" in client.calls[1][1]["validation_errors"][0]


@pytest.mark.parametrize("change", [
    lambda facts: facts.append(deepcopy(facts[0])),
    lambda facts: facts[0].update(chunk_id="其他片段"),
    lambda facts: facts[0].update(quote="延迟降低20%，新增索引"),
])
def test_extractor_rejects_duplicate_ids_unknown_chunks_and_rearranged_quotes(change):
    passage, fact, _, _ = resume_data()
    facts = [fact]
    change(facts)
    with pytest.raises(ValueError):
        validate_extracted_facts({"facts": facts}, {"material_passages": [passage]})


@pytest.mark.parametrize("change", [
    lambda fact: fact.update(fact_id="新增事实"),
    lambda fact: fact.update(chunk_id="其他片段"),
    lambda fact: fact.update(quote="上线3个接口"),
    lambda fact: fact.update(metrics=["120%"]),
    lambda fact: fact.update(text="新增索引后延迟降低120%"),
    lambda fact: fact.update(metrics=["没有数字"]),
])
def test_normalizer_rejects_new_facts_changed_quotes_or_invented_metrics(change):
    passage, fact, normalized, _ = resume_data()
    change(normalized)
    with pytest.raises(ValueError):
        validate_normalized_facts({"normalized_facts": [normalized]},
            {"facts": [fact], "material_passages": [passage]})


@pytest.mark.parametrize("change", [
    lambda output: output["bullets"][0].update(fact_ids=[]),
    lambda output: output["bullets"][0].update(fact_ids=["新增事实"]),
    lambda output: output["bullets"][0].update(text="延迟降低120%"),
    lambda output: output["sections"][1].update(body="- 未声明的贡献"),
    lambda output: output["sections"][1].update(source_refs=["其他资料"]),
    lambda output: output["sections"][0].update(body="提升2倍"),
])
def test_writer_rejects_unlinked_bullets_changed_body_or_unsupported_metrics(change):
    passage, _, normalized, output = resume_data()
    change(output)
    with pytest.raises(ValueError):
        validate_resume_bullets(output, {"normalized_facts": [normalized], "material_passages": [passage]})


def test_metric_twenty_percent_cannot_be_supported_by_one_hundred_twenty_percent():
    passage, _, normalized, output = resume_data()
    normalized.update(quote="延迟降低120%", text="延迟降低120%", metrics=["120%"])
    passage["content"] = "延迟降低120%"
    with pytest.raises(ValueError, match="没有的数字或指标"):
        validate_resume_bullets(output, {"normalized_facts": [normalized], "material_passages": [passage]})


def test_normalizer_metric_cannot_use_number_substring_of_original_quote():
    fact = {"fact_id": "fact-1", "text": "延迟降低120%", "chunk_id": "chunk-1", "quote": "延迟降低120%"}
    passage = {"chunk_id": "chunk-1", "title": "项目资料", "content": fact["quote"]}
    with pytest.raises(ValueError, match="归一化指标出现引用原文没有的数字或指标"):
        validate_normalized_facts({"normalized_facts": [{**fact, "metrics": ["20%"]}]},
            {"facts": [fact], "material_passages": [passage]})
