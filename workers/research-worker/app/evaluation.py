from __future__ import annotations

from datetime import date
from decimal import Decimal, InvalidOperation
import re
from statistics import mean
from typing import Any


def evaluate_research_result(gold: dict[str, object], result_payload: dict[str, object]) -> dict[str, object]:
    ledger = _dict(result_payload.get("state_ledger"))
    rows = _dict_items(ledger.get("rows"))
    cells = _dict_items(ledger.get("cells"))
    expected_entities = {str(item) for item in gold.get("expected_entities", [])}
    actual_entities = {str(row.get("display_name") or row.get("source_title") or "") for row in rows}
    entity_precision, entity_recall, entity_f1 = _prf(actual_entities, expected_entities)

    expected_cells = {
        (str(item.get("entity")), str(item.get("column")))
        for item in _dict_items(gold.get("required_cells"))
    }
    row_name_by_id = {
        str(row.get("entity_id") or row.get("row_id")): str(row.get("display_name") or row.get("source_title") or "")
        for row in rows
    }
    actual_cells = {
        (row_name_by_id.get(str(cell.get("entity_id") or cell.get("row_id")), ""), str(cell.get("column_key") or ""))
        for cell in cells
        if str(cell.get("status") or "") == "VERIFIED"
    }
    cell_precision, cell_recall, cell_f1 = _prf(actual_cells, expected_cells)
    expected_columns = {column for _entity, column in expected_cells}
    actual_columns = {column for _entity, column in actual_cells}
    column_precision, column_recall, column_f1 = _prf(actual_columns, expected_columns)
    ready_entities = {
        str(row.get("display_name") or row.get("source_title") or "")
        for row in rows
        if str(row.get("requirement_completion_status") or "") == "READY"
    }
    row_precision, row_recall, row_f1 = _prf(ready_entities, expected_entities)
    expected_values = {
        (str(item.get("entity")), str(item.get("column"))): str(item.get("value"))
        for item in _dict_items(gold.get("required_cells"))
        if item.get("value") is not None
    }
    actual_values = {
        (row_name_by_id.get(str(cell.get("entity_id") or cell.get("row_id")), ""), str(cell.get("column_key") or "")):
        str(cell.get("candidate_value") or "")
        for cell in cells
        if str(cell.get("status") or "") == "VERIFIED"
    }
    value_matches = sum(
        _values_match(
            actual_values.get(key, ""),
            expected,
            _cell_gold_config(gold, key),
        )
        for key, expected in expected_values.items()
    )
    cell_value_accuracy = round(value_matches / max(1, len(expected_values)), 4) if expected_values else 1.0

    citation = _dict(result_payload.get("citation_verification"))
    counterfactual = _dict(result_payload.get("counterfactual_summary"))
    expected_branch = bool(gold.get("expect_counterfactual", False))
    actual_branch = bool(counterfactual.get("has_counterfactual_recheck", False))
    branches = _dict_items(counterfactual.get("branches"))
    resolved_branch = any(
        str(item.get("branch_status") or "").upper() in {"RESOLVED", "RESOLVED_BRANCH"}
        and bool(item.get("result_evidence_ids"))
        and bool(str(item.get("resolution") or "").strip())
        for item in branches
    )
    branch_success = 1.0 if (resolved_branch if expected_branch else not actual_branch) else 0.0
    required_rows = max(1, len(expected_entities))
    complete_rows = sum(1 for row in rows if str(row.get("requirement_completion_status")) == "READY")

    association_accuracy = float(citation.get("association_accuracy") or 0.0)
    support_accuracy = float(citation.get("support_accuracy") or 0.0)
    min_row_completeness = float(gold.get("min_row_completeness", 1.0))
    min_citation_accuracy = float(gold.get("min_citation_accuracy", 1.0))
    row_completeness = round(min(1.0, complete_rows / required_rows), 4)
    passed = (
        entity_recall == 1.0
        and cell_recall == 1.0
        and cell_value_accuracy == 1.0
        and row_completeness >= min_row_completeness
        and association_accuracy >= min_citation_accuracy
        and support_accuracy >= min_citation_accuracy
        and branch_success == 1.0
    )
    exact_table_evaluable, exact_table_success, exact_table_failures = _evaluate_exact_table(
        gold=gold,
        actual_entities=actual_entities,
        actual_cells=actual_cells,
        actual_values=actual_values,
        row_name_by_id=row_name_by_id,
    )
    return {
        "case_key": str(gold.get("case_key") or "unknown"),
        "core_entity_accuracy": round(entity_recall, 4),
        "entity_precision": entity_precision,
        "entity_recall": entity_recall,
        "entity_f1": entity_f1,
        "required_column_f1": column_f1,
        "column_precision": column_precision,
        "column_recall": column_recall,
        "column_f1": column_f1,
        "cell_precision": cell_precision,
        "cell_recall": cell_recall,
        "cell_f1": cell_f1,
        "cell_value_accuracy": cell_value_accuracy,
        "row_precision": row_precision,
        "row_recall": row_recall,
        "row_f1": row_f1,
        "row_completeness": row_completeness,
        "citation_association_accuracy": association_accuracy,
        "citation_support_accuracy": support_accuracy,
        "branch_correction_success": branch_success,
        # `passed` remains the historical coverage gate.  It deliberately
        # tolerates extra rows/cells, which is useful for regression triage but
        # is not a substitute for an exact-table benchmark result.
        "exact_table_evaluable": exact_table_evaluable,
        "exact_table_success": exact_table_success,
        "exact_table_failure_reasons": exact_table_failures,
        "strict_passed": (
            bool(exact_table_success)
            if bool(gold.get("require_exact_table_match", False))
            else None
        ),
        "passed": passed,
    }


