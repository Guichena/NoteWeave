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
    assert invalid.llm_calls == 2
    assert unavailable.llm_calls == 0


def test_invalid_first_answer_is_retried_with_the_failure_reason() -> None:
    valid = json.dumps({
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
    })

    class FlakyClient(FakeLlmClient):
        def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
            self.calls.append((purpose, payload))
            return "{truncated" if len(self.calls) == 1 else valid

    client = FlakyClient({})
    result = NarrativeReportPolisher(client).polish(INPUT)

    # 第一次返回坏 JSON 时带上失败原因重试，第二次成功就用模型合成的正文
    assert result.mode == "LLM"
    assert result.llm_calls == 2
    assert client.calls[1][1]["previous_attempt_error"] == "NARRATIVE_INVALID_JSON"


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


def test_supporting_quotes_and_labels_are_trusted_and_reach_the_model() -> None:
    enriched = {
        "question": "比较 PostgreSQL 17 的 JSON 索引",
        "cells": [{
            "cell_key": "entity-pg:dim-index",
            "candidate_value": "jsonb_path_ops supports fewer operators but offers better performance.",
            "evidence_keys": ["evidence-a"],
            "guarded": False,
            "entity_label": "PostgreSQL 17",
            "column_label": "索引类型",
            "supporting_quotes": ["The default GIN operator class for jsonb supports 4 operators."],
        }],
        "limitations": ["MySQL 8.4 · 索引类型：必需研究项未找到足够证据"],
    }
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "PostgreSQL 17 的 JSON 索引",
        "executive_summary": "已核验 PostgreSQL 17 的部分索引事实，MySQL 8.4 的对应项仍缺证据。",
        "sections": [{
            "heading": "PostgreSQL 17 · 索引类型",
            "paragraphs": [{
                # 数字 17 与 4 来自研究对象名和支撑原文，不在单元格结论里
                "text": "PostgreSQL 17 默认的 jsonb GIN 操作符类支持 4 个操作符，jsonb_path_ops 支持的更少但性能更好。",
                "cell_keys": ["entity-pg:dim-index"],
                "evidence_keys": ["evidence-a"],
            }],
        }],
        "limitations": ["MySQL 8.4 · 索引类型：必需研究项未找到足够证据"],
    })})

    result = NarrativeReportPolisher(client).polish(enriched)

    assert result.mode == "LLM"
    assert "## 局限" in result.markdown
    request = client.calls[0][1]
    assert request["frozen_cells"][0]["entity_label"] == "PostgreSQL 17"
    assert request["frozen_cells"][0]["supporting_quotes"]
    rules = " ".join(request["rules"])
    assert "same language as research_question" in rules
    assert "must not be generalized" in rules


def test_fallback_headings_use_entity_and_column_labels() -> None:
    result = NarrativeReportPolisher(None).polish({
        "cells": [{"cell_key": "entity-1:dim-a", "candidate_value": "Value", "evidence_keys": ["e"],
                   "entity_label": "MySQL 8.4", "column_label": "索引类型"}],
    })

    assert "## MySQL 8.4 · 索引类型" in result.markdown


def test_malformed_comparison_row_is_dropped_instead_of_failing_the_article() -> None:
    cells = {
        "question": "比较 A 与 B",
        "cells": [
            {"cell_key": "a:x", "candidate_value": "A uses GIN.", "evidence_keys": ["ea"]},
            {"cell_key": "b:x", "candidate_value": "B uses multi-valued indexes.", "evidence_keys": ["eb"]},
        ],
    }
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "A 与 B 的索引",
        "executive_summary": "两者的索引方式不同。",
        "sections": [{"heading": "索引", "paragraphs": [
            {"text": "A 使用 GIN。", "cell_keys": ["a:x"], "evidence_keys": ["ea"]},
            {"text": "B 使用多值索引。", "cell_keys": ["b:x"], "evidence_keys": ["eb"]}]}],
        "comparison_table": {"columns": ["对象", "索引"], "rows": [
            {"cells": ["A", "GIN"], "cell_keys": ["a:x"], "evidence_keys": ["ea"]},
            {"cells": ["B", "多值索引"], "cell_keys": ["b:x"], "evidence_keys": ["eb"]},
            {"cells": ["C"], "cell_keys": ["a:x"], "evidence_keys": ["ea"]},
        ]},
    })})

    result = NarrativeReportPolisher(client).polish(cells)

    assert result.mode == "LLM"
    assert len(result.narrative["comparison_table"]["rows"]) == 2
