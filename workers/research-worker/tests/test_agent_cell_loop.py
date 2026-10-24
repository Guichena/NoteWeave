import json
from dataclasses import dataclass, field, replace
from types import SimpleNamespace

from app import agent_cell_loop, deep_cell_executor as cells
from app.agent_cell_loop import continue_research, next_step


@dataclass
class _Plan:
    research_schema: object
    query_specs: list = field(default_factory=list)
    query_set: list = field(default_factory=list)
    stop_contract: dict = field(default_factory=dict)

    def model_copy(self, update):
        return replace(self, **update)


@dataclass
class _Window:
    window_id: str
    url: str
    adapter: str = "workspace"
    snapshot_status: str = "WORKSPACE"

    def model_copy(self, update):
        return replace(self, **update)


class _Llm:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.payloads = []

    def complete_json(self, purpose, payload):
        self.payloads.append((purpose, payload))
        return json.dumps(self.responses.pop(0)) if self.responses else "{}"


def _column(key):
    return SimpleNamespace(key=key, label=key.title(), description="", requirements=())


def _hit(url):
    return SimpleNamespace(url=url, source_title="t " + url, snippet="s", adapter="external_url")


def test_next_step_only_reads_known_unread_urls_and_drops_repeated_queries():
    llm = _Llm({"columns": [{"column_key": "algo", "status": "PARTIAL", "missing": "default method"}],
                "queries": ["envoy default load balancing policy", "Envoy load balancing", "x" * 300],
                "read_urls": ["https://a/latest", "https://unknown"], "stop": False})
    step = next_step(llm, entity="Envoy", question="q", columns=[_column("algo")], raw_by_column={}, windows=[],
                     unread=[_hit("https://a/latest")], tried_queries=["Envoy load balancing"], round_index=2, max_rounds=3)
    assert [spec.query for spec in step["queries"]] == ["envoy default load balancing policy"]
    assert step["read_urls"] == ["https://a/latest"]
    assert step["pending_columns"] == ["algo"] and step["stop"] is False


def test_next_step_stops_when_every_column_is_answered_or_output_is_invalid():
    answered = _Llm({"columns": [{"column_key": "algo", "status": "ANSWERED", "missing": ""}],
                     "queries": ["more"], "read_urls": [], "stop": False})
    assert next_step(answered, entity="E", question="q", columns=[_column("algo")], raw_by_column={}, windows=[],
                     unread=[], tried_queries=[], round_index=2, max_rounds=3)["stop"] is True
    # 模型没给某列状态时按未回答处理
    silent = _Llm({"columns": [], "queries": ["envoy panic threshold"], "read_urls": [], "stop": False})
    assert next_step(silent, entity="E", question="q", columns=[_column("algo")], raw_by_column={}, windows=[],
                     unread=[], tried_queries=[], round_index=2, max_rounds=3)["pending_columns"] == ["algo"]
    broken = _Llm("not an object")
    assert next_step(broken, entity="E", question="q", columns=[_column("algo")], raw_by_column={}, windows=[],
                     unread=[], tried_queries=[], round_index=2, max_rounds=3) is None


def test_second_round_searches_reads_and_extracts_only_pending_columns(monkeypatch):
    for name in ("_scope_search_plan_to_domains",):
        monkeypatch.setattr(cells, name, lambda snapshot, plan: plan)
    monkeypatch.setattr(cells, "_filter_external_hits_by_domain", lambda snapshot, hits, plan=None: list(hits))
    monkeypatch.setattr(cells, "_interleave_hits_by_domain", lambda hits: list(hits))
    monkeypatch.setattr(cells, "_prioritize_authoritative_hits", lambda snapshot, hits: list(hits))
    calls = {"permits": [], "extract_columns": []}

    class Toolchain:
        def search(self, task_input, plan, *, allow_external):
            return [_hit("https://docs/latest/lb"), _hit("https://blog/lb")]

        def fetch(self, task_input, plan, hits, *, allow_external):
            return [SimpleNamespace(url=hit.url) for hit in hits]

        def read(self, task_input, plan, documents, *, allow_external):
            return [_Window(window_id=f"window-{index}", url=doc.url) for index, doc in enumerate(documents, start=1)]

        def extract(self, task_input, plan, windows, *, llm_client):
            column = plan.research_schema.columns[0].key
            calls["extract_columns"].append(column)
            card = SimpleNamespace(column_key=column, window_id=windows[0].window_id, claim_text="default is round robin",
                                   source_title="t")
            return SimpleNamespace(accepted_cards=[card], rejected_cards=[], termination_reason=None, provider_receipt=None)

    executor = SimpleNamespace(toolchain=Toolchain(), permit_client=SimpleNamespace(
        require_permit=lambda snapshot, tool: calls["permits"].append(tool)))
    usage = []
    context = SimpleNamespace(toolbox=SimpleNamespace(require=lambda tool: None),
                              usage=SimpleNamespace(add=lambda key: usage.append(key)))
    columns = [_column("algo"), _column("health")]
    plan = _Plan(research_schema=SimpleNamespace(columns=columns, model_copy=lambda update: SimpleNamespace(**update)))
    monkeypatch.setattr(cells, "_extract_raw_cards", lambda toolchain, task_input, plan_, windows, llm, active, column_keys=None: (
        [SimpleNamespace()], {key: [toolchain.extract(None, SimpleNamespace(research_schema=SimpleNamespace(
            columns=[_column(key)])), windows, llm_client=llm).accepted_cards[0]] for key in sorted(column_keys)}))
    llm = _Llm({"columns": [{"column_key": "algo", "status": "PARTIAL", "missing": "default"},
                            {"column_key": "health", "status": "ANSWERED", "missing": ""}],
                "queries": ["envoy default lb policy"], "read_urls": [], "stop": False},
               {"columns": [{"column_key": "algo", "status": "ANSWERED"}, {"column_key": "health", "status": "ANSWERED"}],
                "queries": [], "read_urls": [], "stop": True})
    raw = {"algo": [SimpleNamespace(column_key="algo", window_id="window-1", claim_text="supports round robin", source_title="t")],
           "health": [SimpleNamespace(column_key="health", window_id="window-1", claim_text="active checks", source_title="t")]}
    results = [SimpleNamespace()]
    outcome = continue_research(executor, SimpleNamespace(query_policy={"entity_label": "Envoy", "query": "q"}), None, plan,
                                True, context, llm, lambda: None, hits=[_hit("https://docs/v1.5.0/lb")],
                                documents=[SimpleNamespace(url="https://docs/v1.5.0/lb")],
                                windows=[_Window(window_id="window-1", url="https://docs/v1.5.0/lb")],
                                raw_by_column=raw, results=results, tried_queries=["envoy lb"], max_rounds=3)
    assert calls["permits"] == ["search", "fetch", "read", "extract"]
    assert calls["extract_columns"] == ["algo"]
    assert usage == ["search_calls", "fetch_calls", "read_calls", "extract_calls"]
    assert [window.window_id for window in outcome.windows] == ["window-1", "r2-window-1", "r2-window-2"]
    assert len(raw["algo"]) == 2 and len(raw["health"]) == 1 and len(results) == 2
    assert outcome.queries == ["envoy lb", "envoy default lb policy"]
    assert [item["decision"] for item in outcome.trace] == ["CONTINUE", "STOP"]
    # 第二轮请模型决策时能看到第一轮的结论和来源
    findings = llm.payloads[0][1]["findings_by_column"]
    assert findings["algo"][0]["source_url"] == "https://docs/v1.5.0/lb"
    assert agent_cell_loop.AGENT_STEP_PURPOSE == llm.payloads[0][0]
