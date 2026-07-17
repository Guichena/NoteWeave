from __future__ import annotations

from app.branch import plan_branch_recovery
from app.extractor import extract_evidence_cards
from app.llm_client import FakeLlmClient
from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchEvidenceCard,
    ResearchBranchDecision,
    ResearchReadWindow,
    ResearchSearchHit,
    ResearchStateLedger,
)
from app.reporter import build_counterfactual_summary
from app.state import finalize_state_ledger
from app.planner import build_research_plan


def test_counterfactual_branch_should_resolve_only_with_new_independent_evidence() -> None:
    target = ResearchEvidenceCard(
        evidence_id="ev-target",
        window_id="window-1",
        source_id="src-a",
        source_title="Source A",
        claim_text="The mainline is contested.",
        quote_text="The mainline is contested.",
        relation_type="CONFLICTS",
        support_score=0.2,
        conflict_score=0.9,
        entity_id="entity-1",
        column_key="method",
        discovery_round=1,
    )
    result = target.model_copy(
        update={
            "evidence_id": "ev-result",
            "source_id": "src-b",
            "source_title": "Source B",
            "relation_type": "SUPPORTS",
            "support_score": 0.9,
            "conflict_score": 0.0,
            "discovery_round": 2,
            "branch_id": "branch-counterfactual-1",
            "grounding_verified": True,
        }
    )
    prior = ResearchBranchDecision(
        branch_id="branch-counterfactual-1",
        decision="COUNTERFACTUAL_RECHECK",
        branch_reason="CONFLICTING_EVIDENCE",
        verifier_scope="VERIFY",
        target_evidence_ids=["ev-target"],
        branch_status="ACTIVE_BRANCH",
        created_round=1,
    )

    decisions = plan_branch_recovery(
        [_hit()],
        [_window()],
        [target, result],
        LocalVerifierResult(status="WARN"),
        recovery_mode="COUNTERFACTUAL_RECHECK",
        round_no=2,
        prior_decisions=[prior],
    )

    assert decisions[0].decision == "COUNTERFACTUAL_RESOLVED"
    assert decisions[0].result_evidence_ids == ["ev-result"]
    assert decisions[0].resolution == "KEEP_MAINLINE_WITH_AUDIT"


def test_counterfactual_branch_should_not_reuse_preexisting_or_same_source_evidence() -> None:
    target = ResearchEvidenceCard(
        evidence_id="ev-target",
        window_id="window-1",
        source_id="src-a",
        source_title="Source A",
        claim_text="The mainline is contested.",
        quote_text="The mainline is contested.",
        relation_type="CONFLICTS",
        support_score=0.2,
        conflict_score=0.9,
        entity_id="entity-1",
        column_key="method",
        discovery_round=1,
    )
    same_source = target.model_copy(
        update={"evidence_id": "ev-same", "relation_type": "SUPPORTS", "discovery_round": 2}
    )
    prior = ResearchBranchDecision(
        branch_id="branch-counterfactual-1",
        decision="COUNTERFACTUAL_RECHECK",
        branch_reason="CONFLICTING_EVIDENCE",
        verifier_scope="VERIFY",
        target_evidence_ids=["ev-target"],
        branch_status="ACTIVE_BRANCH",
        created_round=1,
    )

    decisions = plan_branch_recovery(
        [_hit()], [_window()], [target, same_source], LocalVerifierResult(status="WARN"),
        recovery_mode="COUNTERFACTUAL_RECHECK", round_no=2, prior_decisions=[prior],
    )

    assert decisions[0].decision == "COUNTERFACTUAL_RECHECK"
    assert decisions[0].branch_status == "ACTIVE_BRANCH"
    assert decisions[0].result_evidence_ids == []


def _hit() -> ResearchSearchHit:
    return ResearchSearchHit(
        hit_id="hit-1",
        source_id="src-1",
        source_title="Source 1",
        query="counterfactual test",
        rank=1,
        snippet="snippet",
        confidence_score=0.9,
        retrieval_reason="test",
    )


def _window() -> ResearchReadWindow:
    return ResearchReadWindow(
        window_id="window-1",
        hit_id="hit-1",
        source_id="src-1",
        source_title="Source 1",
        query="counterfactual test",
        read_focus="conflict",
        window_text="conflicting evidence",
        retention_reason="test",
        token_estimate=10,
    )


