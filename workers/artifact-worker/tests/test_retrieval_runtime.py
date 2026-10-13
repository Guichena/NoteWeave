"""章节检索、冻结边界、预算与接口降级的行为回归。"""

import json
from hashlib import sha256
from urllib.error import URLError

import pytest

from app.callback import ArtifactCallbackHttpError, JavaArtifactCallbackClient
from app.compiler import build_execution_plan
from app.content_runtime import build_canonical_content_objects
from app.generation_runtime import generate_artifact_sections
from app.models import ArtifactTaskInput, ArtifactExecutionPlan
from app.retrieval_runtime import execute_retrieve_node
from app.llm_client import FakeLlmClient
import app.retrieval_runtime as retrieval_runtime


def fixture():
    task = ArtifactTaskInput.model_validate({"task_id": "task", "workspace_id": "workspace",
        "target_id": "artifact", "input_snapshot_id": "input-1",
        "source_scope": [{"source_id": "source", "source_snapshot_id": "snapshot-1", "title": "资料",
            "sample_text": "数据恢复通过日志重放完成。错误处理记录失败原因。"}],
        "control_pack": {"pack_type": "artifact", "target_key": "study_guide", "task_neighborhood": "ARTIFACT"},
        "input_payload": {"skill_key": "study_guide", "user_requirement": "解释数据恢复和错误处理"}})
    plan = build_execution_plan(task)
    plan.outline = ["数据恢复", "错误处理"]
    plan.execution_spec.focus_points = []
    return task, plan, build_canonical_content_objects(task)


def response(task, content="冻结章节正文", **changes):
    return {"task_id": task.task_id, "input_snapshot_id": task.input_snapshot_id,
        "passages": [{"chunk_id": "chunk-1", "source_id": "source", "source_snapshot_id": "snapshot-1",
            "chunk_no": 9, "title": "资料", "location": "page 9", "content": content, "score": 2.0}],
        "degraded": False, "degradation_reasons": [], **changes}


def test_each_heading_has_an_independent_query_and_shared_passages_are_deduplicated():
    task, plan, objects = fixture()
    calls = []
    def search(task_id, query, limit):
        calls.append((task_id, query, limit))
        return response(task)
    packs, sources, trace, node = execute_retrieve_node(task, plan, objects, search)
    assert len(calls) == 2 and all(c[0] == "task" and c[2] == 6 for c in calls)
    assert "章节 数据恢复" in calls[0][1] and "章节 错误处理" in calls[1][1]
    assert len(sources) == 1 and trace["unique_passage_count"] == 1
    assert all(p["method"] == "HOST_HYBRID" for p in packs)
    assert node.node_id == "material_retrieve" and node.verification_status == "PASS"


def test_budget_is_split_between_all_headings_and_never_consumed_by_first_source():
    task, plan, objects = fixture()
    plan.retrieval_node.total_context_chars = 1001
    packs, sources, trace, _ = execute_retrieve_node(task, plan, objects, lambda *args: response(task, "字" * 2000))
    assert [p["budget_chars"] for p in packs] == [501, 500]
    assert [p["used_chars"] for p in packs] == [501, 500]
    assert len(sources[0]["content"]) == 501
    assert all(p["passages"][0]["truncated"] for p in packs)


def test_local_bm25_can_find_facts_after_six_thousand_characters_and_later_sources():
    task, plan, objects = fixture()
    plan.outline = ["日志重放恢复"]
    plan.execution_spec.goal = "日志重放恢复"
    objects[0].plain_text = "无关背景。" * 2500 + "日志重放恢复通过恢复点重新执行，独特事实在末尾。"
    task.source_scope.append(task.source_scope[0].model_copy(update={"source_id": "source-2", "title": "恢复说明"}))
    later = objects[0].model_copy(update={"cco_id": "later", "title": "恢复说明",
        "plain_text": "日志重放恢复需要校验恢复点，第二份资料的依据。", "source_trace": ["source:source-2"]})
    packs, sources, _, _ = execute_retrieve_node(task, plan, [*objects, later])
    text = "\n".join(s["content"] for s in sources)
    assert "独特事实在末尾" in text and "第二份资料的依据" in text
    assert packs[0]["method"] == "LOCAL_BM25"


