"""P0 新行为测试，覆盖诚实的无 LLM extraction 与结构化状态契约。

覆盖 P0-1 (row=entity + ResearchSchema), P0-4 (诚实 RULE-mode),
P0-5 (CellVerifier 4-way verdict), P0-6 (max_loop_rounds + cell retry)。
"""

from __future__ import annotations

import json

import pytest

from app.cell_verifier import CellVerifier, CellSupportStatus
from app.extractor import extract_evidence_cards
from app.llm_client import FakeLlmClient, LlmClient
from app.models import (
    CellVerdict,
    ResearchEntityRow,
    ResearchPlan,
    ResearchRequiredFindingProgress,
    ResearchSchema,
    ResearchColumn,
    ResearchStateCell,
    ResearchStateLedger,
    ResearchStateRow,
    ResearchTaskInput,
)
from app.planner import build_research_plan
from app.state import build_state_ledger
from app.loop_runtime import _freeze_overspent_cells
from app.research_tools import (
    _freeze_entity_candidate_set,
    _merge_ledger_history,
    _recompute_requirement_progress_after_cell_verification,
    _seed_cell_history,
)
from app.verifier import run_local_verifier
from app.verifier import run_global_verifier


# ---------------------------------------------------------------------------
# P0-1: ResearchSchema + row=entity
# ---------------------------------------------------------------------------


def test_planner_should_emit_research_schema_with_paper_survey_columns_for_paper_questions() -> None:
    """research_type 自动识别 PAPER_SURVEY,schema.columns 至少有 5 个核心列。"""
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-schema-paper",
            "workspace_id": "ws-1",
            "target_id": "run-schema-paper",
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "Survey on arxiv papers for table-as-search architectures",
                "profile_key": "DEEP",
                "research_intent": {"depth": "DEEP"},
            },
        }
    )
    plan = build_research_plan(task_input)
    assert plan.research_type == "PAPER_SURVEY"
    assert plan.target_entity_type == "academic paper"
    assert isinstance(plan.research_schema, ResearchSchema)
    column_keys = [c.key for c in plan.research_schema.columns]
    assert "paper_title" in column_keys
    assert "problem" in column_keys
    assert "method" in column_keys
    # P0-1: state_columns 现在由 schema 派生,不再包含 11 个旧元数据
    assert "support_level" not in plan.state_columns
    assert "claim_text" not in plan.state_columns


def test_planner_should_emit_product_comparison_columns_for_product_questions() -> None:
    """product 类问题自动选择 PRODUCT_COMPARISON schema。"""
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-schema-product",
            "workspace_id": "ws-1",
            "target_id": "run-schema-product",
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "Compare notebooklm vs perplexity vs genspark",
                "profile_key": "STANDARD",
                "research_intent": {"depth": "STANDARD"},
            },
        }
    )
    plan = build_research_plan(task_input)
    assert plan.research_type == "PRODUCT_COMPARISON"
    column_keys = [c.key for c in plan.research_schema.columns]
    assert "product" in column_keys
    assert "positioning" in column_keys
    assert "core_feature" in column_keys


def test_state_ledger_should_build_rows_for_each_unique_entity() -> None:
    """P0-1: row 现在是 entity,每个不同 entity_id 一行。"""
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-state-1",
            "workspace_id": "ws-1",
            "target_id": "run-state-1",
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "Compare notebooklm vs perplexity",
                "profile_key": "STANDARD",
                "research_intent": {"depth": "STANDARD"},
            },
        }
    )
    plan = build_research_plan(task_input)
    search_hits = [
        # 实际类型由 conftest 注入的 FakeLlmClient 决定
    ]
    from app.search_adapters import build_search_query_plan

    # 直接构造两个不同 entity 的 search_hits
    from app.models import ResearchSearchHit

    hits = [
        ResearchSearchHit(
            hit_id="h-1", source_id="src-a", source_title="NotebookLM",
            query="notebooklm", rank=1, snippet="notebooklm snippet",
            confidence_score=0.9, retrieval_reason="test",
            search_angle="direct", url="https://notebooklm.google.com",
        ),
        ResearchSearchHit(
            hit_id="h-2", source_id="src-b", source_title="Perplexity",
            query="perplexity", rank=2, snippet="perplexity snippet",
            confidence_score=0.85, retrieval_reason="test",
            search_angle="direct", url="https://perplexity.ai",
        ),
    ]
    ledger = build_state_ledger(task_input, plan, hits, [], [])

    assert len(ledger.entities) == 2
    assert len(ledger.rows) == 2
    entity_ids = {row.entity_id for row in ledger.rows}
    assert entity_ids == {entity.entity_id for entity in ledger.entities}
    # 每个 entity 在 schema.columns 上都有自己的 cells
    expected_cells_per_entity = len(plan.research_schema.columns)
    for row in ledger.rows:
        row_cells = [c for c in ledger.cells if c.entity_id == row.entity_id]
        assert len(row_cells) == expected_cells_per_entity


