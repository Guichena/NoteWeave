"""Deterministic, identity-blind quorum verification for high-risk cells."""

from __future__ import annotations

import hashlib
import json
import unicodedata
from dataclasses import dataclass

from app.agent_contracts import CellCandidate, CellTaskBundle


@dataclass(frozen=True)
class BlindVerificationDecision:
    status: str
    reason_code: str
    canonical_value: str | None
    repair_required: bool
    anonymous_candidate_digests: tuple[str, ...]


class BlindCandidateVerifier:
    """Verify candidate quorum without using worker identity or generation order."""

    def verify(
        self,
        task: CellTaskBundle,
        candidates: list[CellCandidate],
        evidence_domains: dict[str, str],
    ) -> BlindVerificationDecision:
        if len(task.target_cells) != 1:
            return self._repair("BLIND_VERIFIER_REQUIRES_SINGLE_CELL_TASK", ())
        target = task.target_cells[0]
        eligible = [
            item for item in candidates
            if item.task_id == task.task_id
            and item.cell_id == target.cell_id
            and item.base_cell_version == target.expected_version
            and item.lease_epoch == task.lease_epoch
            and item.fencing_token == task.fencing_token
        ]
        anonymous = tuple(sorted(self._anonymous_digest(item, evidence_domains) for item in eligible))
        if len(eligible) < task.candidate_quorum:
            return self._repair("CANDIDATE_QUORUM_NOT_MET", anonymous)

        selected = self._stable_candidates(eligible)[:task.candidate_quorum]
        if len({item.execution_id for item in selected}) != len(selected):
            return self._repair("INDEPENDENT_EXECUTION_QUORUM_NOT_MET", anonymous)
        if len({item.candidate_slot for item in selected}) != len(selected):
            return self._repair("INDEPENDENT_SLOT_QUORUM_NOT_MET", anonymous)

        domain_sets: list[set[str]] = []
        for item in selected:
            domains = {
                self._normalize_domain(evidence_domains.get(evidence_id, ""))
                for evidence_id in item.evidence_ids
            }
            domains.discard("")
            if not domains:
                return self._repair("EVIDENCE_DOMAIN_MISSING", anonymous)
            domain_sets.append(domains)
        if task.candidate_quorum > 1 and any(
            left & right
            for index, left in enumerate(domain_sets)
            for right in domain_sets[index + 1:]
        ):
            return self._repair("INDEPENDENT_SOURCE_QUORUM_NOT_MET", anonymous)

        normalized_values = {self._normalize_value(item.value) for item in selected}
        if len(normalized_values) != 1:
            return self._repair("CANDIDATE_VALUE_CONFLICT", anonymous)
        canonical_value = min(item.value.strip() for item in selected)
        return BlindVerificationDecision(
            status="ACCEPTED",
            reason_code="BLIND_QUORUM_VERIFIED",
            canonical_value=canonical_value,
            repair_required=False,
            anonymous_candidate_digests=anonymous,
        )

    @staticmethod
    def _repair(reason: str, anonymous: tuple[str, ...]) -> BlindVerificationDecision:
        return BlindVerificationDecision("REPAIR_REQUIRED", reason, None, True, anonymous)

    def _stable_candidates(self, candidates: list[CellCandidate]) -> list[CellCandidate]:
        return sorted(candidates, key=lambda item: (
            self._normalize_value(item.value),
            tuple(sorted(item.evidence_ids)),
            item.candidate_slot,
            item.candidate_id,
        ))

    def _anonymous_digest(self, candidate: CellCandidate, evidence_domains: dict[str, str]) -> str:
        payload = {
            "value": self._normalize_value(candidate.value),
            "evidence": sorted(candidate.evidence_ids),
            "domains": sorted({
                self._normalize_domain(evidence_domains.get(item, ""))
                for item in candidate.evidence_ids
                if self._normalize_domain(evidence_domains.get(item, ""))
            }),
        }
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    @staticmethod
    def _normalize_value(value: str) -> str:
        return " ".join(unicodedata.normalize("NFKC", value).casefold().split())

    @staticmethod
    def _normalize_domain(value: str) -> str:
        return value.strip().lower().rstrip(".")
