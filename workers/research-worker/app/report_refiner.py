from __future__ import annotations

from copy import deepcopy


def refine_report_structure(
    report_structure: dict[str, object],
    citation_verification: dict[str, object],
    *,
    max_revisions: int = 2,
) -> tuple[dict[str, object], dict[str, object]]:
    structure = deepcopy(report_structure)
    failed_rows = {
        str(item.get("row_id") or "")
        for item in citation_verification.get("finding_checks", [])
        if isinstance(item, dict) and item.get("status") != "PASS"
    }
    attempts: list[dict[str, object]] = []
    for revision_no in range(1, max(0, max_revisions) + 1):
        if not failed_rows:
            break
        verified = [item for item in structure.get("verified_findings", []) if isinstance(item, dict)]
        demoted = [item for item in verified if str(item.get("row_id") or "") in failed_rows]
        if not demoted:
            break
        structure["verified_findings"] = [item for item in verified if item not in demoted]
        remaining_verified = list(structure["verified_findings"])
        demoted_claims = {
            str(item.get("claim_text") or "").strip()
            for item in demoted
            if str(item.get("claim_text") or "").strip()
        }
        guarded = [item for item in structure.get("guarded_rows", []) if isinstance(item, dict)]
        for item in demoted:
            revised = dict(item)
            revised["row_status"] = "CITATION_GUARDED"
            revised["repair_hint"] = "A snapshot-grounded supporting citation is required before promotion."
            guarded.append(revised)
        structure["guarded_rows"] = guarded
        recovery_status = dict(structure.get("recovery_status", {}))
        recovery_status["guardrailed_rows"] = guarded
        structure["recovery_status"] = recovery_status
        final_answer = dict(structure.get("final_answer", {}))
        final_answer.update(
            answer_status="GUARDED",
            answer_text="No finding is promoted as verified until citation association and support checks pass.",
            source_basis="CITATION_GUARDED",
            confidence_label="LOW",
        )
        structure["final_answer"] = final_answer
        structure["key_findings"] = [
            item
            for item in structure.get("key_findings", [])
            if str(item).strip() not in demoted_claims
        ]
        structure["key_takeaways"] = (
            [
                str(item.get("claim_text") or "").strip()
                for item in remaining_verified[:4]
                if str(item.get("claim_text") or "").strip()
            ]
            or ["No citation-verified takeaway is available; review the guarded findings."]
        )
        structure["evidence_highlights"] = [
            item
            for item in structure.get("evidence_highlights", [])
            if not isinstance(item, dict) or str(item.get("row_id") or "") not in failed_rows
        ]
        structure["executive_summary"] = [
            f"Citation audit demoted {len(demoted)} unsupported finding(s) from verified status.",
            f"{len(remaining_verified)} citation-verified finding(s) remain.",
            "Guarded findings are retained for repair and must not be treated as verified conclusions.",
        ]
        attempts.append(
            {
                "revision_no": revision_no,
                "section_key": "verified_findings",
                "action": "DEMOTE_UNSUPPORTED_FINDINGS",
                "affected_row_ids": sorted(failed_rows),
                "status": "APPLIED",
            }
        )
        failed_rows.clear()

    structure["citation_verification"] = citation_verification
    structure["report_ast"] = _build_report_ast(structure)
    refinement = {
        "status": "REVISED" if attempts else "PASS",
        "revision_count": len(attempts),
        "max_revisions": max_revisions,
        "attempts": attempts,
        "termination_reason": "CITATION_GUARD_SATISFIED" if not failed_rows else "REVISION_BUDGET_EXHAUSTED",
    }
    structure["report_revision_loop"] = refinement
    return structure, refinement


def _build_report_ast(structure: dict[str, object]) -> dict[str, object]:
    return {
        "ast_version": "v1",
        "sections": [
            {
                "section_key": "verified_findings",
                "node_type": "FINDING_LIST",
                "item_count": len(structure.get("verified_findings", [])),
                "evidence_required": True,
            },
            {
                "section_key": "conflicted_findings",
                "node_type": "CONFLICT_LIST",
                "item_count": len(structure.get("conflicted_rows", [])),
                "evidence_required": True,
            },
            {
                "section_key": "guarded_findings",
                "node_type": "GUARDED_LIST",
                "item_count": len(structure.get("guarded_rows", [])),
                "evidence_required": False,
            },
        ],
    }