def aggregate_rollouts(evaluations: list[dict[str, object]], costs: list[dict[str, object]] | None = None) -> dict[str, object]:
    costs = costs or []
    score_keys = [
        "core_entity_accuracy",
        "cell_f1",
        "cell_value_accuracy",
        "column_f1",
        "row_f1",
        "row_completeness",
        "citation_association_accuracy",
        "citation_support_accuracy",
        "branch_correction_success",
    ]
    return {
        "rollout_count": len(evaluations),
        "pass_at_n": 1.0 if any(bool(item.get("passed")) for item in evaluations) else 0.0,
        "pass_rate": round(sum(bool(item.get("passed")) for item in evaluations) / max(1, len(evaluations)), 4),
        "avg": {key: round(mean(float(item.get(key) or 0.0) for item in evaluations), 4) if evaluations else 0.0 for key in score_keys},
        "max": {key: max((float(item.get(key) or 0.0) for item in evaluations), default=0.0) for key in score_keys},
        "token_cost": {
            "avg_tokens": round(mean(float(item.get("input_tokens", 0)) + float(item.get("output_tokens", 0)) for item in costs), 2) if costs else 0.0,
            "avg_cost": round(mean(float(item.get("estimated_cost", 0.0)) for item in costs), 8) if costs else 0.0,
            "avg_retry_overhead": round(mean(float(item.get("retry_count", 0)) for item in costs), 4) if costs else 0.0,
        },
    }


def _prf(actual: set[Any], expected: set[Any]) -> tuple[float, float, float]:
    if not expected:
        return (1.0, 1.0, 1.0) if not actual else (0.0, 1.0, 0.0)
    true_positive = len(actual & expected)
    precision = true_positive / max(1, len(actual))
    recall = true_positive / len(expected)
    f1 = 2 * precision * recall / max(0.000001, precision + recall)
    return round(precision, 4), round(recall, 4), round(f1, 4)


def _dict(value: object) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _dict_items(value: object) -> list[dict[str, Any]]:
    return [item for item in value or [] if isinstance(item, dict)]


def _normalize_value(value: object) -> str:
    normalized = " ".join(str(value).strip().lower().split())
    return normalized.strip("`*_ ")


