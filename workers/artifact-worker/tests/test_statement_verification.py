"""陈述支持判断、完整原文引用和不可用状态的回归。"""

from copy import deepcopy
import json
from types import SimpleNamespace

import pytest

from app.llm_client import OpenAICompatibleLlmClient
import app.statement_verification as verification
from app.statement_verification import split_statements, verify_statement_support


def passage(key="chunk-1", title="资料甲", content="资料明确规定流程按顺序执行。"):
    return {"chunk_id": key, "title": title, "content": content}


def section(body="流程不按顺序执行。", heading="章节甲", refs=None):
    return {"heading": heading, "body": body, "source_refs": ["资料甲"] if refs is None else refs}


def model_output(payload, label="SUPPORTED", citation_label="SUPPORTED"):
    inputs = payload["inputs"]
    texts = {p["chunk_id"]: p["content"] for p in inputs["material_passages"]}
    return {"assessments": [{"statement_id": s["statement_id"], "label": label,
        "sufficiently_supported": label == "SUPPORTED", "reason": "根据对应原文判断。",
        "citations": [{"chunk_id": key, "label": citation_label, "quote": texts[key]}
                      for key in s["citation_chunk_ids"]]} for s in inputs["statements"]]}


class JudgeClient:
    def __init__(self, label="SUPPORTED", citation_label="SUPPORTED", changes=()):
        self.label = label
        self.citation_label = citation_label
        self.changes = list(changes)
        self.calls = []

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, deepcopy(payload)))
        output = model_output(payload, self.label, self.citation_label)
        if self.changes:
            change = self.changes.pop(0)
            if isinstance(change, Exception):
                raise change
            if isinstance(change, str):
                return change
            change(output)
        return json.dumps(output, ensure_ascii=False)


def test_sentence_and_markdown_splitting_preserves_every_statement_with_stable_ids():
    body = "- 首句。次句！\n2. 第三句。\n末句没有句号"
    assert split_statements(body) == ["首句。", "次句！", "第三句。", "末句没有句号"]
    client = JudgeClient("NON_FACTUAL", "UNSUPPORTED")
    first = verify_statement_support([section(body)], [passage()], llm_client=client)
    second = verify_statement_support([section(body)], [passage()], llm_client=client)
    assert [s["statement_id"] for s in first["sections"][0]["statements"]] == [
        s["statement_id"] for s in second["sections"][0]["statements"]]
    assert len(first["sections"][0]["statements"]) == 4


def test_identical_words_with_reversed_meaning_always_require_model_judgment():
    client = JudgeClient("CONTRADICTED", "CONTRADICTED")
    result = verify_statement_support([section()], [passage()], llm_client=client)
    assert len(client.calls) == 1
    statement = result["sections"][0]["statements"][0]
    assert statement["term_coverage"] > 0.7
    assert statement["label"] == "CONTRADICTED"
    assert result["status"] == "FAIL" and result["sections"][0]["support"] == "UNSUPPORTED"
    assert result["citation_recall"] == result["citation_precision"] == 0.0


def test_exact_low_risk_copy_can_be_supported_without_model_or_similarity_inference():
    text = "信息完整且可核实。"
    result = verify_statement_support([section(text)], [passage(content=text)])
    assert result["status"] == "PASS" and result["llm_calls"] == 0
    assert result["sections"][0]["statements"][0]["assessment_status"] == "EXACT_COPY"
    assert result["citation_recall"] == result["citation_precision"] == 1.0


@pytest.mark.parametrize("text", ["延迟降低20%。", "Redis用于保存临时数据。", "腾讯公司发布新的说明。",
    "华为推出设备。", "某未知实体推出设备。"])
def test_numbers_and_proper_names_require_model_even_when_copied_verbatim(text):
    client = JudgeClient()
    result = verify_statement_support([section(text)], [passage(content=text)], llm_client=client)
    assert result["status"] == "PASS" and result["llm_calls"] == 1
    assert len(client.calls) == 1


def test_all_statements_in_chapter_are_judged_in_one_batch():
    client = JudgeClient()
    result = verify_statement_support([section("延迟降低20%。吞吐增加30%。")], [passage(
        content="延迟降低20%。吞吐增加30%。")], llm_client=client)
    assert result["status"] == "PASS" and len(client.calls) == 1
    assert len(client.calls[0][1]["inputs"]["statements"]) == 2


def test_unknown_references_cannot_be_guessed_or_removed_from_precision_denominator():
    client = JudgeClient()
    result = verify_statement_support([section(refs=["资料甲", "不存在的资料"])], [passage()], llm_client=client)
    assert client.calls == []
    assert result["status"] == "FAIL" and result["sections"][0]["support"] == "NOT_RUN"
    assert result["citation_recall"] == result["citation_precision"] == 0.0
    statement = result["sections"][0]["statements"][0]
    assert statement["label"] == "UNDETERMINED" and statement["assessment_status"] == "NOT_RUN"


