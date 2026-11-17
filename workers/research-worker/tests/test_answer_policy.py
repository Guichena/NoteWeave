import json
from copy import deepcopy

import pytest

from app.answer_policy import criterion, draft_answers
from app.narrative_report import NarrativeReportPolisher
from app.report_coverage import audit_report_coverage
from app.statement_support import frozen_facts


FACT = "fact:" + "a" * 48
EVIDENCE = "evidence:" + "b" * 48
REQ = "req:" + "c" * 48
CLAIM = "Atlas first provided the feature in version 1.0."


def data():
    return {"requirement_answer_policy_version": 1, "report_feedback_version": 1,
            "report_metadata_version": 1, "statement_support_version": 1, "report_coverage_version": 1,
            "narrative_llm_budget": 8, "question": "Which release first provided the feature?",
            "cells": [{"cell_key": "atlas:release", "entity_label": "Atlas", "candidate_value": CLAIM,
                       "evidence_keys": [EVIDENCE], "evidence_quotes": [{"evidence_key": EVIDENCE,
                           "quote": CLAIM, "source_url": "https://atlas.example/release", "source_title": "Release"}],
                       "facts": [{"fact_id": FACT, "claim_text": CLAIM, "evidence_keys": [EVIDENCE]}]}],
            "requirement_coverage": {"requirements": [{"requirement_id": REQ, "text": "Identify the first release",
                "question_quote": "Which release first provided the feature? Cite official sources and explain installation.",
                "cell_key": "atlas:release", "scope_type": "CELL", "status": "SATISFIED", "mandatory": True,
                "minimum_count": 1, "completion_mode": "SINGLE", "supporting_fact_ids": [FACT],
                "scope": {"temporal_mode": "HISTORICAL", "answer_contract": {"criterion": "Identify the first release",
                    "source_constraints": [{"policy": "PRIMARY_SOURCE", "question_quote": "Cite official sources"}]}}}]}}


class Client:
    def __init__(self, failure=None):
        self.calls = []; self.failure = failure

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, payload))
        if self.failure:
            raise self.failure
        if purpose == "research.synthesis.answers":
            return json.dumps({"answers": [{"requirement_id": item["requirement"]["requirement_id"],
                "text": CLAIM, "fact_ids": [FACT], "missing_conditions": []} for item in payload["targets"]]})
        if purpose == "research.synthesis.statement_support":
            return json.dumps({"checks": [{"statement_id": item["statement_id"], "verdict": "SUPPORTED",
                "fact_ids": [FACT]} for item in payload["statements"]]})
        return json.dumps({"items": [{"requirement_id": item["requirement"]["requirement_id"],
            "status": "ANSWERED", "statement_ids": [item["statement_id"] for item in item["statements"]],
            "gap_type": "NONE", "missing_detail": ""} for item in payload["requirements"]]})


def test_frozen_criterion_excludes_other_subquestions_and_provenance_quote():
    result = criterion(data()["requirement_coverage"]["requirements"][0])
    assert result["criterion"] == "Identify the first release"
    assert "question_quote" not in json.dumps(result)
    assert "installation" not in json.dumps(result)
    assert result["source_constraints"] == [{"policy": "PRIMARY_SOURCE"}]


@pytest.mark.parametrize("budget", [0, 1, 2, 3, 8, 16])
def test_answer_first_path_keeps_budget_and_final_statement_binding(budget):
    source = data(); source["narrative_llm_budget"] = budget
    client = Client()
    result = NarrativeReportPolisher(client).polish(source)
    assert result.llm_calls == len(client.calls) <= budget
    checks = {item["statement_id"]: item for item in result.narrative["statement_support"]["checks"]}
    answer = result.narrative["answers"][0]
    assert answer["text"] == " ".join(checks[key]["text"] for key in answer["statement_ids"])
    assert EVIDENCE in result.markdown
    if budget == 0:
        assert answer["status"] == "NOT_CHECKED"
        assert answer["reason_code"] == "BUDGET_EXHAUSTED"
    else:
        assert answer["status"] == "ANSWERED"
    for purpose, payload in client.calls:
        if purpose == "research.synthesis.requirement_coverage":
            assert "question_quote" not in json.dumps(payload["requirements"])
            citations = payload["requirements"][0]["statements"][0]["source_citations"]
            assert citations[0]["source_url"] == "https://atlas.example/release"


@pytest.mark.parametrize("error,reason", [(ValueError("bad JSON"), "INVALID_MODEL_OUTPUT"),
    (TimeoutError(), "PROVIDER_TIMEOUT"), (ConnectionError(), "PROVIDER_CONNECTION_ERROR"),
    (RuntimeError(), "PROVIDER_ERROR")])
def test_audit_failure_has_explicit_reason_and_never_claims_answered(error, reason):
    result = NarrativeReportPolisher(Client(error)).polish(data())
    assert result.narrative["report_coverage"]["complete"] is False
    assert result.narrative["answers"][0]["reason_code"] == reason


def test_model_unavailable_preserves_verified_facts_without_inventing_answers():
    result = NarrativeReportPolisher(None).polish(data())
    assert result.llm_calls == 0
    assert CLAIM in result.markdown
    assert result.narrative["answers"][0]["reason_code"] == "MODEL_UNAVAILABLE"


def test_zero_budget_with_no_client_reports_budget_exhaustion():
    source = data(); source["narrative_llm_budget"] = 0
    result = NarrativeReportPolisher(None).polish(source)
    assert result.narrative["answers"][0]["reason_code"] == "BUDGET_EXHAUSTED"