def _cell_gold_config(gold: dict[str, object], key: tuple[str, str]) -> dict[str, object]:
    for item in _dict_items(gold.get("required_cells")):
        if (str(item.get("entity")), str(item.get("column"))) == key:
            return item
    return {}


def _values_match(actual: object, expected: object, config: dict[str, object]) -> bool:
    """Use an explicit per-cell rule instead of silently fuzzy-matching values."""
    match_type = str(config.get("value_match") or "normalized_exact").strip().lower()
    actual_text = _normalize_value(actual)
    expected_text = _normalize_value(expected)
    if match_type in {"exact", "normalized_exact"}:
        return actual_text == expected_text
    if match_type == "number_near":
        actual_number = _parse_number(actual_text)
        expected_number = _parse_number(expected_text)
        if actual_number is None or expected_number is None:
            return False
        tolerance = Decimal(str(config.get("relative_tolerance", 0)))
        absolute_tolerance = Decimal(str(config.get("absolute_tolerance", 0)))
        return abs(actual_number - expected_number) <= max(
            absolute_tolerance,
            abs(expected_number) * tolerance,
        )
    if match_type == "date_near":
        actual_date = _parse_iso_date(actual_text)
        expected_date = _parse_iso_date(expected_text)
        if actual_date is None or expected_date is None:
            return False
        days = int(config.get("date_tolerance_days", 0))
        return abs((actual_date - expected_date).days) <= max(0, days)
    raise ValueError(f"unsupported value_match={match_type!r} for gold cell {config!r}")


def _parse_number(value: str) -> Decimal | None:
    normalized = value.replace(",", "").strip()
    is_percent = normalized.endswith("%")
    if is_percent:
        normalized = normalized[:-1].strip()
    if not re.fullmatch(r"[+-]?(?:\d+(?:\.\d+)?|\.\d+)", normalized):
        return None
    try:
        result = Decimal(normalized)
    except InvalidOperation:
        return None
    return result / Decimal(100) if is_percent else result


def _parse_iso_date(value: str) -> date | None:
    match = re.fullmatch(r"(\d{4})-(\d{2})-(\d{2})", value)
    if not match:
        return None
    try:
        return date(*map(int, match.groups()))
    except ValueError:
        return None


def _evaluate_exact_table(
    *,
    gold: dict[str, object],
    actual_entities: set[str],
    actual_cells: set[tuple[str, str]],
    actual_values: dict[tuple[str, str], str],
    row_name_by_id: dict[str, str],
) -> tuple[bool, bool | None, list[str]]:
    """Evaluate a full table only when the gold data actually specifies one.

    DeepWideSearch's headline score is an exact DataFrame comparison after its
    per-column normalisation.  Older NoteWeave cases only describe coverage,
    so reporting an exact score for them would be misleading.
    """
    required_cells = _dict_items(gold.get("required_cells"))
    require_exact = bool(gold.get("require_exact_table_match", False))
    values_complete = bool(required_cells) and all("value" in item for item in required_cells)
    evaluable = require_exact and values_complete
    if not evaluable:
        return False, None, ([] if not require_exact else ["EXACT_GOLD_VALUES_MISSING"])

    expected_entities = {str(item) for item in gold.get("expected_entities", [])}
    expected_cells = {
        (str(item.get("entity")), str(item.get("column")))
        for item in required_cells
    }
    failures: list[str] = []
    if actual_entities != expected_entities:
        failures.append("ENTITY_SET_MISMATCH")
    if actual_cells != expected_cells:
        failures.append("VERIFIED_CELL_SET_MISMATCH")
    for item in required_cells:
        key = (str(item.get("entity")), str(item.get("column")))
        if not _values_match(actual_values.get(key, ""), item.get("value"), item):
            failures.append(f"CELL_VALUE_MISMATCH:{key[0]}:{key[1]}")
    # `row_name_by_id` is intentionally accepted as an audit hook: it makes
    # callers construct values from the entity-bound ledger rather than a
    # report string, even though the entity-set comparison above is sufficient.
    del row_name_by_id
    return True, not failures, failures
