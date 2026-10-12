import json
from types import SimpleNamespace

from app.discovery_executor import DiscoveryExecutor, discovery_confidence
from app.task_snapshot_contract import snapshot_digest
from app.agent_task_client import AgentTaskClaim
from test_controlled_role_executors import _claim, _command


def claim(mode="SOURCES_ONLY", search_budget=9):
    old = _claim("WIDE_DISCOVERY", {"discovery_input": {
        "schema_version": "research-discovery-input.v2", "input_digest": "sha256:discovery",
        "question": "Compare storage systems", "plan_revision": 1, "entity_set_version": 1,
        "query_limit": 3, "table": {"entities": [{"key": "mysql", "label": "MySQL"}],
            "columns": [{"key": "license", "label": "License"}], "verified_cells": []}}})
    data = json.loads(old.task_snapshot_json)
    data.pop("snapshot_digest")
    data["budget"] = {"llm_calls": 4, "search_calls": search_budget}
    data["source_policy"] = {"retrieval_mode": mode, "allow_external_search": True}
    digest = snapshot_digest(data)
    return AgentTaskClaim(old.agent_task_id, old.lease_epoch, old.fencing_token, "[]", json.dumps(data["budget"]),
        json.dumps({**data, "snapshot_digest": digest}), digest)


class Search:
    def __init__(self):
        self.permits, self.queries = [], []
    def require_permit(self, snapshot, tool):
        self.permits.append(tool)
    def search_workspace_windows(self, snapshot, *, queries, limit):
        self.queries.extend(queries)
        return [SimpleNamespace(source_id="source-a", source_title="Storage",
            source_window_id="window-a", window_text="The license allows redistribution.")]


class Model:
    def __init__(self, invalid=False):
        self.calls = []
        self.invalid = invalid
    def usage_summary(self):
        return {"provider_call_count": len(self.calls)}
    def complete_json(self, purpose, payload):
        self.calls.append((purpose, payload))
        if self.invalid:
            return "{}"
        if purpose == "research.discovery.queries":
            return json.dumps({"queries": ["storage license", "storage vendors", "storage limits"]})
        observation = payload["observations"][0]
        return json.dumps({"proposals": [{"proposal_type": "DIMENSION", "label": "Redistribution",
            "search_query": "redistribution conditions", "rationale": "补充许可条件维度",
            "supports": [{"observation_key": observation["observation_key"],
                "quote_text": "allows redistribution"}]}]})


def test_three_real_queries_and_exact_supported_scope_proposal():
    search, model = Search(), Model()
    completion = DiscoveryExecutor("worker-a", search, llm_client=model)(_command(), claim())
    assert len(search.queries) == 3
    assert search.permits == ["search"] * 3
    assert completion.budget_usage.search_calls == 3
    assert completion.budget_usage.llm_calls == 2
    assert completion.role_result["proposals"][0]["supporting_domain_count"] == 1
    assert completion.role_result["proposals"][0]["confidence_ppm"] == 500000
    assert completion.candidates == completion.evidence == ()
    assert model.calls[0][1]["table"]["entities"][0]["label"] == "MySQL"


def test_both_invalid_model_outputs_retry_once_then_only_search_query_fallback():
    search, model = Search(), Model(invalid=True)
    completion = DiscoveryExecutor("worker-a", search, llm_client=model)(_command(), claim())
    assert completion.budget_usage.llm_calls == 4
    assert all("validation_errors" in model.calls[i][1] for i in (1, 3))
    assert {p["proposal_type"] for p in completion.role_result["proposals"]} == {"SEARCH_QUERY"}
    assert completion.role_result["generation_mode"] == "RULE"