# ---------------------------------------------------------------------------
# P0-4: 诚实 RULE-mode (no LLM)
# ---------------------------------------------------------------------------


def test_extractor_should_return_empty_list_when_llm_client_is_none() -> None:
    """P0-4: 无 LLM 时,extractor 直接返回空列表(不伪造证据分)。"""
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-rule-empty",
            "workspace_id": "ws-1",
            "target_id": "run-rule-empty",
            "source_scope": [
                {
                    "source_id": "src-rule",
                    "title": "Rule Source",
                    "summary": "Alpha source suggests verified evidence.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "What should rule mode do without LLM?",
                "profile_key": "STANDARD",
                "research_intent": {"depth": "STANDARD"},
            },
        }
    )
    plan = build_research_plan(task_input)
    from app.search import run_workspace_search
    from app.reader import open_read_windows

    search_hits = run_workspace_search(task_input, plan)
    read_windows = open_read_windows(task_input, plan, search_hits)
    # 不传 llm_client
    cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=None)
    assert cards == []
    # Local Verifier 应当记录 RULE_MODE_NO_EXTRACTION warning


# ---------------------------------------------------------------------------
# P0-5: CellVerifier 4-way verdict
# ---------------------------------------------------------------------------


def test_cell_verifier_should_return_not_enough_info_when_no_evidence() -> None:
    """无证据卡时,CellVerifier 返回 NOT_ENOUGH_INFO,不应该猜结论。"""
    from app.models import ResearchStateCell

    cell = ResearchStateCell(
        cell_id="entity-1:method",
        row_id="entity-1",
        entity_id="entity-1",
        column_key="method",
        candidate_value="transformer based",
        status="CANDIDATE_READY",
    )
    verifier = CellVerifier(llm_client=None)
    updated_cells, verdicts = verifier.verify_cells([cell], {"entity-1:method": []})
    assert len(updated_cells) == 1
    assert len(verdicts) == 1
    assert verdicts[0].status == CellSupportStatus.NOT_ENOUGH_INFO
    assert updated_cells[0].verdict == CellSupportStatus.NOT_ENOUGH_INFO
    assert verdicts[0].used_llm is False


def test_cell_verifier_should_emit_contradicts_when_supports_and_conflicts_both_present() -> None:
    """同时存在 SUPPORTS 与 CONFLICTS 卡片时,CellVerifier 应能区分两种 verdict。"""
    from app.models import ResearchEvidenceCard, ResearchStateCell

    cell = ResearchStateCell(
        cell_id="entity-1:method",
        row_id="entity-1",
        entity_id="entity-1",
        column_key="method",
        candidate_value="transformer based",
        status="CANDIDATE_READY",
    )
    cards = [
        ResearchEvidenceCard(
            evidence_id="ev-window-1-1", window_id="window-1",
            source_id="src-a", source_title="Source A",
            claim_text="transformer based", quote_text="q1",
            relation_type="SUPPORTS", support_score=0.85, conflict_score=0.02,
        ),
        ResearchEvidenceCard(
            evidence_id="ev-window-1-2", window_id="window-1",
            source_id="src-b", source_title="Source B",
            claim_text="not transformer", quote_text="q2",
            relation_type="CONFLICTS", support_score=0.3, conflict_score=0.7,
        ),
    ]
    verifier = CellVerifier(llm_client=None)
    updated_cells, verdicts = verifier.verify_cells(
        [cell], {"entity-1:method": cards}
    )
    # P0-5 修复: SUPPORTS + CONFLICTS 同时存在时,旧代码会把两张卡片合并丢一张
    # 现在两张卡片都保留,verdict 区分对待
    assert verdicts[0].status == CellSupportStatus.CONTRADICTS
    assert updated_cells[0].verdict == CellSupportStatus.CONTRADICTS
    assert len(verdicts[0].evidence_ids) == 2  # 两张证据都被引用


