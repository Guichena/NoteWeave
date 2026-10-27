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


def test_paragraph_citing_only_unknown_evidence_is_dropped_and_empty_article_falls_back() -> None:
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

    # 唯一的段落只引用了不存在的证据，去掉后没有内容，才退回确定性报告
    assert result.mode == "FALLBACK"
    assert result.reason == "NARRATIVE_SECTIONS_MISSING"
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


def test_comparison_row_with_unbound_fact_is_dropped_and_the_article_kept() -> None:
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

    assert result.mode == "LLM"
    assert "40 ms" not in result.markdown
    assert result.narrative["comparison_table"] == {}
    assert "Version 2.1 records a measured latency of 50 ms." in result.markdown


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


def test_required_comparison_table_is_built_from_cells_when_llm_omits_it() -> None:
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
        "cells": [
            {"cell_key": "subject:answer", "candidate_value": "提供 JSON 能力", "evidence_keys": ["evidence:answer"],
             "guarded": False, "entity_label": "PostgreSQL 17", "column_label": "JSON"},
            {"cell_key": "other:answer", "candidate_value": "支持 JSON 类型", "evidence_keys": ["evidence:other"],
             "guarded": False, "entity_label": "MySQL 8.4", "column_label": "JSON"},
        ],
        "limitations": [],
    })

    assert result.mode == "LLM"
    assert "## 对比表" in result.markdown
    assert result.narrative["comparison_table"]["rows"][0]["evidence_keys"] == ["evidence:other", "evidence:answer"]


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
        "executive_summary": "部分索引事实已核验，其余对应项仍缺证据。",
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

    # 确定性报告按问题的各个方面分节
    assert "## 索引类型" in result.markdown


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


def test_only_the_paragraph_with_an_unsupported_number_is_dropped() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Latency findings",
        "executive_summary": "Latency is measured.",
        "sections": [{"heading": "Observed performance", "paragraphs": [
            {"text": "Version 2.1 records a measured latency of 50 ms.", "cell_keys": ["latency"], "evidence_keys": ["evidence-a"]},
            {"text": "Version 3.0 records 40 ms.", "cell_keys": ["latency"], "evidence_keys": ["evidence-a"]},
        ]}],
    })})

    result = NarrativeReportPolisher(client).polish(INPUT)

    assert result.mode == "LLM"
    assert "50 ms" in result.markdown and "3.0" not in result.markdown


INLINE_INPUT = {
    "question": "Kafka 和 Pulsar 的消息顺序保证有什么区别？",
    "cells": [
        {"cell_key": "kafka:ordering", "candidate_value": "Kafka 在单个分区内保证顺序。",
         "evidence_keys": ["evidence:aa01", "evidence:aa02"], "guarded": False},
        {"cell_key": "pulsar:ordering", "candidate_value": "Pulsar 的 Key_Shared 订阅按 key 保证顺序。",
         "evidence_keys": ["evidence:bb01"], "guarded": False},
    ],
    "limitations": [],
}


def _inline_article(paragraph_text: str, row_cells: list[str]) -> str:
    return json.dumps({
        "title": "顺序保证比较",
        "executive_summary": "Kafka 按分区保证顺序，Pulsar 的 Key_Shared 订阅按 key 保证顺序。",
        "sections": [{"heading": "直接回答", "paragraphs": [{
            "text": paragraph_text, "cell_keys": ["kafka:ordering", "pulsar:ordering"], "evidence_keys": []}]}],
        "comparison_table": {"columns": ["维度", "Kafka", "Pulsar"], "rows": [{
            "cells": row_cells, "cell_keys": ["kafka:ordering", "pulsar:ordering"],
            "evidence_keys": ["evidence:aa01", "evidence:aa02", "evidence:bb01"]}]},
        "limitations": [],
    })