def test_provider_degradation_merges_scoped_hits_with_local_material_and_records_reason():
    task, plan, objects = fixture()
    packs, sources, trace, node = execute_retrieve_node(task, plan, objects, lambda *args: response(
        task, degraded=True, degradation_reasons=["INDEX_UNAVAILABLE"]))
    assert len(sources) > 1 and packs[0]["method"] == "HOST_HYBRID_WITH_LOCAL_BM25"
    assert "INDEX_UNAVAILABLE" in trace["degradation_reasons"]
    assert "ARTIFACT_RETRIEVAL_LOCAL_BM25_FALLBACK" in trace["degradation_reasons"]
    assert node.verification_status == "WARN"


def test_degraded_host_and_local_window_with_identical_content_only_use_budget_once():
    task, plan, _ = fixture()
    text = "冻结章节正文"
    task.source_scope[0].material_windows = [{"window_id": "window-alias", "chunk_no": 99,
        "window_no": 0, "content": text, "checksum_sha256": sha256(text.encode("utf-8")).hexdigest()}]
    objects = build_canonical_content_objects(task)
    packs, sources, _, _ = execute_retrieve_node(task, plan, objects, lambda *args: response(
        task, text, degraded=True, degradation_reasons=["INDEX_UNAVAILABLE"]))
    assert all([p["chunk_id"] for p in pack["passages"]] == ["chunk-1"] for pack in packs)
    assert all(pack["used_chars"] == len(text) for pack in packs)
    assert [source["chunk_id"] for source in sources] == ["chunk-1"]


def test_identical_content_from_different_sources_keeps_both_source_identities():
    task, plan, _ = fixture()
    text = "冻结章节正文"
    source = task.source_scope[0].model_copy(update={"source_id": "second-source", "title": "第二份资料",
        "material_windows": [{"window_id": "second-window", "chunk_no": 9, "window_no": 0,
            "content": text, "checksum_sha256": sha256(text.encode("utf-8")).hexdigest()}]})
    task.source_scope.append(source)
    objects = build_canonical_content_objects(task)
    packs, sources, _, _ = execute_retrieve_node(task, plan, objects, lambda *args: response(
        task, text, degraded=True, degradation_reasons=["INDEX_UNAVAILABLE"]))
    assert all({p["source_id"] for p in pack["passages"]} == {"source", "second-source"} for pack in packs)
    assert {source["source_id"] for source in sources} == {"source", "second-source"}


def test_all_chapters_reuse_the_local_corpus_tokenization(monkeypatch):
    task, plan, objects = fixture()
    plan.outline = [f"章节 {index}" for index in range(80)]
    corpus = retrieval_runtime._local_corpus(task, objects)
    corpus_texts = {p["title"] + " " + p["content"] for p in corpus}
    calls = []
    original = retrieval_runtime._terms

    def tracked(text):
        calls.append(text)
        return original(text)

    monkeypatch.setattr(retrieval_runtime, "_terms", tracked)
    packs, _, _, _ = execute_retrieve_node(task, plan, objects)
    assert len(packs) == 80
    assert sum(text in corpus_texts for text in calls) == len(corpus)
    assert len(calls) == len(corpus) + len(plan.outline)


def test_healthy_host_does_not_build_unused_local_bm25_index(monkeypatch):
    task, plan, objects = fixture()

    def forbidden(*args):
        raise AssertionError("宿主检索正常时不应创建本地 BM25 索引")

    monkeypatch.setattr(retrieval_runtime, "_build_bm25_index", forbidden)
    execute_retrieve_node(task, plan, objects, lambda *args: response(task))


@pytest.mark.parametrize("change", ["task", "input", "source", "snapshot", "shape"])
def test_invalid_or_out_of_scope_response_is_rejected_without_local_fallback(change):
    task, plan, objects = fixture()
    raw = response(task)
    if change == "task": raw["task_id"] = "other-task"
    if change == "input": raw["input_snapshot_id"] = "other-input"
    if change == "source": raw["passages"][0]["source_id"] = "other-source"
    if change == "snapshot": raw["passages"][0]["source_snapshot_id"] = "latest-snapshot"
    if change == "shape": raw["passages"][0]["content"] = 10
    with pytest.raises(ValueError):
        execute_retrieve_node(task, plan, objects, lambda *args: raw)


def test_empty_scope_never_calls_workspace_search_and_uses_direct_input():
    task, plan, objects = fixture()
    task.source_scope = []
    def forbidden(*args): raise AssertionError("空资料范围不能调用混合检索")
    packs, _, trace, _ = execute_retrieve_node(task, plan, objects, forbidden)
    assert packs[0]["passages"] and "ARTIFACT_RETRIEVAL_LOCAL_INPUT" in trace["degradation_reasons"]