def test_cell_verifier_should_not_claim_supports_without_llm_judge() -> None:
    """仅多张高 confidence SUPPORTS 卡片时,verdict 为 SUPPORTS。"""
    from app.models import ResearchEvidenceCard, ResearchStateCell

    cell = ResearchStateCell(
        cell_id="entity-1:method", row_id="entity-1", entity_id="entity-1",
        column_key="method", candidate_value="transformer based",
        status="CANDIDATE_READY",
    )
    cards = [
        ResearchEvidenceCard(
            evidence_id="ev-window-1-1", window_id="window-1",
            source_id="src-a", source_title="Source A",
            claim_text="transformer based", quote_text="q1",
            relation_type="SUPPORTS", support_score=0.92, conflict_score=0.01,
        ),
        ResearchEvidenceCard(
            evidence_id="ev-window-1-2", window_id="window-2",
            source_id="src-b", source_title="Source B",
            claim_text="transformer based", quote_text="q2",
            relation_type="SUPPORTS", support_score=0.88, conflict_score=0.02,
        ),
    ]
    verifier = CellVerifier(llm_client=None)
    updated_cells, verdicts = verifier.verify_cells(
        [cell], {"entity-1:method": cards}
    )
    assert verdicts[0].status == CellSupportStatus.NOT_ENOUGH_INFO
    assert verdicts[0].used_llm is False
    assert verdicts[0].confidence <= 0.3


def test_cell_verifier_should_use_llm_when_available() -> None:
    """有 LLM 时,CellVerifier 调用 LLM JSON schema 4-way 判定。"""
    from app.models import ResearchEvidenceCard, ResearchStateCell

    cell = ResearchStateCell(
        cell_id="entity-1:method", row_id="entity-1", entity_id="entity-1",
        column_key="method", candidate_value="transformer based",
        status="CANDIDATE_READY",
    )
    cards = [
        ResearchEvidenceCard(
            evidence_id="ev-window-1-1", window_id="window-1",
            source_id="src-a", source_title="Source A",
            claim_text="transformer based", quote_text="q1",
            relation_type="SUPPORTS", support_score=0.9, conflict_score=0.02,
        ),
    ]
    llm = FakeLlmClient(
        {
            "research.verify.cell": (
                '{"status":"PARTIALLY_SUPPORTS","confidence":0.65,'
                '"reason":"evidence hedges on a sub-aspect","suggested_revision":""}'
            )
        }
    )
    verifier = CellVerifier(llm_client=llm)
    updated_cells, verdicts = verifier.verify_cells(
        [cell], {"entity-1:method": cards}
    )
    assert verdicts[0].status == CellSupportStatus.PARTIALLY_SUPPORTS
    assert verdicts[0].used_llm is True
    assert verdicts[0].confidence == 0.65


# ---------------------------------------------------------------------------
# P0-6: max_loop_rounds 与 cell retry 冻结
# ---------------------------------------------------------------------------


def test_planner_should_set_max_retry_per_cell_by_depth() -> None:
    """QUICK/STANDARD/DEEP 应分别设置 max_retry_per_cell 为 1/3/5。"""
    for depth, expected_retry in [
        ("QUICK", 1),
        ("STANDARD", 3),
        ("DEEP", 5),
    ]:
        task_input = ResearchTaskInput.model_validate(
            {
                "task_id": f"task-retry-{depth}",
                "workspace_id": "ws-1",
                "target_id": f"run-retry-{depth}",
                "control_pack": {
                    "pack_type": "research",
                    "target_key": "DEFAULT",
                    "task_neighborhood": "RESEARCH_DEFAULT",
                },
                "input_payload": {
                    "question": "what",
                    "profile_key": "DEFAULT",
                    "research_intent": {"depth": depth},
                },
            }
        )
        plan = build_research_plan(task_input)
        assert plan.stop_contract["max_retry_per_cell"] == expected_retry, depth
        assert plan.stop_contract["max_loop_rounds"] >= 1


