"""Recompute every MA4G child content digest from real MySQL row payloads."""

from __future__ import annotations

import base64
import hmac
import json
import os
import re
import unicodedata
from collections import Counter

from app.research_agent_completion_contract import domain_separated_json_digest


DOMAINS = {
    "evidence": "research-agent-source-evidence.v1",
    "candidate": "research-agent-candidate.v1",
    "merge": "research-agent-cell-merge.v1",
    "cell_evidence": "research-agent-cell-evidence.v1",
}
FIELDS = {
    "evidence": {
        "id", "research_run_id", "agent_completion_id", "evidence_key", "window_id",
        "source_id", "source_title", "source_url", "provider", "adapter", "search_query",
        "read_focus", "quote_text", "claim_text", "relation_type", "support_score",
        "conflict_score", "support_score_ppm", "conflict_score_ppm", "snapshot_status",
        "snapshot_key",
    },
    "candidate": {
        "id", "research_run_id", "agent_completion_id", "research_agent_execution_id",
        "task_id", "execution_id", "idempotency_key", "cell_key", "base_cell_version",
        "plan_revision", "entity_set_version", "lease_epoch", "fencing_token",
        "candidate_value", "evidence_ids", "confidence_score", "confidence_score_ppm",
    },
    "merge": {
        "id", "research_run_id", "agent_completion_id", "candidate_id", "merge_key",
        "cell_key", "expected_cell_version", "result_cell_version", "verdict", "decision",
        "reason_code", "accepted_evidence_ids",
    },
    "cell_evidence": {
        "id", "research_run_id", "agent_completion_id", "candidate_id", "merge_id",
        "merge_key", "research_cell_id", "source_evidence_id", "evidence_key",
    },
}
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


def reject_duplicate_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        normalized = unicodedata.normalize("NFC", key)
        if normalized in result:
            raise ValueError("duplicate row payload key after NFC normalization")
        result[normalized] = value
    return result


def require_ppm(payload: dict[str, object], *keys: str) -> None:
    for key in keys:
        value = payload.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > 1_000_000:
            raise RuntimeError(f"row digest payload has invalid {key}")


def main() -> None:
    path = os.environ.get("MA4G_ROW_DIGEST_EXPORT", "/control/row-digests.tsv")
    expected_each = int(os.environ.get("MA4G_EXPECTED_CHILD_ROWS", "0"))
    if expected_each < 1:
        raise RuntimeError("MA4G_EXPECTED_CHILD_ROWS must be positive")
    counts: Counter[str] = Counter()
    identities: set[tuple[str, str]] = set()
    ppm_bound = 0
    with open(path, "rt", encoding="utf-8", newline="") as handle:
        for raw_line in handle:
            fields = raw_line.rstrip("\n").split("\t")
            if len(fields) != 4:
                raise RuntimeError("row digest export has an invalid field count")
            row_type, row_id, stored_digest, payload_b64 = fields
            if row_type not in DOMAINS or not DIGEST.fullmatch(stored_digest):
                raise RuntimeError("row digest export has an invalid type or digest")
            if (row_type, row_id) in identities:
                raise RuntimeError("row digest export contains a duplicate identity")
            identities.add((row_type, row_id))
            payload_bytes = base64.b64decode(payload_b64, validate=True)
            payload = json.loads(
                payload_bytes.decode("utf-8", errors="strict"),
                object_pairs_hook=reject_duplicate_pairs,
            )
            if not isinstance(payload, dict) or set(payload) != FIELDS[row_type] or payload.get("id") != row_id:
                raise RuntimeError(f"{row_type} row digest payload has missing or unknown fields")
            if row_type == "evidence":
                require_ppm(payload, "support_score_ppm", "conflict_score_ppm")
                ppm_bound += 1
            elif row_type == "candidate":
                require_ppm(payload, "confidence_score_ppm")
                ppm_bound += 1
            expected = domain_separated_json_digest(DOMAINS[row_type], payload)
            if not hmac.compare_digest(stored_digest, expected):
                raise RuntimeError(f"{row_type} {row_id} content digest mismatch")
            counts[row_type] += 1

    expected_counts = {row_type: expected_each for row_type in DOMAINS}
    if dict(counts) != expected_counts:
        raise RuntimeError(f"row digest counts mismatch: expected={expected_counts} actual={dict(counts)}")
    if ppm_bound != expected_each * 2:
        raise RuntimeError("ppm-bearing row digest coverage is incomplete")
    print(
        "MA4G_PERSISTED_ROW_DIGESTS_VERIFIED "
        f"each={expected_each} total={sum(counts.values())} ppm_bound={ppm_bound}",
        flush=True,
    )


if __name__ == "__main__":
    main()