def test_inline_citations_stay_next_to_their_sentences() -> None:
    client = FakeLlmClient({"research.synthesis": _inline_article(
        "Kafka 在单个分区内保证顺序 [evidence:aa01]。Pulsar 的 Key_Shared 订阅按 key 保证顺序 [evidence:bb01]。",
        ["顺序范围", "单个分区 [evidence:aa01]", "按 key [evidence:bb01]"])})

    result = NarrativeReportPolisher(client).polish(INLINE_INPUT)

    assert result.mode == "LLM"
    paragraph = result.narrative["sections"][0]["paragraphs"][0]
    assert paragraph["evidence_keys"] == ["evidence:aa01", "evidence:bb01"]
    assert ("Kafka 在单个分区内保证顺序 [evidence:aa01]。Pulsar 的 Key_Shared 订阅按 key 保证顺序 [evidence:bb01]。\n"
            in result.markdown)
    assert "| 顺序范围 | 单个分区 [evidence:aa01] | 按 key [evidence:bb01] |" in result.markdown
    # 段末和表格最后一列都不再堆叠引用
    assert "[evidence:aa02]" not in result.markdown
    assert result.narrative["comparison_table"]["rows"][0]["evidence_keys"] == ["evidence:aa01", "evidence:bb01"]


def test_inline_citation_outside_bound_cells_requires_rewriting_instead_of_removing_the_marker() -> None:
    client = FakeLlmClient({"research.synthesis": _inline_article(
        "Kafka 在单个分区内保证顺序 [evidence:aa01] [evidence:zz99]。Pulsar 按 key 保证顺序 [evidence:bb01]。",
        ["顺序范围", "单个分区 [evidence:zz99]", "按 key [evidence:bb01]"])})

    result = NarrativeReportPolisher(client).polish(INLINE_INPUT)

    assert result.mode == "FALLBACK"
    assert "evidence:zz99" not in result.markdown
    assert result.reason == "NARRATIVE_UNSUPPORTED_TYPED_FACT"


def test_paragraph_citing_another_cells_evidence_binds_that_cell_instead_of_being_dropped() -> None:
    article = json.loads(_inline_article(
        "Kafka 在单个分区内保证顺序 [evidence:aa01]。Pulsar 的 Key_Shared 订阅按 key 保证顺序 [evidence:bb01]。",
        ["顺序范围", "单个分区 [evidence:aa01]", "按 key [evidence:bb01]"]))
    # 模型只写了一格，却同时引用了另一格的证据
    article["sections"][0]["paragraphs"][0]["cell_keys"] = ["kafka:ordering"]
    client = FakeLlmClient({"research.synthesis": json.dumps(article)})

    result = NarrativeReportPolisher(client).polish(INLINE_INPUT)

    assert result.mode == "LLM"
    paragraph = result.narrative["sections"][0]["paragraphs"][0]
    assert paragraph["cell_keys"] == ["kafka:ordering", "pulsar:ordering"]
    assert "Pulsar 的 Key_Shared 订阅按 key 保证顺序 [evidence:bb01]。" in result.markdown
    assert "补充核验结果" not in result.markdown


def test_comparison_row_citing_another_cells_evidence_is_kept() -> None:
    article = json.loads(_inline_article(
        "Kafka 在单个分区内保证顺序 [evidence:aa01]。Pulsar 的 Key_Shared 订阅按 key 保证顺序 [evidence:bb01]。",
        ["顺序范围", "单个分区 [evidence:aa01]", "按 key [evidence:bb01]"]))
    article["comparison_table"]["rows"][0]["cell_keys"] = ["kafka:ordering"]
    client = FakeLlmClient({"research.synthesis": json.dumps(article)})

    result = NarrativeReportPolisher(client).polish(INLINE_INPUT)

    row = result.narrative["comparison_table"]["rows"][0]
    assert row["cell_keys"] == ["kafka:ordering", "pulsar:ordering"]
    assert "| 顺序范围 | 单个分区 [evidence:aa01] | 按 key [evidence:bb01] |" in result.markdown