def test_partial_replay_cannot_retrieve_or_generate_from_cached_material():
    task, plan, objects = fixture()
    task.replay_availability = "PARTIAL"
    with pytest.raises(ValueError, match="不可完整重放"):
        execute_retrieve_node(task, plan, objects)


def test_generation_receives_chapter_materials_and_retrieval_diagnostics():
    task, plan, objects = fixture()
    llm = FakeLlmClient({"artifact.generate": json.dumps({"sections": [
        {"heading": h, "body": "冻结正文", "source_refs": ["资料"]} for h in plan.outline]})})
    _, trace = generate_artifact_sections(task_input=task, plan=plan, canonical_content_objects=objects, llm_client=llm)
    payload = llm.calls[0][1]
    assert len(payload["section_materials"]) == 2 and trace["retrieval"]["node_kind"] == "RETRIEVE"
    assert all(pack["used_chars"] <= pack["budget_chars"] for pack in payload["section_materials"])


def test_host_retrieved_fact_reaches_generated_result_and_is_preserved_as_frozen_material():
    from app.runner import run_artifact_task
    task, _, _ = fixture()
    remote_fact = "冻结资料末尾的独特事实：恢复任务按日志序号重放。"
    assert remote_fact not in task.source_scope[0].sample_text
    _, result = run_artifact_task(task, passage_search=lambda *args: response(task, remote_fact))
    assert remote_fact in result.result_payload["markdown"]
    assert any(remote_fact in obj["plain_text"] and obj["metadata"].get("chunk_id") == "chunk-1"
               for obj in result.result_payload["canonical_content_objects"])
    assert result.result_payload["generation_trace"]["retrieval"]["unique_passage_count"] == 1
    assert result.result_payload["node_traces"][0]["node_kind"] == "RETRIEVE"


@pytest.mark.parametrize("status", [401, 403, 409])
def test_authentication_revocation_or_stale_delivery_errors_do_not_fall_back(status):
    client = JavaArtifactCallbackClient("http://host")
    def rejected(*args): raise ArtifactCallbackHttpError(status, "拒绝读取")
    client._request = rejected
    with pytest.raises(ArtifactCallbackHttpError):
        client.retrieve_passages("task", "查询", 6, input_snapshot_id="input")


@pytest.mark.parametrize("status", [429, 500, 503, 504])
def test_unavailable_endpoint_returns_explicit_degradation(status):
    client = JavaArtifactCallbackClient("http://host")
    def unavailable(*args): raise ArtifactCallbackHttpError(status, "服务不可用")
    client._request = unavailable
    raw = client.retrieve_passages("task", "查询", 6, input_snapshot_id="input")
    assert raw["input_snapshot_id"] == "input" and raw["degraded"] and not raw["passages"]


def test_actual_urlopen_network_error_returns_degradation(monkeypatch):
    client = JavaArtifactCallbackClient("http://host")

    def unavailable(*args, **kwargs):
        raise URLError("连接被拒绝")

    monkeypatch.setattr("app.callback.credential_safe_urlopen", unavailable)
    raw = client.retrieve_passages("task", "查询", 6, input_snapshot_id="input")
    assert raw["input_snapshot_id"] == "input" and raw["degraded"] and not raw["passages"]
    assert raw["degradation_reasons"] == ["ARTIFACT_RETRIEVAL_ENDPOINT_UNAVAILABLE"]


def test_unrelated_runtime_error_is_not_hidden_by_retrieval_fallback():
    client = JavaArtifactCallbackClient("http://host")

    def invalid(*args):
        raise RuntimeError("响应解析失败")

    client._request = invalid
    with pytest.raises(RuntimeError, match="响应解析失败"):
        client.retrieve_passages("task", "查询", 6, input_snapshot_id="input")


def test_old_checkpoint_gets_default_retrieval_node_without_recompiling_intent():
    _, plan, _ = fixture()
    raw = plan.model_dump()
    raw.pop("retrieval_node")
    restored = ArtifactExecutionPlan.model_validate(raw)
    assert restored.retrieval_node.node_kind == "RETRIEVE"
    assert restored.execution_spec.compilation_trace == plan.execution_spec.compilation_trace
