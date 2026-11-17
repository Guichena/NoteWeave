import json
from types import SimpleNamespace

import pytest

from app.fact_selection import select_facts
from app.requirement_definitions import compile_requirements
from app.repair_queries import plan_repair_queries
from app.cell_query_planner import CellColumn, CellQueryContext

REQ = "req:" + "a" * 48
OTHER = "req:" + "b" * 48


def requirement(identity=REQ, cell="python:pep", kind="IDENTIFIER", temporal="TIME_INVARIANT"):
    return {"requirement_id": identity, "cell_key": cell, "text": "Name the relevant PEP", "origin": "EXPLICIT",
            "mandatory": True, "completion_mode": "SINGLE", "minimum_count": 1, "scope": {"temporal_mode": temporal,
            "answer_contract": {"version": 2, "answer_kind": kind, "criterion": "Name the relevant PEP",
                "conditions": ["Identify the PEP number and title"], "source_constraints": []}}}


def material(key, claim, cell="python:pep", score=950000):
    return {"evidence_key": key, "claim_text": claim, "quote_text": claim, "cell_key": cell,
            "source_id": "source", "window_id": "window", "support_score_ppm": score}


class SelectionClient:
    def __init__(self, annotations=None, failure=None):
        self.annotations = annotations or {}; self.calls = []; self.failure = failure

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, payload))
        if self.failure:
            raise self.failure
        items = []
        for item in payload["facts"]:
            match = self.annotations.get(item["evidence_key"], {})
            items.append({"evidence_key": item["evidence_key"], "duplicate_group": match.get("group", item["evidence_key"]),
                "relevance": [{"requirement_id": req["requirement_id"], "condition_indexes": match.get("indexes", []),
                               "source_types": match.get("source_types", []),
                               "temporal_match": match.get("temporal", True)} for req in item["requirements"]]})
        return json.dumps({"items": items})


def contract(reqs=None):
    reqs = reqs or [requirement()]
    return {"completion_gap_policy_version": 1, "requirements": reqs,
            "missing_requirement_ids": [req["requirement_id"] for req in reqs], "facts": []}


def test_direct_pep_evidence_survives_a_limit_before_repeated_release_background():
    rows = [material("background", "Starting with the 3.13 release free threading was experimental."),
            material("pep", "PEP 703: Making the Global Interpreter Lock Optional in CPython", score=700000)]
    client = SelectionClient({"pep": {"indexes": [0]}})
    selected, diagnostic = select_facts(SimpleNamespace(query_policy={"requirement_contract": contract()}), rows,
        [{"requirement_id": REQ, "evidence_keys": ["background", "pep"], "complete": False}], limit=1, client=client)
    assert [item["evidence_key"] for item in selected] == ["pep"]
    assert len(client.calls) == 1
    assert diagnostic["deferred"][0]["claim_text"] == rows[0]["claim_text"]


def test_semantic_duplicate_sources_do_not_displace_a_distinct_required_finding():
    req = requirement(); req.update(completion_mode="DISTINCT_FACTS", minimum_count=2)
    rows = [material("one", "Finding one"), material("translated", "第一项发现"), material("two", "Finding two", score=600000)]
    client = SelectionClient({"one": {"indexes": [0], "group": "same"}, "translated": {"indexes": [0], "group": "same"}, "two": {"indexes": [0]}})
    selected, _ = select_facts(SimpleNamespace(query_policy={"requirement_contract": contract([req])}), rows, [], limit=2, client=client)
    assert "two" in {item["evidence_key"] for item in selected}


def test_equivalent_facts_preserve_both_explicitly_required_source_types():
    req = requirement()
    req["scope"]["answer_contract"]["source_constraints"] = [{"policy": "PRIMARY_SOURCE", "source_types": ["SPECIFICATION", "OFFICIAL_DOCUMENTATION"]}]
    rows = [material("pep", "The defining specification."), material("doc", "Official documentation describes the same fact.", score=600000),
            material("extra", "A repeated specification source.", score=990000)]
    client = SelectionClient({"pep": {"indexes": [0], "group": "same", "source_types": ["SPECIFICATION"]},
        "doc": {"indexes": [0], "group": "same", "source_types": ["OFFICIAL_DOCUMENTATION"]},
        "extra": {"indexes": [0], "group": "same", "source_types": ["SPECIFICATION"]}})
    selected, _ = select_facts(SimpleNamespace(query_policy={"requirement_contract": contract([req])}), rows, [], limit=2, client=client)
    assert "doc" in {item["evidence_key"] for item in selected}


def test_current_status_prioritizes_applicable_evidence_over_historical_background():
    req = requirement(kind="FACT", temporal="CURRENT")
    rows = [material("old", "Initially experimental."), material("current", "Currently supported for the specified release.", score=600000)]
    client = SelectionClient({"old": {"indexes": [0], "temporal": False}, "current": {"indexes": [0]}})
    selected, _ = select_facts(SimpleNamespace(query_policy={"requirement_contract": contract([req])}), rows, [], limit=1, client=client)
    assert selected[0]["evidence_key"] == "current"