def test_only_the_unsupported_sentence_is_removed_from_a_paragraph() -> None:
    client = FakeLlmClient({"research.synthesis": _inline_article(
        "Kafka 在单个分区内保证顺序 [evidence:aa01]。两者的顺序范围不同。Kafka 从 3.0 起默认开启幂等。"
        "Pulsar 的 Key_Shared 订阅按 key 保证顺序 [evidence:bb01]。",
        ["顺序范围", "单个分区 [evidence:aa01]", "按 key [evidence:bb01]"])})

    result = NarrativeReportPolisher(client).polish(INLINE_INPUT)

    assert result.mode == "LLM"
    paragraph = result.narrative["sections"][0]["paragraphs"][0]
    # 不带引用又写了数字的一句去掉，同段有引用的句子和不含事实的过渡句照常保留
    assert paragraph["text"] == ("Kafka 在单个分区内保证顺序 [evidence:aa01]。两者的顺序范围不同。"
                                 "Pulsar 的 Key_Shared 订阅按 key 保证顺序 [evidence:bb01]。")
    assert paragraph["cell_keys"] == ["kafka:ordering", "pulsar:ordering"]
    assert "3.0" not in result.markdown


def test_summary_keeps_two_citations_and_row_label_drops_repeated_ones() -> None:
    article = json.loads(_inline_article(
        "Kafka 在单个分区内保证顺序 [evidence:aa01]。Pulsar 的 Key_Shared 订阅按 key 保证顺序 [evidence:bb01]。",
        ["顺序范围 [evidence:aa01]", "单个分区 [evidence:aa01]", "按 key [evidence:bb01]"]))
    article["executive_summary"] = ("Kafka 按分区保证顺序，Pulsar 按 key 保证顺序 "
                                    "[evidence:aa01][evidence:bb01]。")
    client = FakeLlmClient({"research.synthesis": json.dumps(article)})

    result = NarrativeReportPolisher(client).polish(INLINE_INPUT)

    assert result.mode == "LLM"
    assert "Pulsar 按 key 保证顺序 [evidence:aa01][evidence:bb01]。" in result.markdown
    assert "| 顺序范围 | 单个分区 [evidence:aa01] | 按 key [evidence:bb01] |" in result.markdown


def test_one_bad_paragraph_does_not_discard_the_rest_of_the_article() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Latency findings",
        "executive_summary": "The measured baseline was verified.",
        "sections": [{"heading": "Findings", "paragraphs": [
            {"text": "Version 2.1 records a measured latency of 50 ms.", "cell_keys": ["latency"],
             "evidence_keys": ["evidence-a"]},
            {"text": "An unrelated claim.", "cell_keys": ["missing-cell"], "evidence_keys": ["evidence-a"]},
            "not a paragraph",
        ]}, {"heading": "", "paragraphs": []}],
    })})

    result = NarrativeReportPolisher(client).polish(INPUT)

    assert result.mode == "LLM"
    assert "Version 2.1 records a measured latency of 50 ms." in result.markdown
    assert "An unrelated claim." not in result.markdown


def test_fallback_groups_findings_by_aspect_with_a_comparison_table() -> None:
    result = NarrativeReportPolisher(None).polish({
        "question": "比较 Kafka 与 Pulsar 的顺序保证",
        "cells": [
            {"cell_key": "k:order", "candidate_value": "分区内有序。其余细节略。", "evidence_keys": ["evidence:k1"],
             "entity_label": "Kafka", "column_label": "顺序范围"},
            {"cell_key": "p:order", "candidate_value": "Key_Shared 按 key 有序", "evidence_keys": ["evidence:p1"],
             "entity_label": "Pulsar", "column_label": "顺序范围"},
        ],
        "limitations": ["重平衡的影响没有找到可核验的来源"],
    })

    assert result.mode == "FALLBACK"
    assert "## 顺序范围" in result.markdown
    assert "Kafka：分区内有序。其余细节略。 [evidence:k1]" in result.markdown
    assert "| 顺序范围 | 分区内有序。 [evidence:k1] | Key_Shared 按 key 有序 [evidence:p1] |" in result.markdown
    assert "下文按问题的各个方面列出已核验的结论及其来源" in result.markdown
    assert "## 局限" in result.markdown


