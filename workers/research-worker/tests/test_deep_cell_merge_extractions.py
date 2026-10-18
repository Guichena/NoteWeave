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