def test_unavailable_external_provider_returns_empty_supported_proposals(monkeypatch):
    def unavailable():
        raise RuntimeError("provider unavailable")
    monkeypatch.setattr("app.discovery_executor.build_web_search_adapter", unavailable)
    completion = DiscoveryExecutor("worker-a", Search(), enable_llm=False)(_command(), claim("WEB_ONLY"))
    assert completion.role_result["proposals"] == []
    assert completion.budget_usage.search_calls >= 1
    assert "EXTERNAL_SEARCH_UNAVAILABLE" in completion.role_result["degradation_reasons"]


def test_lower_budget_bounds_actual_query_count():
    search = Search()
    completion = DiscoveryExecutor("worker-a", search, enable_llm=False)(_command(), claim(search_budget=1))
    assert len(search.queries) == completion.budget_usage.search_calls == 1


def test_domain_and_copy_deduplication_with_authority_weight():
    first = {"source_domain": "docs.vendor.co.uk", "lineage_digest": "a", "snippet": "Licensing terms"}
    duplicate = {**first, "source_domain": "www.vendor.co.uk"}
    second = {"source_domain": "foundation.org", "lineage_digest": "b", "snippet": "Different redistributable license"}
    assert discovery_confidence([first, duplicate], []) == (1, 500000)
    assert discovery_confidence([first, second], []) == (2, 666666)
    assert discovery_confidence([first, second], ["vendor.co.uk", "foundation.org"]) == (2, 800000)


def test_external_adapter_executes_query_and_filters_private_result_urls():
    class Web:
        def __init__(self):
            self.queries = []
        def search(self, task, plan, remaining_budget=None):
            self.queries.extend(plan.query_set)
            assert task.retrieval_mode == "WEB_ONLY"
            return [SimpleNamespace(source_id="web-a", source_title="Official site",
                url=url, snippet="Current license terms allow attribution.")
                for url in ["https://vendor.org/license", "http://127.0.0.1/internal"]]
    search, web = Search(), Web()
    completion = DiscoveryExecutor("worker-a", search, search_adapter=web, enable_llm=False)(
        _command(), claim("WEB_ONLY", search_budget=1))
    assert web.queries == ["Compare storage systems"]
    assert search.queries == []
    assert search.permits == ["search"]
    assert completion.budget_usage.search_calls == 1
    assert completion.role_result["search_observations"][0]["source_url"] == "https://vendor.org/license"
    assert len(completion.role_result["search_observations"]) == 1
    assert completion.role_result["proposals"][0]["proposal_type"] == "SEARCH_QUERY"


def test_fabricated_support_quote_is_retried_then_replaced_by_supported_query():
    class WrongQuote(Model):
        def complete_json(self, purpose, payload):
            result = json.loads(super().complete_json(purpose, payload))
            if purpose == "research.discover":
                result["proposals"][0]["supports"][0]["quote_text"] = "This sentence is fabricated."
            return json.dumps(result)
    model = WrongQuote()
    completion = DiscoveryExecutor("worker-a", Search(), llm_client=model)(_command(), claim())
    assert completion.budget_usage.llm_calls == 3
    assert {p["proposal_type"] for p in completion.role_result["proposals"]} == {"SEARCH_QUERY"}
    assert "validation_errors" in model.calls[-1][1]
    assert "fabricated" not in json.dumps(completion.role_result["proposals"])


def test_large_multibyte_search_snippets_fit_the_atomic_completion_envelope():
    class LargeSearch(Search):
        def search_workspace_windows(self, snapshot, *, queries, limit):
            self.queries.extend(queries)
            return [SimpleNamespace(source_id=f"source-{i}", source_title=f"Research {i}",
                source_window_id=f"window-{i}", window_text=("资料原始研究结论" + str(i)) * 400)
                for i in range(8)]
    search = LargeSearch()
    completion = DiscoveryExecutor("worker-a", search, enable_llm=False)(_command(), claim())
    # 信封构造器自身校验 262144 字节上限，裁剪检索摘要不改变实际执行的查询数。
    assert 0 < len(completion.role_result["search_observations"]) < 16
    assert completion.budget_usage.search_calls == 2
    assert completion.role_result["proposals"]
