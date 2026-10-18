"""合并各列抽取结果时，终止原因要与真正保留的卡片一致，否则完成回执不合契约。"""

from app.deep_cell_executor import _merge_extractions
from app.extraction_result import ExtractionResult, ExtractionTerminationReason
from app.models import ResearchEvidenceCard


def test_cards_filtered_out_after_extraction_do_not_report_accepted_cards() -> None:
    card = ResearchEvidenceCard.model_construct()
    results = [ExtractionResult(accepted_cards=[card]),
               ExtractionResult(termination_reason=ExtractionTerminationReason.NO_READ_WINDOWS)]

    merged = _merge_extractions(results, [])

    assert merged.accepted_count == 0
    assert merged.termination_reason == ExtractionTerminationReason.ALL_CARDS_REJECTED


def test_kept_cards_report_accepted_cards() -> None:
    card = ResearchEvidenceCard.model_construct()
    merged = _merge_extractions([ExtractionResult(accepted_cards=[card])], [card])

    assert merged.termination_reason == ExtractionTerminationReason.ACCEPTED_CARDS


def test_quote_without_markdown_marks_resolves_to_the_original_span() -> None:
    from app.extractor import _locate_whitespace_tolerant

    text = "Claude returns a `tool_use` block with an **id**,\n  `name` and `input`. Then you reply."
    located = _locate_whitespace_tolerant(text, "Claude returns a tool_use block with an id, name and input.")

    assert located is not None
    start, span = located
    assert span == "Claude returns a `tool_use` block with an **id**,\n  `name` and `input`."
    assert text[start:start + len(span)] == span
    assert _locate_whitespace_tolerant(text, "Claude returns a tool_result block") is None


def test_source_tier_trusts_github_only_for_the_project_owner_and_ranks_planned_domains_first() -> None:
    from types import SimpleNamespace

    from app.deep_cell_executor import _source_tier

    snapshot = SimpleNamespace(query_policy={
        "authoritative_domains": ["redis.io", "valkey.io", "github.com"], "entity_label": "Valkey 8"})

    def item(url: str, quality: str = "") -> SimpleNamespace:
        return SimpleNamespace(adapter="external_url", url=url, source_domain="", source_quality=quality)

    assert _source_tier(snapshot, item("https://valkey.io/topics/persistence/")) == 0
    assert _source_tier(snapshot, item("https://github.com/valkey-io/valkey/blob/unstable/valkey.conf")) == 0
    assert _source_tier(snapshot, item("https://github.com/someone/redis-notes")) == 2
    # 云厂商托管服务的文档是官方文档，但不是研究对象自己的文档
    assert _source_tier(snapshot, item("https://docs.cloud.google.com/memorystore/docs/valkey", "OFFICIAL_DOC")) == 1
    assert _source_tier(snapshot, item("https://www.loginradius.com/blog/oauth")) == 2
    # 官方域名下的社区论坛按二手来源处理
    assert _source_tier(snapshot, item("https://community.redis.io/t/aof-question/12")) == 2


def test_quote_extends_to_the_next_sentence_that_names_the_tool_in_the_claim() -> None:
    from app.extractor import _extend_quote_for_claim, _grounded_claim

    text = ("Intro. When restoring an incremental backup, it will be necessary to have not only the incremental "
            "backup itself but also all earlier backups. Use pg_combinebackup to combine them. Unrelated tail.")
    quote = ("When restoring an incremental backup, it will be necessary to have not only the incremental "
             "backup itself but also all earlier backups.")
    claim = "恢复时需要更早的备份，并用 pg_combinebackup 合并"

    start, extended = _extend_quote_for_claim(text, text.find(quote), quote, claim)

    assert extended == quote + " Use pg_combinebackup to combine them."
    assert text[start:start + len(extended)] == extended
    assert _grounded_claim(claim, extended) == claim
    # 附近也找不到时引用不变，结论退回原文
    _, unchanged = _extend_quote_for_claim(text, text.find(quote), quote, "用 pg_restore_incremental 恢复")
    assert unchanged == quote
    assert _grounded_claim("用 pg_restore_incremental 恢复", quote) == quote
