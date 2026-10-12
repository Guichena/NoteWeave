import json
from dataclasses import replace
from pathlib import Path

import pytest

from app.claim_clustering import build_claim_clusters, equivalent, normalize_claim, validate_clusters
from app.evidence_audit_executor import EvidenceAuditExecutor
from app.source_independence import independent_confidence, registered_domain, simhash
from app.task_snapshot_contract import snapshot_digest
from test_controlled_role_executors import _claim, _command


class SequenceLlm:
    def __init__(self, *results):
        self.results = iter(results)
        self.calls = []

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, payload))
        result = next(self.results)
        if isinstance(result, Exception):
            raise result
        return json.dumps(result)

    def usage_summary(self):
        return {"provider_call_count": len(self.calls)}


def evidence(key, claim, domain, quote, score=800000, cell="cell-a", lineage=""):
    return {"evidence_key": key, "cell_key": cell, "claim_text": claim,
            "source_domain": domain, "quote_text": quote, "support_score_ppm": score,
            "lineage_digest": lineage}


@pytest.mark.parametrize("left,right", [("Size １ GB", "Size 1000 MB"),
                                           ("Size 1 GiB", "Size 1024 MiB"),
                                           ("Latency 50 ms", "Latency 0.05 s"),
                                           ("容量１GB", "容量1000MB")])
def test_normalizes_units_and_width_without_model(left, right):
    assert normalize_claim(left) == normalize_claim(right)
    assert equivalent(left, right, None) is True


@pytest.mark.parametrize("left,right", [("Size 1 GB", "Size 1 GiB"),
                                           ("Limit 10 requests", "Limit 20 requests"),
                                           ("Supports export", "Does not support export")])
def test_quantities_and_polarity_never_merge_by_similarity(left, right):
    assert equivalent(left, right, None) is False


def test_equivalence_retries_invalid_boolean_and_preserves_validation_error():
    llm = SequenceLlm({"equivalent": "true"}, {"equivalent": True})
    assert equivalent("Supports CSV export", "Supports file export", llm) is True
    assert len(llm.calls) == 2
    assert llm.calls[1][1]["validation_errors"]
    assert {p for p, _ in llm.calls} == {"research.claims.equivalent"}


def test_unavailable_equivalence_preserves_separate_original_clusters():
    rows = [evidence("a", "Supports CSV export", "alpha.org", "CSV export is available"),
            evidence("b", "Supports file export", "beta.net", "Files can be exported")]
    llm = SequenceLlm(RuntimeError("服务不可用"), RuntimeError("服务不可用"))
    clusters = build_claim_clusters(rows, llm)
    assert len(clusters) == 2
    assert {c["claim_text"] for c in clusters} == {r["claim_text"] for r in rows}
    validate_clusters(clusters)


def test_complete_link_prevents_transitive_equivalence(monkeypatch):
    import app.claim_clustering as module
    monkeypatch.setattr(module, "equivalent", lambda a, b, llm: {a, b} != {"A", "C"})
    clusters = build_claim_clusters([evidence(k, k, k + ".org", k, 800000-i)
                                     for i, k in enumerate(["A", "B", "C"])])
    assert [c["evidence_keys"] for c in clusters] == [["A", "B"], ["C"]]


def test_psl_private_domains_and_noisy_or():
    assert registered_domain("docs.example.co.nz") == "example.co.nz"
    assert registered_domain("a.github.io") != registered_domain("b.github.io")
    rows = [evidence("a", "X", "a.github.io", "Export data through CSV format", 800000),
            evidence("b", "X", "b.github.io", "Download complete records in spreadsheet files", 700000)]
    assert independent_confidence(rows) == (2, 940000)
    rows[1]["support_score_ppm"] = 900000
    assert independent_confidence(rows) == (2, 950000)


def test_domain_lineage_and_quote_copy_merge_transitively():
    rows = [evidence("a", "X", "docs.example.co.nz", "first original passage", 800000),
            evidence("b", "X", "www.example.co.nz", "copied exact passage", 700000),
            evidence("c", "X", "independent.org", "copied exact passage", 600000)]
    assert independent_confidence(rows) == (1, 800000)
    rows[2]["quote_text"] = "different original passage"
    rows[1]["lineage_digest"] = rows[2]["lineage_digest"] = "same-file"
    assert independent_confidence(rows) == (1, 800000)