def test_selector_failure_preserves_budget_and_mandatory_slot_distribution():
    rows = [material("a", "One"), material("b", "Two", "python:status")]
    client = SelectionClient(failure=TimeoutError())
    reqs = [requirement(), requirement(OTHER, "python:status", "FACT", "CURRENT")]
    selected, _ = select_facts(SimpleNamespace(query_policy={"requirement_contract": contract(reqs)}), rows,
        [{"requirement_id": req["requirement_id"], "evidence_keys": [row["evidence_key"]]} for req, row in zip(reqs, rows)], limit=2, client=client)
    assert len(client.calls) == 1 and len(selected) == 2


def definition():
    return {"text": "Name the relevant PEP", "question_quote": "PEP 编号和标题", "dimension": "Specification", "entities": [],
            "completion_mode": "SINGLE", "minimum_count": 1, "count_expression": "", "answer_kind": "IDENTIFIER",
            "temporal_mode": "TIME_INVARIANT", "conditions": ["PEP 编号", "PEP 标题"],
            "source_constraints": [{"policy": "PRIMARY_SOURCE", "question_quote": "引用官方文档"}]}


def test_structured_definition_keeps_citation_constraint_separate_from_identity_conditions():
    result = compile_requirements([definition()], "目前支持状态，PEP 编号和标题，引用官方文档", [{"label": "Specification"}], [{"label": "Python"}], required=True, structured=True)
    assert result[0]["temporal_mode"] == "TIME_INVARIANT"
    assert result[0]["conditions"] == ["PEP 编号", "PEP 标题"]
    assert result[0]["source_constraints"][0]["question_quote"] == "引用官方文档"


@pytest.mark.parametrize("field,value", [("answer_kind", "PROVENANCE"), ("temporal_mode", "CURRENT"), ("conditions", []),
    ("source_constraints", [{"policy": "PRIMARY_SOURCE", "question_quote": "invented source constraint"}])])
def test_invalid_or_expanded_structured_requirement_is_rejected(field, value):
    raw = definition(); raw[field] = value
    with pytest.raises(ValueError):
        compile_requirements([raw], "PEP 编号和标题，引用官方文档", [{"label": "Specification"}], [{"label": "Python"}], required=True, structured=True)


def test_current_status_repair_rejects_invented_version_and_targets_release_evidence():
    class Client:
        calls = 0
        def complete_json(self, *_):
            self.calls += 1
            return json.dumps({"strategy": "NARROW", "queries": ["Python 99.9 free threading status"]})
    client = Client()
    context = CellQueryContext("Current support", "CPython free-threaded build", (CellColumn("status", "Current status"),),
                               authoritative_domains=("python.org",), as_of_date="2026-10-21")
    target = {"dispatch_version": 1, "repair_cause": "MARGINAL_GAIN_EXHAUSTED", "actions": [{
        "action": "POPULATE_REQUIREMENTS", "condition_targets": [{"requirement_id": REQ, "temporal_mode": "CURRENT"}]}]}
    plan, _ = plan_repair_queries(context, target, client)
    assert client.calls == 2
    assert plan.method == "RULE"
    assert all("99.9" not in spec.query for spec in plan.specs)
    assert any("release" in spec.query for spec in plan.specs)
    assert any("site:python.org" in spec.query for spec in plan.specs)
    assert any("site:" not in spec.query for spec in plan.specs)


def test_initial_current_query_does_not_borrow_a_version_from_the_history_column():
    from app.cell_query_planner import _validate
    context = CellQueryContext("First release and current status", "Python", (CellColumn("history", "First release"), CellColumn("status", "Current status")),
        requirement_targets=({"column_key": "history", "temporal_mode": "HISTORICAL", "criterion": "first release"},
                             {"column_key": "status", "temporal_mode": "CURRENT", "criterion": "current status"}))
    specs, rejected = _validate({"queries": [{"query": "Python 3.13 introduced free threading", "column_keys": ["history"]},
                                            {"query": "Python 99.9 current free threading status", "column_keys": ["status"]}]}, context)
    assert len(specs) == 1 and specs[0].column_keys == ["history"]
    assert "unspecified release" in rejected[0]["reason"]


@pytest.mark.parametrize("status,invalid,expected", [("EXPANDED", [0], False), ("PROVENANCE_ONLY", [], False), ("VALID", [], True)])
def test_atomic_condition_verdict_is_required_in_addition_to_clause_mapping(status, invalid, expected):
    from app.planning_completeness import audit_planning
    class Client:
        def complete_json(self, *_):
            return json.dumps({"clauses": [{"clause_id": "clause:0", "status": "MAPPED", "requirement_indexes": [0]}],
                "requirement_checks": [{"requirement_index": 0, "status": status, "invalid_condition_indexes": invalid}]})
    result = audit_planning({"completion_gap_policy_version": 1, "question": "引用官方文档",
        "requested_clauses": [{"clause_id": "clause:0", "text": "引用官方文档"}]},
        {"user_requirements": [{"text": "引用官方文档说明", "conditions": ["安装方式"]}]}, Client())
    assert result["schema_version"] == "research-planning-completeness.v2"
    assert result["complete"] is expected
    assert result["requirement_checks"][0]["status"] == status