def test_planner_should_accept_design_profile_aliases() -> None:
    for depth, expected_retry, expected_depth in [
        ("FAST", 1, "QUICK"),
        ("HIGH_CONFIDENCE", 5, "DEEP"),
    ]:
        task_input = ResearchTaskInput.model_validate(
            {
                "task_id": f"task-depth-alias-{depth}",
                "workspace_id": "ws-1",
                "target_id": f"run-depth-alias-{depth}",
                "control_pack": {
                    "pack_type": "research",
                    "target_key": "DEFAULT",
                    "task_neighborhood": "RESEARCH_DEFAULT",
                },
                "input_payload": {
                    "question": "what",
                    "profile_key": "DEFAULT",
                    "research_intent": {"depth": depth},
                },
            }
        )

        plan = build_research_plan(task_input)

        assert plan.stop_contract["depth"] == expected_depth
        assert plan.stop_contract["max_retry_per_cell"] == expected_retry


def test_freeze_overspent_cells_should_mark_cells_above_retry_budget_as_frozen() -> None:
    """P0-6: 超过 max_retry_per_cell 的 cell 应被冻结,不再消耗后续循环 token。"""
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-freeze",
            "workspace_id": "ws-1",
            "target_id": "run-freeze",
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {"question": "what", "profile_key": "DEFAULT"},
        }
    )
    plan = build_research_plan(task_input)
    search_hits = []
    read_windows = []
    evidence_cards = []
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, evidence_cards)
    # 手工构造一个 cell 让它超过 retry 预算
    if ledger.cells:
        target = ledger.cells[0].model_copy(update={"repair_count": 99})
        new_cells = [target] + [c for c in ledger.cells if c.cell_id != target.cell_id]
        ledger = ledger.model_copy(update={"cells": new_cells})
    else:
        # 空 ledger: 注入一个测试 cell
        from app.models import ResearchStateCell

        target = ResearchStateCell(
            cell_id="entity-test:col", row_id="entity-test",
            entity_id="entity-test", column_key="col", candidate_value="v",
            status="CANDIDATE_READY", repair_count=99,
        )
        ledger = ledger.model_copy(update={"cells": [target]})

    frozen = _freeze_overspent_cells(ledger, max_retry_per_cell=3)
    # 找到原 target cell
    target_after = next(c for c in frozen.cells if c.cell_id == "entity-test:col")
    assert target_after.status == "FROZEN"
    assert "FROZEN" in target_after.verdict_reason


def test_cell_retry_and_frozen_state_should_survive_cross_round_rebuild() -> None:
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-cross-round-cell",
            "workspace_id": "ws-1",
            "target_id": "run-cross-round-cell",
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {"question": "what", "profile_key": "DEFAULT"},
        }
    )
    plan = build_research_plan(task_input)
    empty = build_state_ledger(task_input, plan, [], [], [])
    prior_cell = ResearchStateCell(
        cell_id="entity-test:method",
        row_id="entity-test",
        entity_id="entity-test",
        column_key="method",
        candidate_value="candidate",
        status="FROZEN",
        repair_count=3,
        verdict_reason="retry budget exhausted",
    )
    rebuilt_cell = prior_cell.model_copy(
        update={"status": "CANDIDATE_READY", "repair_count": 0, "verdict_reason": ""}
    )
    prior = empty.model_copy(update={"cells": [prior_cell]})
    rebuilt = empty.model_copy(update={"cells": [rebuilt_cell]})

    seeded = _seed_cell_history(prior, rebuilt)
    merged = _merge_ledger_history(prior, rebuilt)

    assert seeded.cells[0].status == "FROZEN"
    assert seeded.cells[0].repair_count == 3
    assert merged.cells[0].status == "FROZEN"
    assert merged.cells[0].repair_count == 3
    assert merged.cells[0].verdict_reason == "retry budget exhausted"