def test_title_and_different_chunk_id_collision_is_ambiguous_and_never_guessed():
    client = JudgeClient()
    result = verify_statement_support([section(refs=["chunk-2"])], [
        passage("chunk-1", "chunk-2", "第一份资料。"), passage("chunk-2", "另一份资料", "第二份资料。")],
        llm_client=client)
    assert result["status"] == "FAIL" and client.calls == []
    assert result["sections"][0]["chunk_ids"] == []
    assert result["sections"][0]["support"] == "NOT_RUN"


def test_title_equal_to_its_own_chunk_id_is_unambiguous():
    client = JudgeClient()
    result = verify_statement_support([section(refs=["chunk-1"])], [passage("chunk-1", "chunk-1")],
        llm_client=client)
    assert result["status"] == "PASS" and len(client.calls) == 1
    assert result["sections"][0]["chunk_ids"] == ["chunk-1"]


def test_real_chunk_refs_and_same_titles_are_limited_to_chapter_assignments():
    client = JudgeClient()
    passages = [passage("chunk-1", content="第一章支持20%。"), passage("chunk-2", content="第二章支持30%。")]
    materials = [{"heading": "章节甲", "passages": [{"chunk_id": "chunk-1", "selected_chars": 8}]},
        {"heading": "章节乙", "passages": [{"chunk_id": "chunk-2", "selected_chars": len(passages[1]["content"])}]}]
    result = verify_statement_support([section("第一章支持20%。"), section("第二章支持30%。", "章节乙", ["chunk-2"])],
        passages, llm_client=client, section_materials=materials)
    assert result["status"] == "PASS"
    assert [s["chunk_ids"] for s in result["sections"]] == [["chunk-1"], ["chunk-2"]]
    assert client.calls[0][1]["inputs"]["material_passages"] == [{"chunk_id": "chunk-1", "content": passages[0]["content"][:8]}]
    assert client.calls[1][1]["inputs"]["material_passages"] == [{"chunk_id": "chunk-2", "content": passages[1]["content"]}]
    assert set(client.calls[0][1]["inputs"]) == {"statements", "material_passages"}


def test_quote_outside_chapter_selected_length_is_rejected():
    client = JudgeClient(changes=[lambda out: out["assessments"][0]["citations"][0].update(quote="后部秘密"),
        lambda out: out["assessments"][0]["citations"][0].update(quote="后部秘密")])
    result = verify_statement_support([section()], [passage(content="前部资料。后部秘密")], llm_client=client,
        section_materials=[{"heading": "章节甲", "passages": [{"chunk_id": "chunk-1", "selected_chars": 5}]}])
    assert result["status"] == "FAIL" and result["llm_calls"] == 2
    assert result["sections"][0]["support"] == "NOT_RUN"


@pytest.mark.parametrize("change", [
    lambda out: out["assessments"].clear(),
    lambda out: out["assessments"].append(deepcopy(out["assessments"][0])),
    lambda out: out["assessments"][0].update(statement_id="未知陈述"),
    lambda out: out["assessments"][0].update(label="PASS"),
    lambda out: out["assessments"][0].update(sufficiently_supported=False),
    lambda out: out["assessments"][0]["citations"].clear(),
    lambda out: out["assessments"][0]["citations"].append(deepcopy(out["assessments"][0]["citations"][0])),
    lambda out: out["assessments"][0]["citations"][0].update(chunk_id="新的引用"),
    lambda out: out["assessments"][0]["citations"][0].update(quote="不是连续原文"),
])
def test_invalid_judge_structure_or_source_links_retry_once_then_not_run(change):
    client = JudgeClient(changes=[change, change])
    result = verify_statement_support([section()], [passage()], llm_client=client)
    assert result["status"] == "FAIL" and result["llm_calls"] == 2
    assert result["sections"][0]["statements"][0]["assessment_status"] == "NOT_RUN"
    assert client.calls[1][1]["validation_errors"]
    assert result["citation_recall"] == result["citation_precision"] == 0.0


def test_invalid_quote_retry_can_be_repaired_by_real_continuous_quote():
    client = JudgeClient(changes=[lambda out: out["assessments"][0]["citations"][0].update(quote="错误原文")])
    result = verify_statement_support([section()], [passage()], llm_client=client)
    assert result["status"] == "PASS" and result["llm_calls"] == 2
    assert "连续原文" in client.calls[1][1]["validation_errors"][0]


