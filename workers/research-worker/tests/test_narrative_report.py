import json

from app.llm_client import FakeLlmClient
from app.narrative_report import NarrativeReportPolisher


INPUT = {
    "cells": [{
        "cell_key": "latency",
        "candidate_value": "Measured latency is 50 ms in version 2.1.",
        "evidence_keys": ["evidence-a"],
        "guarded": False,
    }],
    "limitations": ["The sample covers one release."],
}


def test_polishes_frozen_cells_into_evidence_bound_article() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Latency findings",
        "executive_summary": "The verified measurement establishes a clear baseline.",
        "sections": [{
            "heading": "Observed performance",
            "paragraphs": [{
                "text": "Version 2.1 records a measured latency of 50 ms.",
                "cell_keys": ["latency"],
                "evidence_keys": ["evidence-a"],
            }],
        }],
        "limitations": ["The sample covers one release."],
    })})

    result = NarrativeReportPolisher(client).polish(INPUT)

    assert result.mode == "LLM"
    assert result.narrative["title"] == "Latency findings"
    assert "Version 2.1" in result.markdown
    assert "[evidence-a]" in result.markdown
    assert len(client.calls) == 1


def test_unknown_evidence_forces_deterministic_fallback() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Untrusted",
        "executive_summary": "Summary",
        "sections": [{
            "heading": "Finding",
            "paragraphs": [{
                "text": "Latency is 50 ms.",
                "cell_keys": ["latency"],
                "evidence_keys": ["invented-evidence"],
            }],
        }],
    })})

    result = NarrativeReportPolisher(client).polish(INPUT)

    assert result.mode == "FALLBACK"
    assert result.reason == "NARRATIVE_UNKNOWN_EVIDENCE"
    assert "invented-evidence" not in result.markdown
    assert "[evidence-a]" in result.markdown


def test_new_typed_fact_forces_fallback() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Untrusted",
        "executive_summary": "Summary",
        "sections": [{
            "heading": "Finding",
            "paragraphs": [{
                "text": "Version 3.0 records latency of 40 ms.",
                "cell_keys": ["latency"],
                "evidence_keys": ["evidence-a"],
            }],
        }],
    })})

    result = NarrativeReportPolisher(client).polish(INPUT)

    assert result.mode == "FALLBACK"
    assert result.reason == "NARRATIVE_UNSUPPORTED_TYPED_FACT"
    assert "3.0" not in result.markdown


def test_renders_evidence_bound_comparison_table() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Latency comparison",
        "executive_summary": "The verified measurement establishes a baseline.",
        "sections": [{
            "heading": "Observed performance",
            "paragraphs": [{
                "text": "Version 2.1 records a measured latency of 50 ms.",
                "cell_keys": ["latency"],
                "evidence_keys": ["evidence-a"],
            }],
        }],
        "comparison_table": {
            "columns": ["Dimension", "Result"],
            "rows": [{
                "cells": ["Latency", "50 ms in version 2.1"],
                "cell_keys": ["latency"],
                "evidence_keys": ["evidence-a"],
            }],
        },
    })})

    result = NarrativeReportPolisher(client).polish(INPUT)

    assert result.mode == "LLM"
    assert "## 对比表" in result.markdown
    assert "| Latency | 50 ms in version 2.1 [evidence-a] |" in result.markdown
    assert result.narrative["comparison_table"]["columns"] == ["Dimension", "Result"]


def test_comparison_table_with_unbound_fact_forces_fallback() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Latency comparison",
        "executive_summary": "Summary",
        "sections": [{
            "heading": "Observed performance",
            "paragraphs": [{
                "text": "Version 2.1 records a measured latency of 50 ms.",
                "cell_keys": ["latency"],
                "evidence_keys": ["evidence-a"],
            }],
        }],
        "comparison_table": {
            "columns": ["Dimension", "Result"],
            "rows": [{
                "cells": ["Latency", "40 ms in version 3.0"],
                "cell_keys": ["latency"],
                "evidence_keys": ["evidence-a"],
            }],
        },
    })})

    result = NarrativeReportPolisher(client).polish(INPUT)

    assert result.mode == "FALLBACK"
    assert result.reason == "NARRATIVE_UNSUPPORTED_TYPED_FACT"
    assert "40 ms" not in result.markdown


def test_invalid_json_and_missing_client_have_stable_fallback() -> None:
    invalid = NarrativeReportPolisher(FakeLlmClient({"research.synthesis": "not json"})).polish(INPUT)
    unavailable = NarrativeReportPolisher(None).polish(INPUT)

    assert invalid.mode == unavailable.mode == "FALLBACK"
    assert invalid.markdown == unavailable.markdown
    assert invalid.reason == "NARRATIVE_INVALID_JSON"
    assert unavailable.reason == "LLM_UNAVAILABLE"


def test_comparison_report_allows_versions_from_trusted_question_and_requires_table() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "PostgreSQL 17 与 MySQL 8.4 对比",
        "executive_summary": "两者均提供 JSON 能力。",
        "sections": [{
            "heading": "结论",
            "paragraphs": [{
                "text": "PostgreSQL 17 与 MySQL 8.4 均提供 JSON 能力。",
                "cell_keys": ["subject:answer"],
                "evidence_keys": ["evidence:answer"],
            }],
        }],
        "comparison_table": {
            "columns": ["产品", "结论"],
            "rows": [
                {"cells": ["PostgreSQL 17", "提供 JSON 能力"], "cell_keys": ["subject:answer"], "evidence_keys": ["evidence:answer"]},
                {"cells": ["MySQL 8.4", "提供 JSON 能力"], "cell_keys": ["subject:answer"], "evidence_keys": ["evidence:answer"]},
            ],
        },
        "limitations": [],
    })})
    result = NarrativeReportPolisher(client).polish({
        "question": "比较 PostgreSQL 17 与 MySQL 8.4 的 JSON 能力",
        "cells": [{"cell_key": "subject:answer", "candidate_value": "提供 JSON 能力", "evidence_keys": ["evidence:answer"], "guarded": False}],
        "limitations": [],
    })

    assert result.mode == "LLM"
    assert "## 对比表" in result.markdown


def test_comparison_report_falls_back_when_llm_omits_required_table() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "对比报告",
        "executive_summary": "结论摘要",
        "sections": [{
            "heading": "结论",
            "paragraphs": [{"text": "提供 JSON 能力", "cell_keys": ["subject:answer"], "evidence_keys": ["evidence:answer"]}],
        }],
        "comparison_table": {},
        "limitations": [],
    })})
    result = NarrativeReportPolisher(client).polish({
        "question": "比较 PostgreSQL 17 与 MySQL 8.4 的 JSON 能力",
        "cells": [{"cell_key": "subject:answer", "candidate_value": "提供 JSON 能力", "evidence_keys": ["evidence:answer"], "guarded": False}],
        "limitations": [],
    })

    assert result.mode == "FALLBACK"
    assert result.reason == "NARRATIVE_COMPARISON_TABLE_REQUIRED"