def test_local_verifier_should_warn_on_forbidden_control_patterns() -> None:
    from app.models import ResearchEvidenceCard, ResearchReadWindow, ResearchSearchHit

    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-forbidden-pattern",
            "workspace_id": "ws-1",
            "target_id": "run-forbidden-pattern",
            "source_scope": [
                {
                    "source_id": "src-1",
                    "title": "Forbidden Source",
                    "summary": "This source includes a forbidden phrase.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Keep findings grounded."],
                "forbidden_patterns": ["forbidden phrase"],
            },
            "input_payload": {"question": "what", "profile_key": "DEFAULT"},
        }
    )
    plan = build_research_plan(task_input)
    search_hits = [
        ResearchSearchHit(
            hit_id="hit-1",
            source_id="src-1",
            source_title="Forbidden Source",
            query="what",
            rank=1,
            snippet="This source includes a forbidden phrase.",
            confidence_score=0.9,
            retrieval_reason="test",
            search_angle="direct",
            adapter="workspace",
            provider="workspace",
        )
    ]
    read_windows = [
        ResearchReadWindow(
            window_id="window-1",
            hit_id="hit-1",
            source_id="src-1",
            source_title="Forbidden Source",
            query="what",
            read_focus="test",
            window_text="This source includes a forbidden phrase.",
            retention_reason="test",
            token_estimate=10,
        )
    ]
    evidence_cards = [
        ResearchEvidenceCard(
            evidence_id="ev-1",
            window_id="window-1",
            source_id="src-1",
            source_title="Forbidden Source",
            claim_text="This claim repeats a forbidden phrase.",
            quote_text="This source includes a forbidden phrase.",
            relation_type="SUPPORTS",
            support_score=0.9,
            conflict_score=0.0,
        )
    ]
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, evidence_cards)

    result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, evidence_cards)

    assert any(record.reason_code == "FORBIDDEN_PATTERN_FOUND" for record in result.decision_records)
    assert any("forbidden" in warning for warning in result.warnings)


def test_local_verifier_should_warn_on_single_source_dominance() -> None:
    from app.models import ResearchStateLedger, ResearchStateRow

    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-source-dominance",
            "workspace_id": "ws-1",
            "target_id": "run-source-dominance",
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Keep findings grounded."],
            },
            "input_payload": {"question": "what", "profile_key": "DEFAULT"},
        }
    )
    plan = build_research_plan(task_input)
    rows = [
        ResearchStateRow(
            row_id=f"row-{index}",
            entity_id=f"entity-{index}",
            source_id="src-dominant",
            source_title="Dominant Source",
            row_status="VERIFIED",
            source_quality="WORKSPACE_SOURCE",
        )
        for index in range(4)
    ]
    ledger = ResearchStateLedger(
        rows=rows,
        verified_row_count=4,
        coverage_score=1.0,
        search_hit_count=4,
        read_window_count=4,
        evidence_card_count=4,
    )

    result = run_local_verifier(task_input, plan, ledger, [], [], [])

    assert any(record.reason_code == "SINGLE_SOURCE_DOMINANCE" for record in result.decision_records)


def test_global_verifier_should_not_depend_on_local_pass_for_ready_decision() -> None:
    from app.models import LocalVerifierResult, ResearchStateLedger, ResearchStateRow

    ledger = ResearchStateLedger(
        rows=[
            ResearchStateRow(
                row_id="row-1",
                entity_id="entity-1",
                source_id="src-1",
                source_title="Source 1",
                row_status="VERIFIED",
                source_quality="WORKSPACE_SOURCE",
            )
        ],
        verified_row_count=1,
        coverage_score=1.0,
    )
    local_result = LocalVerifierResult(
        status="WARN",
        warnings=["local advisory warning"],
        recovery_actions=["review local advisory warning"],
    )

    result = run_global_verifier(local_result, ledger, [])

    assert result.status == "PASS"
    assert result.decision == "READY_TO_WRITE"


