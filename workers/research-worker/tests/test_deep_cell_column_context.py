"""深挖任务抽取时要带上列的真实含义，原文片段按列截取且不从单词中间开始。"""

from __future__ import annotations

from app.extractor import _locate_whitespace_tolerant, _snap_to_boundaries


def test_excerpt_does_not_start_or_end_inside_a_word() -> None:
    text = "only difference being that there may be more than one insert. The JSON type stores documents."
    start = text.index("ly difference")
    end = text.index("documents") + 4

    excerpt = _snap_to_boundaries(text, start, end)

    assert excerpt.startswith("difference being")
    assert excerpt.endswith("stores")


def test_chinese_excerpt_keeps_its_bounds() -> None:
    text = "虚拟线程在 JDK 21 正式发布，此前两个版本为预览特性。"
    assert _snap_to_boundaries(text, 2, 12) == text[2:12]


def test_quote_with_changed_line_breaks_maps_back_to_the_exact_source_text() -> None:
    text = "Intro.\nOf the two operator classes for type jsonb,\n  jsonb_ops is the default."
    located = _locate_whitespace_tolerant(text, "Of the two operator classes for type jsonb, jsonb_ops is the default.")

    assert located is not None
    start, quote = located
    assert text[start:start + len(quote)] == quote
    assert "\n  " in quote


def test_padded_or_too_short_quotes_are_not_relaxed() -> None:
    text = "Verifier gated synthesis must cite opened windows"
    assert _locate_whitespace_tolerant(text, " Verifier gated synthesis must cite opened windows ") is None
    assert _locate_whitespace_tolerant(text, "Verifier  gated") is None


def test_claim_is_a_short_answer_but_falls_back_to_the_quote_when_numbers_are_not_quoted() -> None:
    from app.extractor import _grounded_claim

    quote = "This JEP proposes to finalize virtual threads in JDK 21."
    assert _grounded_claim("虚拟线程在 JDK 21 正式发布", quote) == "虚拟线程在 JDK 21 正式发布"
    # 结论里的 444 不在引用原文中，Java 侧会判为引用不支撑，退回原文
    assert _grounded_claim("JDK 21 正式发布（JEP 444）", quote) == quote


def test_english_doc_sentence_matches_through_column_intents_for_a_chinese_question() -> None:
    from types import SimpleNamespace

    from app.extractor import _intent_terms_text, _quote_matches_question_intent

    column = SimpleNamespace(label="使用步骤与命令", description="如何发起增量备份",
                             search_intents=["pg_basebackup incremental backup"])
    plan = SimpleNamespace(normalized_question="PostgreSQL 17 新增的增量备份怎么使用？",
                           research_schema=SimpleNamespace(columns=[column]), stop_contract={"deep_cell_entity_label": "PostgreSQL 17"})
    quote = "Use pg_basebackup --incremental=PATH to take an incremental backup."

    assert not _quote_matches_question_intent(plan.normalized_question, quote)
    assert _quote_matches_question_intent(_intent_terms_text(plan), quote)


def test_english_paper_matches_a_chinese_conflict_question_through_issued_queries() -> None:
    from types import SimpleNamespace

    from app.extractor import _intent_terms_text, _quote_matches_question_intent

    column = SimpleNamespace(label="支持证据", description="支持该说法的研究", search_intents=[])
    plan = SimpleNamespace(normalized_question="提高测试覆盖率阈值能否减少线上缺陷？",
                           research_schema=SimpleNamespace(columns=[column]), stop_contract={},
                           query_set=["test coverage threshold defect escape empirical study"],
                           query_specs=[SimpleNamespace(query="code coverage quality gate escaped defects case study")])

    assert _quote_matches_question_intent(_intent_terms_text(plan), "Higher coverage did not reduce escaped defects.")
    # 检索词里限定来源类型的词不算切题
    assert not _quote_matches_question_intent(_intent_terms_text(plan), "This empirical study was funded by a grant.")


def test_repair_queries_skip_duplicates_instead_of_dropping_the_whole_answer() -> None:
    import json
    from types import SimpleNamespace

    from app.cell_query_planner import CellColumn, CellQueryContext
    from app.repair_queries import plan_repair_queries

    class Llm:
        def complete_json(self, purpose, payload):
            return json.dumps({"strategy": "NARROW", "queries": ["pg_basebackup incremental", "summarize_wal setting"]})

    context = CellQueryContext(question="PostgreSQL 17 增量备份", entity_label="PostgreSQL 17",
                               columns=(CellColumn("c", "前提条件", search_intents=("summarize_wal",)),), max_queries=3)
    plan, _ = plan_repair_queries(context, {"repair_cause": "NO_VALID_QUOTE", "tried_queries": ["pg_basebackup incremental"]}, Llm())

    assert plan.method == "LLM"
    assert [spec.query for spec in plan.specs] == ["summarize_wal setting"]


def test_multi_column_task_gets_one_excerpt_per_column_from_a_long_window() -> None:
    from types import SimpleNamespace

    from app.extractor import _window_excerpt

    filler = "The server streams the base backup over the replication protocol. " * 60
    options = "-i old_manifest_file\n--incremental=old_manifest_file\n  Performs an incremental backup."
    restore = "An incremental backup cannot be used directly; pg_combinebackup must first combine it."
    text = "pg_basebackup -- take a base backup\n\n" + filler + options + " " + filler + restore + " " + filler
    columns = [
        SimpleNamespace(label="增量备份执行方式", description="命令行参数",
                        search_intents=["pg_basebackup --incremental old_manifest_file option"]),
        SimpleNamespace(label="合并与恢复流程", description="",
                        search_intents=["pg_combinebackup combine incremental backup restore"]),
    ]
    plan = SimpleNamespace(normalized_question="PostgreSQL 17 增量备份怎么使用？",
                           research_schema=SimpleNamespace(columns=columns),
                           stop_contract={"deep_cell_entity_label": "PostgreSQL 17"})
    window = SimpleNamespace(window_text=text, query="PostgreSQL 17 incremental backup")

    excerpt = _window_excerpt(plan, window)

    assert "--incremental=old_manifest_file" in excerpt
    assert "pg_combinebackup must first combine it" in excerpt
    assert excerpt.startswith("pg_basebackup -- take a base backup")
    # 每段都是原文中连续的一段
    assert all(part in text for part in excerpt.split("\n\n") if part)
