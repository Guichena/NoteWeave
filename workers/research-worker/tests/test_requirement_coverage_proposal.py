"""覆盖建议只能引用服务端能解析为当前事实或冻结历史事实的证据。"""

from app.requirement_coverage import coverage_proposal
import json

import pytest


REQUIREMENT = "req:" + "a" * 48
CONTRACT = {
    "schema_version": "research-requirement-contract.v2",
    "contract_digest": "sha256:" + "b" * 64,
    "facts": [],
}


def observation(*keys):
    return {"requirement_id": REQUIREMENT, "evidence_keys": list(keys), "complete": True}


def test_retained_lead_without_fact_cluster_cannot_claim_coverage():
    result = coverage_proposal(CONTRACT, [observation("lead")], ["lead"], [])
    assert result["observations"] == []


def test_removing_unclustered_evidence_keeps_remaining_coverage_partial():
    result = coverage_proposal(
        CONTRACT, [observation("fact", "lead")], ["fact", "lead"], [{"evidence_keys": ["fact"]}]
    )
    assert result["observations"] == [
        {"requirement_id": REQUIREMENT, "evidence_keys": ["fact"], "complete": False}
    ]


def test_frozen_historical_fact_can_support_coverage_without_new_cluster():
    contract = {**CONTRACT, "facts": [{"evidence_keys": ["previous"]}]}
    result = coverage_proposal(contract, [observation("previous")], [], [])
    assert result["observations"] == [observation("previous")]


def test_cluster_cannot_authorize_evidence_omitted_from_submission():
    result = coverage_proposal(CONTRACT, [observation("omitted")], [], [{"evidence_keys": ["omitted"]}])
    assert result["observations"] == []


def test_legacy_proposal_drops_incomplete_evidence_set():
    contract = {**CONTRACT, "schema_version": "research-requirement-contract.v1"}
    item = {"requirement_id": REQUIREMENT, "evidence_keys": ["fact", "lead"]}
    result = coverage_proposal(contract, [item], ["fact", "lead"], [{"evidence_keys": ["fact"]}])
    assert result["observations"] == []


def modern_contract():
    return {**CONTRACT, "requirement_answer_policy_version": 1, "requirements": [{
        "requirement_id": REQUIREMENT, "text": "Identify the defining PEP number and title",
        "question_quote": "Identify it and explain installation", "cell_key": "python:pep", "scope_type": "CELL",
        "scope": {"temporal_mode": "TIME_INVARIANT"}, "mandatory": True}]}


CLUSTERS = [{"cluster_key": "cluster", "cell_key": "python:pep", "claim_text": "PEP 703: Making the Global Interpreter Lock Optional in CPython",
             "evidence_keys": ["fact"]}]
EVIDENCE = [{"evidence_key": "fact", "quote_text": CLUSTERS[0]["claim_text"], "source_url": "https://peps.python.org/pep-0703/"}]


class RecheckClient:
    def __init__(self, result=None, error=None):
        self.result = result if result is not None else {"observations": [observation("fact")]}
        self.error = error; self.calls = []

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, payload))
        if self.error:
            raise self.error
        return json.dumps(self.result)


def test_clipped_coverage_becomes_complete_only_after_independent_recheck():
    client = RecheckClient()
    result = coverage_proposal(modern_contract(), [observation("fact", "lead")], ["fact", "lead"], CLUSTERS,
                               client=client, evidence=EVIDENCE)
    assert result["observations"] == [observation("fact")]
    assert len(client.calls) == 1
    purpose, payload = client.calls[0]
    assert purpose == "research.requirement_coverage.recheck"
    assert payload["targets"][0]["allowed_evidence_keys"] == ["fact"]
    assert payload["targets"][0]["facts"][0]["citations"][0]["quote"] == CLUSTERS[0]["claim_text"]
    assert "installation" not in json.dumps(payload["targets"])


@pytest.mark.parametrize("result", [
    {"observations": [{**observation("fact"), "complete": False}]},
    {"observations": [observation("foreign")]},
    {"observations": [observation("fact"), observation("fact")]},
    {"observations": [{**observation("fact"), "requirement_id": "req:" + "f" * 48}]},
    {"observations": [{**observation("fact"), "complete": "true"}]},
    {"observations": [{**observation("fact"), "extra": True}]},
    {},
])
def test_uncertain_or_invalid_recheck_preserves_partial_support(result):
    response = coverage_proposal(modern_contract(), [observation("fact", "lead")], ["fact"], CLUSTERS,
                                client=RecheckClient(result), evidence=EVIDENCE)
    assert response["observations"] == [{**observation("fact"), "complete": False}]


def test_recheck_provider_failure_preserves_partial_support():
    client = RecheckClient(error=TimeoutError())
    response = coverage_proposal(modern_contract(), [observation("fact", "lead")], ["fact"], CLUSTERS,
                                client=client, evidence=EVIDENCE)
    assert response["observations"] == [{**observation("fact"), "complete": False}]
    assert len(client.calls) == 1


@pytest.mark.parametrize("case", ["legacy", "complete", "other_cell", "no_quote", "oversized"])
def test_recheck_skips_ineligible_inputs(case):
    contract = modern_contract(); client = RecheckClient(); evidence = EVIDENCE
    item = {**observation("fact"), "complete": False}
    if case == "legacy":
        contract.pop("requirement_answer_policy_version")
    elif case == "complete":
        item["complete"] = True
    elif case == "other_cell":
        contract["requirements"][0]["cell_key"] = "python:release"
    elif case == "no_quote":
        evidence = []
    else:
        evidence = [{**EVIDENCE[0], "quote_text": "x" * 24000}]
    coverage_proposal(contract, [item], ["fact"], CLUSTERS, client=client, evidence=evidence)
    assert client.calls == []


def test_frozen_fact_with_literal_quote_can_be_rechecked_without_new_evidence():
    contract = modern_contract()
    contract["facts"] = [{**CLUSTERS[0], "fact_id": "fact:previous", "evidence_quotes": [{
        "evidence_key": "fact", "quote": CLUSTERS[0]["claim_text"], "source_url": "https://peps.python.org/pep-0703/"}]}]
    response = coverage_proposal(contract, [{**observation("fact"), "complete": False}], [], [], client=RecheckClient())
    assert response["observations"] == [observation("fact")]