def test_entity_candidate_set_should_freeze_after_wide_discovery() -> None:
    first = ResearchStateLedger(
        entities=[ResearchEntityRow(entity_id="entity-a", display_name="A")],
        rows=[ResearchStateRow(row_id="entity-a", entity_id="entity-a")],
    )
    frozen = _freeze_entity_candidate_set(first, None)

    assert frozen.entity_set_status == "FROZEN"
    assert frozen.entity_set_version == 1
    assert frozen.frozen_entity_ids == ["entity-a"]

    next_round = ResearchStateLedger(
        entities=[
            ResearchEntityRow(entity_id="entity-a", display_name="A"),
            ResearchEntityRow(entity_id="entity-b", display_name="B"),
        ],
        rows=[
            ResearchStateRow(row_id="entity-a", entity_id="entity-a"),
            ResearchStateRow(row_id="entity-b", entity_id="entity-b"),
        ],
    )
    still_frozen = _freeze_entity_candidate_set(next_round, frozen)

    assert [entity.entity_id for entity in still_frozen.entities] == ["entity-a"]
    assert [row.row_id for row in still_frozen.rows] == ["entity-a"]
    assert still_frozen.rejected_candidate_entity_ids == ["entity-b"]
    assert still_frozen.entity_set_version == 1


def test_requirement_progress_should_be_recomputed_from_final_cell_verdicts() -> None:
    ledger = ResearchStateLedger(
        rows=[ResearchStateRow(
            row_id="entity-1",
            entity_id="entity-1",
            row_status="CANDIDATE_READY",
            matched_requirement_ids=["goal_finding"],
            ready_requirement_ids=["goal_finding"],
            requirement_completion_status="READY",
        )],
        cells=[ResearchStateCell(
            cell_id="entity-1:method",
            row_id="entity-1",
            entity_id="entity-1",
            column_key="method",
            candidate_value="candidate",
            status="CANDIDATE_READY",
            is_required=True,
            required_by_requirement_ids=["goal_finding"],
            satisfied_requirement_ids=["goal_finding"],
            requirement_completion_status="READY",
        )],
        required_finding_progress=[ResearchRequiredFindingProgress(
            requirement_id="goal_finding",
            requirement_type="GOAL_FINDING",
            label="Goal",
            target_row_count=1,
            accepted_row_statuses=["VERIFIED"],
            required_columns=["method"],
            matched_row_ids=["entity-1"],
            ready_row_ids=["entity-1"],
            status="READY",
        )],
        requirement_ready_row_count=1,
    )

    recomputed = _recompute_requirement_progress_after_cell_verification(ledger)

    assert recomputed.required_finding_progress[0].status == "PARTIAL"
    assert recomputed.required_finding_progress[0].ready_row_ids == []
    assert recomputed.rows[0].requirement_completion_status == "PARTIAL"
    assert recomputed.cells[0].requirement_completion_status == "MISSING"
    assert recomputed.requirement_ready_row_count == 0


def test_evidence_entities_should_replace_source_hits_as_table_rows() -> None:
    from app.models import ResearchEvidenceCard, ResearchSearchHit

    task_input = ResearchTaskInput.model_validate({
        "task_id": "task-entity-source-separation",
        "workspace_id": "ws-1",
        "target_id": "run-entity-source-separation",
        "control_pack": {"pack_type": "research", "target_key": "DEFAULT", "task_neighborhood": "RESEARCH_DEFAULT"},
        "input_payload": {"question": "Compare Product Alpha", "profile_key": "DEFAULT"},
    })
    plan = build_research_plan(task_input)
    column = plan.research_schema.columns[0].key
    hits = [
        ResearchSearchHit(
            hit_id=f"hit-{index}", source_id=f"src-{index}", source_title=f"Source Page {index}",
            query="Product Alpha", rank=index, snippet="evidence", confidence_score=0.8,
            retrieval_reason="test",
        )
        for index in (1, 2)
    ]
    cards = [
        ResearchEvidenceCard(
            evidence_id=f"ev-{index}", window_id=f"window-{index}", source_id=f"src-{index}",
            source_title=f"Source Page {index}", claim_text="Product Alpha evidence",
            quote_text="Product Alpha evidence", relation_type="SUPPORTS", support_score=0.8,
            conflict_score=0.0, entity_id="entity-product-alpha", entity_name="Product Alpha",
            column_key=column,
        )
        for index in (1, 2)
    ]

    ledger = build_state_ledger(task_input, plan, hits, [], cards)

    assert [entity.entity_id for entity in ledger.entities] == ["entity-product-alpha"]
    assert ledger.entities[0].source_ids == ["src-1", "src-2"]
    assert [row.row_id for row in ledger.rows] == ["entity-product-alpha"]