def test_truncated_model_output_keeps_the_completed_paragraphs() -> None:
    full = json.dumps({
        "title": "Latency findings",
        "executive_summary": "The measured baseline was verified.",
        "sections": [{"heading": "Findings", "paragraphs": [
            {"text": "Version 2.1 records a measured latency of 50 ms.", "cell_keys": ["latency"],
             "evidence_keys": ["evidence-a"]},
            {"text": "This second paragraph was cut off by the output limit", "cell_keys": ["latency"],
             "evidence_keys": ["evidence-a"]},
        ]}],
    })
    truncated = full[: full.index("This second paragraph") + 20]

    result = NarrativeReportPolisher(FakeLlmClient({"research.synthesis": truncated})).polish(INPUT)

    assert result.mode == "LLM"
    assert "Version 2.1 records a measured latency of 50 ms." in result.markdown
    assert "cut off" not in result.markdown


def test_comparison_sentence_may_name_both_subjects_while_citing_one_side() -> None:
    from app.narrative_report import _cited_text_supported

    pg = {"cell_key": "pg", "candidate_value": "GIN indexes", "entity_label": "PostgreSQL 17", "evidence_keys": ["evidence:pg"],
          "evidence_quotes": [{"evidence_key": "evidence:pg", "quote": "PostgreSQL 17 supports GIN indexes."}]}
    mysql = {"cell_key": "mysql", "candidate_value": "Virtual column indexes", "entity_label": "MySQL 8.4",
             "evidence_keys": ["evidence:mysql"],
             "evidence_quotes": [{"evidence_key": "evidence:mysql", "quote": "MySQL 8.4 indexes virtual columns."}]}
    cells = [pg, mysql]

    # 比较句同时提到两边、只引用一边的原文，与 Java 侧校验口径一致
    assert _cited_text_supported("Unlike MySQL, PostgreSQL 17 uses GIN [evidence:pg].", [pg], ["evidence:pg"], cells)
    # 只提到一个对象却引用另一个对象的证据，仍然拒绝
    assert not _cited_text_supported("PostgreSQL indexes virtual columns [evidence:mysql].", cells, ["evidence:mysql"], cells)


def test_percent_word_in_the_quote_supports_the_percent_sign() -> None:
    from app.narrative_report import _cited_text_supported

    cell = {"cell_key": "survey", "candidate_value": "主观生产力均值为60.6%", "entity_label": "Japanese survey",
            "evidence_keys": ["evidence:survey"],
            "evidence_quotes": [{"evidence_key": "evidence:survey", "quote": "the mean productivity of WFH was 60.6 percent"}]}

    assert _cited_text_supported("主观生产力均值为60.6% [evidence:survey]。", [cell], ["evidence:survey"], [cell])
    assert not _cited_text_supported("主观生产力均值为61% [evidence:survey]。", [cell], ["evidence:survey"], [cell])


def test_omitted_cell_falls_back_to_its_quote_when_the_value_has_an_unsupported_number() -> None:
    from app.narrative_report import _cited_text_supported, _omitted_cell_text

    cell = {"cell_key": "survey", "candidate_value": "主观生产力均值约为六成，即 60%", "entity_label": "Japanese survey",
            "evidence_keys": ["evidence:survey"], "guarded": False,
            "evidence_quotes": [{"evidence_key": "evidence:survey", "quote": "[Survey] the mean productivity of WFH was 60.6 percent"}]}

    text = _omitted_cell_text(cell, [cell], True)
    # 结论里的 60% 原文中没有，补段改用原文，并去掉原文前的标题
    assert text == "Japanese survey：the mean productivity of WFH was 60.6 percent"
    assert _cited_text_supported(text, [cell], ["evidence:survey"], [cell])