@pytest.mark.parametrize("failure,reason", [("connection", "PROVIDER_CONNECTION_ERROR"), ("timeout", "PROVIDER_TIMEOUT")])
def test_transport_failure_survives_empty_response_and_metered_wrapper(failure, reason):
    import httpx
    from openai import APIConnectionError, APITimeoutError
    from types import SimpleNamespace
    from app.llm_client import OpenAICompatibleLlmClient
    from app.synthesis_executor import _MeteredSynthesisClient

    request = httpx.Request("POST", "https://example.invalid/v1/chat/completions")
    error = APIConnectionError(request=request) if failure == "connection" else APITimeoutError(request=request)

    def fail(**_kwargs):
        raise error

    client = OpenAICompatibleLlmClient("test-key", "test-model", max_attempts=1, max_total_calls=8)
    client.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fail)))
    permits = []
    wrapper = _MeteredSynthesisClient(client, SimpleNamespace(require_synthesis_call=lambda *args: permits.append(args)), None)
    result = NarrativeReportPolisher(wrapper).polish(data())
    assert result.narrative["answers"][0]["reason_code"] == reason
    assert result.narrative["report_coverage"]["complete"] is False
    assert 0 < client.usage_summary()["provider_call_count"] == len(permits) <= 8
    assert client.call_records[-1]["termination_reason"] == reason


def test_successful_transport_with_malformed_json_is_invalid_model_output():
    from types import SimpleNamespace
    from app.llm_client import OpenAICompatibleLlmClient

    client = OpenAICompatibleLlmClient("test-key", "test-model", max_attempts=1, max_total_calls=8)
    response = {"choices": [{"message": {"content": "invalid JSON"}}]}
    client.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(
        create=lambda **kwargs: SimpleNamespace(model_dump=lambda: response))))
    result = NarrativeReportPolisher(client).polish(data())
    assert result.narrative["answers"][0]["reason_code"] == "INVALID_MODEL_OUTPUT"
    assert client.call_records[-1]["termination_reason"] == "SUCCESS"


def test_unknown_fact_in_answer_is_rejected():
    class BadClient(Client):
        def complete_json(self, purpose, payload):
            return json.dumps({"answers": [{"requirement_id": REQ, "text": "unsupported", "fact_ids": ["foreign"], "missing_conditions": []}]})
    source = data()
    answers, calls = draft_answers(source, frozen_facts(source["cells"]), BadClient(), 1)
    assert calls == 1
    assert answers[REQ]["text"] == ""
    assert answers[REQ]["reason_code"] == "INVALID_MODEL_OUTPUT"


def test_optional_gap_is_visible_but_does_not_expand_mandatory_completion():
    source = data()
    optional = deepcopy(source["requirement_coverage"]["requirements"][0])
    optional.update(requirement_id="req:"+"d"*48, mandatory=False, status="PENDING")
    source["requirement_coverage"]["requirements"].insert(0, optional)
    result = NarrativeReportPolisher(Client()).polish(source)
    coverage = result.narrative["report_coverage"]
    assert coverage["complete"] is True
    assert len(coverage["items"]) == 2
    assert coverage["items"][0]["requirement_id"] == REQ
    assert coverage["items"][1]["status"] == "PARTIAL"


def test_satisfied_requirement_expression_gap_does_not_trigger_fact_search():
    class ExpressionClient(Client):
        def complete_json(self, purpose, payload):
            if purpose == "research.synthesis.requirement_coverage":
                return json.dumps({"items": [{"requirement_id": REQ, "status": "PARTIAL", "gap_type": "MISSING_FACTS",
                    "missing_detail": "State the first version directly", "statement_ids": [payload["requirements"][0]["statements"][0]["statement_id"]]}]})
            return super().complete_json(purpose, payload)
    result = NarrativeReportPolisher(ExpressionClient()).polish(data())
    assert result.narrative["report_coverage"]["items"][0]["gap_type"] == "EXPRESSION"
    assert result.narrative["answers"][0]["missing_conditions"] == ["State the first version directly"]


@pytest.mark.parametrize("text", [
    "Official documentation for version 2.0 describes the feature.",
    "As of 2026-10-21 Atlas first provided the feature in version 1.0.",
    "Atlas first provided the feature in version 1.1.",
])
def test_answer_draft_cannot_borrow_version_from_url_or_date_from_question(text):
    class BadNumberClient(Client):
        def complete_json(self, purpose, payload):
            if purpose == "research.synthesis.answers":
                self.calls.append((purpose,payload))
                return json.dumps({"answers": [{"requirement_id": REQ, "text": text,
                    "fact_ids": [FACT], "missing_conditions": []}]})
            return super().complete_json(purpose,payload)
    source = data()
    source["cells"][0]["evidence_quotes"][0]["source_url"] = "https://atlas.example/docs/2.0"
    result = NarrativeReportPolisher(BadNumberClient()).polish(source)
    assert text not in result.markdown
    assert CLAIM in result.markdown


def test_local_answer_repair_uses_the_same_literal_guard():
    from app.answer_policy import guard_paragraphs
    source = data()
    paragraphs = [{"text": "Atlas provides it in version 999.0.","cell_keys":["atlas:release"],"evidence_keys":[EVIDENCE]}]
    assert guard_paragraphs(paragraphs,source["cells"]) == []