def _conflict_card(index: int) -> ResearchEvidenceCard:
    return ResearchEvidenceCard(
        evidence_id=f"ev-conflict-{index}",
        window_id="window-1",
        source_id=f"src-{index}",
        source_title=f"Conflict Source {index}",
        claim_text=f"conflicting claim {index}",
        quote_text=f"quote {index}",
        relation_type="CONFLICTS",
        support_score=0.2,
        conflict_score=0.8,
    )


def test_conflicting_cards_should_create_independent_counterfactual_branch_sessions() -> None:
    decisions = plan_branch_recovery(
        [_hit()],
        [_window()],
        [_conflict_card(1), _conflict_card(2)],
        LocalVerifierResult(status="WARN"),
        branch_budget=2,
    )

    assert [decision.branch_id for decision in decisions] == [
        "branch-counterfactual-1",
        "branch-counterfactual-2",
    ]
    assert [decision.session_id for decision in decisions] == [
        "session-counterfactual-1",
        "session-counterfactual-2",
    ]
    assert all(decision.execution_mode == "SEQUENTIAL_BRANCH" for decision in decisions)
    assert decisions[0].target_evidence_ids == ["ev-conflict-1"]
    assert decisions[1].target_evidence_ids == ["ev-conflict-2"]
    assert decisions[0].sibling_branch_ids == ["branch-counterfactual-2"]
    assert decisions[1].sibling_branch_ids == ["branch-counterfactual-1"]


def test_finalize_ledger_should_persist_counterfactual_branch_sessions() -> None:
    decisions = plan_branch_recovery(
        [_hit()],
        [_window()],
        [_conflict_card(1), _conflict_card(2)],
        LocalVerifierResult(status="WARN"),
        branch_budget=2,
    )

    ledger = finalize_state_ledger(
        ResearchStateLedger(),
        LocalVerifierResult(status="WARN"),
        GlobalVerifierResult(status="WARN", decision="WRITE_WITH_GUARDRAILS", summary="conflict"),
        decisions,
        round_no=1,
    )
    summary = build_counterfactual_summary(
        ledger,
        decisions,
        LocalVerifierResult(status="WARN"),
        GlobalVerifierResult(status="WARN", decision="WRITE_WITH_GUARDRAILS", summary="conflict"),
        recovery_mode="COUNTERFACTUAL_RECHECK",
    )

    assert [session.session_id for session in ledger.branch_sessions] == [
        "session-counterfactual-1",
        "session-counterfactual-2",
    ]
    assert summary["counterfactual_branch_count"] == 2
    assert summary["counterfactual_session_ids"] == [
        "session-counterfactual-1",
        "session-counterfactual-2",
    ]
    assert summary["branches"][0]["execution_mode"] == "SEQUENTIAL_BRANCH"


def test_branch_budget_should_cap_new_counterfactual_sessions() -> None:
    decisions = plan_branch_recovery(
        [_hit()],
        [_window()],
        [_conflict_card(1), _conflict_card(2)],
        LocalVerifierResult(status="WARN"),
        branch_budget=1,
    )

    assert len(decisions) == 1
    assert decisions[0].branch_id == "branch-counterfactual-1"

    exhausted = plan_branch_recovery(
        [_hit()],
        [_window()],
        [_conflict_card(2)],
        LocalVerifierResult(status="WARN"),
        prior_decisions=decisions,
        branch_budget=1,
    )

    assert exhausted[0].decision == "NO_BRANCH"
    assert exhausted[0].branch_reason == "BRANCH_BUDGET_EXHAUSTED"


def test_counterfactual_state_updates_should_remain_target_scoped() -> None:
    from app.models import ResearchStateCell, ResearchStateRow

    ledger = ResearchStateLedger(
        rows=[
            ResearchStateRow(
                row_id="entity-a",
                entity_id="entity-a",
                row_status="CONFLICTED",
                conflicting_evidence_ids=["ev-a"],
            ),
            ResearchStateRow(
                row_id="entity-b",
                entity_id="entity-b",
                row_status="VERIFIED",
            ),
        ],
        cells=[
            ResearchStateCell(
                cell_id="entity-a:method",
                row_id="entity-a",
                entity_id="entity-a",
                column_key="method",
                candidate_value="A",
                evidence_refs=["ev-a"],
            ),
            ResearchStateCell(
                cell_id="entity-b:method",
                row_id="entity-b",
                entity_id="entity-b",
                column_key="method",
                candidate_value="B",
                status="VERIFIED",
                evidence_refs=["ev-b"],
            ),
        ],
    )
    decision = ResearchBranchDecision(
        branch_id="branch-counterfactual-1",
        session_id="session-counterfactual-1",
        decision="COUNTERFACTUAL_RECHECK",
        branch_reason="CONFLICTING_EVIDENCE",
        verifier_scope="VERIFY",
        hypothesis_summary="challenge A only",
        target_evidence_ids=["ev-a"],
        branch_status="ACTIVE_BRANCH",
    )

    finalized = finalize_state_ledger(
        ledger,
        LocalVerifierResult(status="WARN"),
        GlobalVerifierResult(status="WARN", decision="WRITE_WITH_GUARDRAILS", summary="conflict"),
        [decision],
        round_no=1,
    )

    assert finalized.active_branch_ids == ["branch-counterfactual-1"]
    assert finalized.cells[0].branch_id == "branch-counterfactual-1"
    assert finalized.cells[1].branch_id == "branch-main"
    assert finalized.rows[0].branch_id == "branch-counterfactual-1"
    assert finalized.rows[1].branch_id == "branch-main"