@pytest.mark.parametrize("density,status", [("PER_SECTION", "FAIL"), ("OPTIONAL", "WARN")])
def test_unavailable_model_preserves_unknown_label_and_does_not_raise_metrics(density, status):
    result = verify_statement_support([section()], [passage()], citation_density=density)
    assert result["status"] == status and result["llm_calls"] == 0
    assert result["sections"][0]["statements"][0]["label"] == "UNDETERMINED"
    assert result["citation_recall"] == result["citation_precision"] == 0.0


@pytest.mark.parametrize("failure", [RuntimeError("连接密钥不应进入诊断"), "", "invalid JSON"])
def test_provider_failure_empty_or_two_invalid_outputs_are_explicitly_not_run(failure):
    client = JudgeClient(changes=[failure, failure])
    result = verify_statement_support([section()], [passage()], llm_client=client)
    assert result["status"] == "FAIL"
    assert result["sections"][0]["support"] == "NOT_RUN"
    assert result["llm_calls"] == (2 if failure == "invalid JSON" else 1)
    assert "连接密钥" not in json.dumps(result, ensure_ascii=False)


def test_sdk_budget_exhaustion_does_not_count_a_new_call_or_report_supported():
    client = OpenAICompatibleLlmClient(api_key="test", model="test", max_total_calls=1)
    client.reserve_prior_calls(1)
    result = verify_statement_support([section()], [passage()], llm_client=client)
    assert result["status"] == "FAIL" and result["llm_calls"] == 0
    assert client.usage_summary()["call_count"] == 1


def test_partial_single_citation_cannot_make_entire_statement_supported():
    client = JudgeClient("UNSUPPORTED", "PARTIAL")
    result = verify_statement_support([section()], [passage()], llm_client=client)
    assert result["status"] == "FAIL" and result["citation_recall"] == 0.0
    assert result["citation_precision"] == 1.0
    assert result["sections"][0]["statements"][0]["citations"][0]["label"] == "PARTIAL"


def test_sufficient_statement_with_contradicted_citation_retries_with_error():
    def contradict_second(output):
        output["assessments"][0]["citations"][1]["label"] = "CONTRADICTED"
    client = JudgeClient(changes=[contradict_second])
    result = verify_statement_support([section(refs=["资料甲", "资料乙"])], [
        passage(), passage("chunk-2", "资料乙", "另一份资料明确规定流程按顺序执行。")], llm_client=client)
    assert result["status"] == "PASS" and result["llm_calls"] == 2
    assert "矛盾引用" in client.calls[1][1]["validation_errors"][0]


def test_multiple_partial_citations_conservatively_cannot_prove_full_support():
    client = JudgeClient(citation_label="PARTIAL")
    result = verify_statement_support([section(refs=["资料甲", "资料乙"])], [
        passage(), passage("chunk-2", "资料乙", "另一份资料只说明部分流程。")], llm_client=client)
    assert result["status"] == "FAIL" and result["llm_calls"] == 2
    assert result["sections"][0]["support"] == "NOT_RUN"
    assert "至少有一个充分支持" in client.calls[1][1]["validation_errors"][0]


def test_non_factual_statements_have_no_recall_denominator_and_no_false_precision():
    client = JudgeClient("NON_FACTUAL", "UNSUPPORTED")
    result = verify_statement_support([section("请继续阅读。", refs=[])], [], llm_client=client)
    assert result["status"] == "PASS" and result["sections"][0]["support"] == "SUPPORTED"
    assert result["citation_recall"] is None and result["citation_precision"] is None


def test_unjudged_facts_remain_in_denominator_when_another_chapter_is_supported():
    client = JudgeClient(changes=[lambda _: None, ""])
    result = verify_statement_support([section("延迟降低20%。"), section("延迟降低30%。", "章节乙")],
        [passage(content="延迟降低20%。延迟降低30%。")], llm_client=client)
    assert result["status"] == "FAIL"
    assert result["citation_recall"] == result["citation_precision"] == 0.5


@pytest.mark.parametrize("limit_kind", ["statements", "sections", "material"])
def test_limits_preserve_all_statements_but_never_truncate_to_pass(monkeypatch, limit_kind):
    sections = [section("首句。末句。")] if limit_kind == "statements" else [section(), section(heading="章节乙")]
    if limit_kind == "statements":
        monkeypatch.setattr(verification, "MAX_STATEMENTS_PER_SECTION", 1)
    elif limit_kind == "sections":
        monkeypatch.setattr(verification, "MAX_SECTIONS", 1)
    else:
        monkeypatch.setattr(verification, "MAX_CONTEXT_CHARS", 1)
    client = JudgeClient()
    result = verify_statement_support(sections, [passage()], llm_client=client)
    assert result["status"] == "FAIL" and client.calls == []
    assert sum(len(s["statements"]) for s in result["sections"]) == 2
    assert all(s["support"] == "NOT_RUN" for s in result["sections"])
    assert result["execution_traces"] and result["execution_traces"][0]["status"] == "NOT_RUN"