def test_simhash_merges_reordered_republication_with_cross_language_golden():
    quote = "alpha beta gamma delta epsilon"
    assert simhash(quote) == simhash("epsilon delta gamma beta alpha")
    assert format(simhash(quote), "016x") == "eedf74ad6fe9f881"
    assert format(simhash("Straße 中文 ＡＢＣ"), "016x") == "367bbfbf81574d22"
    rows = [evidence("a", "X", "alpha.org", quote),
            evidence("b", "X", "beta.net", "epsilon delta gamma beta alpha")]
    assert independent_confidence(rows) == (1, 800000)


def test_cluster_score_aggregates_only_independent_sources_and_keeps_raw_claims():
    rows = [evidence("a", "Size 1 GB", "alpha.org", "Capacity equals one billion bytes", 800000),
            evidence("b", "Size 1000 MB", "beta.net", "Maximum storage is a thousand megabytes", 700000)]
    cluster, = build_claim_clusters(rows)
    assert cluster["confidence_ppm"] == 940000
    assert cluster["claim_text"] == "Size 1 GB"
    assert [c["claim_text"] for c in cluster["equivalent_claims"]] == [r["claim_text"] for r in rows]
    cluster["claim_text"] = "invented representation"
    with pytest.raises(ValueError):
        validate_clusters([cluster])


def audit_claim():
    claim = _claim("EVIDENCE_AUDIT", {"audit_input": {
        "schema_version": "research-evidence-audit-input.v2", "input_digest": "sha256:audit",
        "cells": [{"cell_key": "cell-a", "cell_version": 3, "semantic_revision": 1,
                   "column_description": "容量", "evidence": [{"evidence_key": "a",
                     "final_status": "QUALIFIED", "source_domain": "alpha.org", "lineage_digest": "lineage-a",
                     "typed_status": "PASS"}], "clusters": [
                     {"cluster_key": "cluster:a", "claim_text": "Size 1 GB", "evidence_keys": ["a"]},
                     {"cluster_key": "cluster:b", "claim_text": "Size 2 GB", "evidence_keys": ["b"]}]}]}})
    payload = json.loads(claim.task_snapshot_json)
    payload.pop("snapshot_digest")
    payload["budget"] = {"llm_calls": 4}
    digest = snapshot_digest(payload)
    return replace(claim, budget_json='{"llm_calls":4}', task_snapshot_json=json.dumps(
        {**payload, "snapshot_digest": digest}), snapshot_digest=digest)


@pytest.mark.parametrize("verdict,status", [("CONTRADICTORY", "REPAIR_REQUIRED"),
                                               ("NOT_CONTRADICTORY", "PASS")])
def test_audit_checks_all_pairs_with_independent_purpose_and_budget(verdict, status):
    llm = SequenceLlm({"equivalent": False}, {"verdict": verdict})
    result = EvidenceAuditExecutor("worker-a", llm_client=llm)(_command(), audit_claim())
    assert result.role_result["status"] == status
    assert result.role_result["semantic_checks"][0]["verdict"] == verdict
    assert result.budget_usage.llm_calls == 2
    assert [p for p, _ in llm.calls] == ["research.claims.equivalent", "research.audit.contradiction"]


def test_audit_unavailable_is_not_run_and_blocks_composition():
    result = EvidenceAuditExecutor("worker-a", enable_llm=False)(_command(), audit_claim())
    assert result.role_result["status"] == "BLOCKED"
    assert result.role_result["semantic_checks"][0]["verdict"] == "NOT_RUN"
    assert result.role_result["targets"][0]["reason_codes"] == ["SEMANTIC_CHECK_NOT_RUN"]
    assert result.budget_usage.llm_calls == 0


def test_inconsistent_equivalent_and_contradictory_verdict_blocks_composition():
    llm = SequenceLlm({"equivalent": True}, {"verdict": "CONTRADICTORY"})
    result = EvidenceAuditExecutor("worker-a", llm_client=llm)(_command(), audit_claim())
    assert result.role_result["status"] == "BLOCKED"
    assert result.role_result["semantic_checks"][0]["verdict"] == "NOT_RUN"


def test_shared_equivalent_completion_vector_keeps_cross_language_digest_and_order():
    from app.research_agent_completion_contract import build_completion_envelope
    path = Path(__file__).resolve().parents[3] / "backend/src/test/resources/research/semantic-completion-v2.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    envelope = build_completion_envelope(payload)
    payload["candidates"][0]["equivalent_claims"].reverse()
    reversed_envelope = build_completion_envelope(payload)
    assert envelope.envelope_digest == reversed_envelope.envelope_digest
    assert envelope.envelope_digest == payload["envelope_digest"]
    payload.pop("envelope_digest")
    payload["candidates"][0]["candidate_value"] = "生成的另一种容量结论"
    with pytest.raises(ValueError):
        build_completion_envelope(payload)