def test_counterfactual_extractor_should_preserve_system_owned_target_entity_id() -> None:
    from app.models import ResearchTaskInput

    task_input = ResearchTaskInput.model_validate({
        "task_id": "task-counter-extract",
        "workspace_id": "ws-1",
        "target_id": "run-counter-extract",
        "control_pack": {"pack_type": "research", "target_key": "DEFAULT", "task_neighborhood": "RESEARCH_DEFAULT"},
        "input_payload": {"question": "Compare the target method", "profile_key": "DEFAULT"},
    })
    plan = build_research_plan(task_input)
    target_entity_id = "entity-system-owned"
    target_column = plan.research_schema.columns[0].key
    plan.stop_contract.update({
        "recovery_mode": "COUNTERFACTUAL_RECHECK",
        "recovery_target_entity_ids": [target_entity_id],
        "recovery_target_branch_by_entity": {target_entity_id: "branch-counterfactual-1"},
    })
    window = _window().model_copy(update={
        "source_id": "src-independent",
        "source_title": "Independent Source",
        "window_text": "Independent evidence supports the target method.",
    })
    llm = FakeLlmClient({
        "research.extract": (
            '{"evidence_cards":[{"window_id":"window-1",'
            f'"entity_id":"{target_entity_id}","column_key":"{target_column}",'
            '"claim_text":"Independent evidence supports the target method.",'
            '"quote_text":"Independent evidence supports the target method.",'
            '"relation_type":"SUPPORTS","support_score":0.9,"conflict_score":0.0}]}'
        )
    })

    cards = extract_evidence_cards(task_input, plan, [window], llm)

    assert cards[0].entity_id == target_entity_id
    assert cards[0].branch_id == "branch-counterfactual-1"
    target = ResearchEvidenceCard(
        evidence_id="ev-target-real-path",
        window_id="window-target",
        source_id="src-target",
        source_title="Target Source",
        claim_text="The target method is contested.",
        quote_text="The target method is contested.",
        relation_type="CONFLICTS",
        support_score=0.2,
        conflict_score=0.9,
        entity_id=target_entity_id,
        column_key=target_column,
        discovery_round=1,
        grounding_verified=True,
    )
    prior = ResearchBranchDecision(
        branch_id="branch-counterfactual-1",
        session_id="session-counterfactual-1",
        decision="COUNTERFACTUAL_RECHECK",
        branch_reason="CONFLICTING_EVIDENCE",
        verifier_scope="VERIFY",
        target_evidence_ids=[target.evidence_id],
        branch_status="ACTIVE_BRANCH",
        created_round=1,
    )
    decisions = plan_branch_recovery(
        [_hit()],
        [window],
        [target, cards[0].model_copy(update={"discovery_round": 2})],
        LocalVerifierResult(status="WARN"),
        recovery_mode="COUNTERFACTUAL_RECHECK",
        round_no=2,
        prior_decisions=[prior],
    )

    assert decisions[0].decision == "COUNTERFACTUAL_RESOLVED"
    assert decisions[0].result_evidence_ids == [cards[0].evidence_id]


def test_new_counterfactual_branch_should_allocate_matching_unique_session_id() -> None:
    first = plan_branch_recovery(
        [_hit()], [_window()], [_conflict_card(1)], LocalVerifierResult(status="WARN"), branch_budget=2
    )

    second = plan_branch_recovery(
        [_hit()],
        [_window()],
        [_conflict_card(2)],
        LocalVerifierResult(status="WARN"),
        prior_decisions=first,
        branch_budget=2,
    )

    assert second[0].branch_id == "branch-counterfactual-2"
    assert second[0].session_id == "session-counterfactual-2"
