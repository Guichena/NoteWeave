from __future__ import annotations

from app.extractor import extract_evidence_cards
from app.llm_client import FakeLlmClient
from app.models import ResearchStateLedger, ResearchStateRow, ResearchTaskInput
from app.planner import build_research_plan
from app.read_adapters import run_research_read
from app.reporter import write_research_report
from app.search import run_workspace_search
from app.state import build_state_ledger
from app.verifier import run_global_verifier, run_local_verifier


def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-llm-1",
            "workspace_id": "ws-1",
            "target_id": "run-llm-1",
            "source_scope": [
                {
                    "source_id": "src-llm",
                    "title": "LLM Evidence Source",
                    "summary": "The source says verifier gated synthesis must cite opened windows.",
                    "sample_text": "Verifier gated synthesis must cite opened windows and reject unsupported claims.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Reject unsupported conclusions."],
            },
            "input_payload": {
                "question": "How should synthesis use evidence?",
                "profile_key": "DEFAULT",
            },
        }
    )


def _read_windows():
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)
    return task_input, plan, search_hits, run_research_read(task_input, plan, search_hits)


def test_llm_extractor_should_generate_evidence_cards_from_valid_json() -> None:
    task_input, plan, _search_hits, read_windows = _read_windows()
    llm_client = FakeLlmClient(
        {
            "research.extract": (
                '{"evidence_cards":[{"window_id":"window-1","claim_text":"'
                'Synthesis must cite opened windows.","quote_text":"Verifier gated '
                'synthesis must cite opened windows.","relation_type":"SUPPORTS",'
                '"support_score":0.93,"conflict_score":0.01}]}'
            )
        }
    )

    cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)

    assert cards[0].evidence_id == "ev-1"
    assert cards[0].claim_text == "Synthesis must cite opened windows."
    assert cards[0].quote_text == "Verifier gated synthesis must cite opened windows."
    assert cards[0].support_score == 0.93


def test_llm_extractor_should_repair_fenced_json_with_trailing_commas() -> None:
    task_input, plan, _search_hits, read_windows = _read_windows()
    llm_client = FakeLlmClient(
        {
            "research.extract": """
```json
{"evidence_cards":[{"window_id":"window-1","claim_text":"Repair works","quote_text":"Opened windows are cited.","relation_type":"SUPPORTS","support_score":0.8,"conflict_score":0.0,},],}
```
"""
        }
    )

    cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)

    assert cards[0].claim_text == "Repair works"
    assert cards[0].quote_text == "Opened windows are cited."


def test_llm_extractor_should_fallback_to_rule_mode_on_unusable_output() -> None:
    task_input, plan, _search_hits, read_windows = _read_windows()
    llm_client = FakeLlmClient({"research.extract": "not-json-at-all"})

    cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)

    assert cards[0].claim_text.endswith("How should synthesis use evidence?.")
    assert "Verifier gated synthesis" in cards[0].quote_text


def test_local_verifier_should_reject_missing_evidence() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    ledger = build_state_ledger(task_input, plan, [], [], [])

    result = run_local_verifier(task_input, plan, ledger, [], [], [])

    assert result.status == "WARN"
    assert "state ledger has no evidence rows" in result.warnings
    assert "attach at least one workspace source before final export" in result.recovery_actions


def test_local_verifier_should_merge_llm_judge_warning() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    cards = extract_evidence_cards(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)
    llm_client = FakeLlmClient(
        {
            "research.verify.local": (
                '{"status":"WARN","warnings":["citation density is weak"],'
                '"recovery_actions":["read one more source window"]}'
            )
        }
    )

    result = run_local_verifier(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        llm_client=llm_client,
    )

    assert result.status == "WARN"
    assert "citation density is weak" in result.warnings
    assert "read one more source window" in result.recovery_actions


def test_report_writer_should_only_cite_existing_evidence_cards() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    cards = extract_evidence_cards(task_input, plan, read_windows)
    ledger = ResearchStateLedger(
        columns=plan.state_columns,
        rows=[
            ResearchStateRow(
                row_id="row-valid",
                source_id=cards[0].source_id,
                source_title=cards[0].source_title,
                search_query=read_windows[0].query,
                read_focus=read_windows[0].read_focus,
                evidence_id=cards[0].evidence_id,
                claim_text=cards[0].claim_text,
                quote_text=cards[0].quote_text,
                evidence_excerpt=cards[0].quote_text,
                relation_type=cards[0].relation_type,
                support_score=cards[0].support_score,
                conflict_score=cards[0].conflict_score,
                support_level="SUPPORTED",
                verifier_note="valid",
            ),
            ResearchStateRow(
                row_id="row-ghost",
                source_id="src-ghost",
                source_title="Ghost Source",
                search_query="ghost",
                read_focus="ghost",
                evidence_id="ev-ghost",
                claim_text="Ghost claim",
                quote_text="Ghost quote",
                evidence_excerpt="Ghost quote",
                relation_type="SUPPORTS",
                support_score=0.99,
                conflict_score=0.0,
                support_level="SUPPORTED",
                verifier_note="invalid",
            ),
        ],
        search_hit_count=len(search_hits),
        read_window_count=len(read_windows),
        evidence_card_count=len(cards),
    )
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)
    global_result = run_global_verifier(local_result, ledger, [])

    report = write_research_report(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    assert "ev-1" in report
    assert "ev-ghost" not in report
    assert "Ghost claim" not in report
