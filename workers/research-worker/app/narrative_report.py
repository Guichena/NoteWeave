"""Evidence-bound reader report synthesis with a deterministic fallback."""

from __future__ import annotations

import re
from dataclasses import dataclass, replace

from app.json_repair import parse_json_payload
from app.llm_client import LlmClient


_TYPED_TOKEN = re.compile(
    r"(?<![\w])(?:\d+(?:[.,]\d+)*(?:\s?(?:%|ms|s|MB|GB|TB|USD|EUR|CNY))?|v?\d+\.\d+(?:\.\d+)?)(?![\w])",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class NarrativeReportResult:
    narrative: dict[str, object]
    markdown: str
    mode: str
    reason: str | None = None
    # 实际发出的模型调用次数，宿主按角色预算核对
    llm_calls: int = 0


class NarrativeReportPolisher:
    """Turn frozen cells into prose without granting the model authority over facts."""

    def __init__(self, llm_client: LlmClient | None) -> None:
        self.llm_client = llm_client

    def polish(self, synthesis_input: dict[str, object]) -> NarrativeReportResult:
        cells = _validated_cells(synthesis_input)
        limitations = _clean_strings(synthesis_input.get("limitations"))
        question = str(synthesis_input.get("question") or "").strip()
        comparison_required = _comparison_table_required(question)
        if self.llm_client is None:
            return _fallback(cells, limitations, "LLM_UNAVAILABLE", question)
        request_payload: dict[str, object] = {
            "contract": {
                "title": "non-empty string",
                "executive_summary": "evidence-bound summary",
                "sections": [{
                    "heading": "non-empty string",
                    "paragraphs": [{
                        "text": "article-style prose",
                        "cell_keys": ["frozen cell keys"],
                        "evidence_keys": ["evidence keys attached to those cells"],
                    }],
                }],
                "comparison_table": {
                    "columns": ["comparison dimensions, only when useful"],
                    "rows": [{
                        "cells": ["one value per column"],
                        "cell_keys": ["frozen cell keys supporting the row"],
                        "evidence_keys": ["accepted evidence keys supporting the row"],
                    }],
                },
                "limitations": ["known limitations only"],
            },
            "rules": [
                "Use only facts present in frozen_cells.",
                "Every paragraph must cite at least one cell_key and evidence_key.",
                "Do not add sources, numbers, dates, versions, names, or conclusions.",
                "Write a cohesive professional research article, not an audit log.",
                "Return JSON only.",
                "For a comparison question, comparison_table is required and must contain at least two evidence-bound rows.",
            ],
            "frozen_cells": cells,
            "research_question": question,
            "known_limitations": limitations,
        }
        # 模型偶尔返回无法解析或不合契约的 JSON；带上失败原因重试一次，仍失败才退回确定性模板
        failure = ""
        for attempt in range(1, 3):
            payload = request_payload if not failure else {**request_payload, "previous_attempt_error": failure}
            parsed = parse_json_payload(self.llm_client.complete_json("research.synthesis", payload))
            try:
                narrative = _normalize_narrative(parsed, cells, limitations, question, comparison_required)
            except ValueError as exc:
                failure = str(exc)
                continue
            return NarrativeReportResult(narrative, _render_markdown(narrative), "LLM", llm_calls=attempt)
        return replace(_fallback(cells, limitations, failure, question), llm_calls=2)


def _validated_cells(synthesis_input: dict[str, object]) -> list[dict[str, object]]:
    cells: list[dict[str, object]] = []
    for raw in synthesis_input.get("cells", []):
        if not isinstance(raw, dict):
            continue
        cell_key = str(raw.get("cell_key") or "").strip()
        value = str(raw.get("candidate_value") or "").strip()
        evidence_keys = _clean_strings(raw.get("evidence_keys"))
        if not cell_key or not value or not evidence_keys:
            raise ValueError("SYNTHESIS cell lacks verified value or accepted evidence")
        cells.append({
            "cell_key": cell_key,
            "candidate_value": value,
            "evidence_keys": evidence_keys,
            "guarded": bool(raw.get("guarded", False)),
        })
    if not cells:
        raise ValueError("SYNTHESIS input has no frozen cells")
    return sorted(cells, key=lambda item: str(item["cell_key"]))


def _normalize_narrative(
    payload: object,
    cells: list[dict[str, object]],
    known_limitations: list[str],
    trusted_context: str = "",
    comparison_required: bool = False,
) -> dict[str, object]:
    if not isinstance(payload, dict):
        raise ValueError("NARRATIVE_INVALID_JSON")
    title = str(payload.get("title") or "").strip()
    summary = str(payload.get("executive_summary") or "").strip()
    if not title or not summary:
        raise ValueError("NARRATIVE_HEADER_MISSING")
    cells_by_key = {str(cell["cell_key"]): cell for cell in cells}
    sections: list[dict[str, object]] = []
    for raw_section in payload.get("sections", []):
        if not isinstance(raw_section, dict):
            raise ValueError("NARRATIVE_SECTION_INVALID")
        heading = str(raw_section.get("heading") or "").strip()
        paragraphs: list[dict[str, object]] = []
        if not heading:
            raise ValueError("NARRATIVE_SECTION_HEADING_MISSING")
        for raw_paragraph in raw_section.get("paragraphs", []):
            if not isinstance(raw_paragraph, dict):
                raise ValueError("NARRATIVE_PARAGRAPH_INVALID")
            text = str(raw_paragraph.get("text") or "").strip()
            cell_keys = _clean_strings(raw_paragraph.get("cell_keys"))
            evidence_keys = _clean_strings(raw_paragraph.get("evidence_keys"))
            if not text or not cell_keys or not evidence_keys:
                raise ValueError("NARRATIVE_PARAGRAPH_UNBOUND")
            if any(key not in cells_by_key for key in cell_keys):
                raise ValueError("NARRATIVE_UNKNOWN_CELL")
            bound_cells = [cells_by_key[key] for key in cell_keys]
            allowed_evidence = {
                key for cell in bound_cells for key in cell["evidence_keys"]  # type: ignore[union-attr]
            }
            if any(key not in allowed_evidence for key in evidence_keys):
                raise ValueError("NARRATIVE_UNKNOWN_EVIDENCE")
            source_text = " ".join([trusted_context, *(str(cell["candidate_value"]) for cell in bound_cells)])
            source_tokens = {_canonical_token(token) for token in _TYPED_TOKEN.findall(source_text)}
            paragraph_tokens = {_canonical_token(token) for token in _TYPED_TOKEN.findall(text)}
            if not paragraph_tokens.issubset(source_tokens):
                raise ValueError("NARRATIVE_UNSUPPORTED_TYPED_FACT")
            paragraphs.append({
                "text": text,
                "cell_keys": cell_keys,
                "evidence_keys": evidence_keys,
            })
        if not paragraphs:
            raise ValueError("NARRATIVE_SECTION_EMPTY")
        sections.append({"heading": heading, "paragraphs": paragraphs})
    if not sections:
        raise ValueError("NARRATIVE_SECTIONS_MISSING")
    comparison_table = _normalize_comparison_table(
        payload.get("comparison_table"), cells_by_key, trusted_context
    )
    if comparison_required and not comparison_table:
        raise ValueError("NARRATIVE_COMPARISON_TABLE_REQUIRED")
    requested_limitations = _clean_strings(payload.get("limitations"))
    limitations = [item for item in requested_limitations if item in known_limitations]
    return {
        "schema_version": "research-reader-report.v1",
        "title": title,
        "executive_summary": summary,
        "sections": sections,
        "comparison_table": comparison_table,
        "limitations": limitations,
    }


def _fallback(
    cells: list[dict[str, object]], limitations: list[str], reason: str, question: str = ""
) -> NarrativeReportResult:
    sections = []
    for cell in cells:
        value = str(cell["candidate_value"])
        if cell["guarded"]:
            value += " (limited by unresolved evidence)"
        sections.append({
            "heading": _reader_heading(str(cell["cell_key"])),
            "paragraphs": [{
                "text": value,
                "cell_keys": [str(cell["cell_key"])],
                "evidence_keys": list(cell["evidence_keys"]),
            }],
        })
    narrative: dict[str, object] = {
        "schema_version": "research-reader-report.v1",
        "title": question or "研究报告",
        "executive_summary": "以下报告仅综合已通过核验的研究结论，并为每项事实保留可回溯的证据引用。",
        "sections": sections,
        "comparison_table": {},
        "limitations": limitations,
    }
    return NarrativeReportResult(narrative, _render_markdown(narrative), "FALLBACK", reason)


def _render_markdown(narrative: dict[str, object]) -> str:
    lines = [f"# {narrative['title']}", "", str(narrative["executive_summary"]), ""]
    for section in narrative["sections"]:  # type: ignore[union-attr]
        lines.extend([f"## {section['heading']}", ""])
        for paragraph in section["paragraphs"]:
            citations = " ".join(f"[{key}]" for key in paragraph["evidence_keys"])
            lines.extend([f"{paragraph['text']} {citations}".strip(), ""])
    comparison_table = narrative.get("comparison_table")
    if isinstance(comparison_table, dict) and comparison_table:
        columns = comparison_table["columns"]
        lines.extend(["## 对比表", "", "| " + " | ".join(_escape_table_cell(item) for item in columns) + " |"])
        lines.append("| " + " | ".join("---" for _ in columns) + " |")
        for row in comparison_table["rows"]:
            cells = list(row["cells"])
            citations = " ".join(f"[{key}]" for key in row["evidence_keys"])
            cells[-1] = f"{cells[-1]} {citations}".strip()
            lines.append("| " + " | ".join(_escape_table_cell(item) for item in cells) + " |")
        lines.append("")
    limitations = narrative.get("limitations")
    if isinstance(limitations, list) and limitations:
        lines.extend(["## Limitations", ""])
        lines.extend(f"- {item}" for item in limitations)
        lines.append("")
    return "\n".join(lines).strip() + "\n"


def _clean_strings(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return list(dict.fromkeys(str(item).strip() for item in value if str(item).strip()))


def _normalize_comparison_table(
    value: object, cells_by_key: dict[str, dict[str, object]], trusted_context: str = ""
) -> dict[str, object]:
    if value in (None, {}):
        return {}
    if not isinstance(value, dict):
        raise ValueError("NARRATIVE_COMPARISON_TABLE_INVALID")
    columns = _string_items(value.get("columns"))
    raw_rows = value.get("rows")
    if len(columns) < 2 or len(columns) > 6 or not isinstance(raw_rows, list) or not raw_rows:
        raise ValueError("NARRATIVE_COMPARISON_TABLE_INVALID")
    rows: list[dict[str, object]] = []
    for raw_row in raw_rows:
        if not isinstance(raw_row, dict):
            raise ValueError("NARRATIVE_COMPARISON_ROW_INVALID")
        row_cells = _string_items(raw_row.get("cells"))
        cell_keys = _clean_strings(raw_row.get("cell_keys"))
        evidence_keys = _clean_strings(raw_row.get("evidence_keys"))
        if len(row_cells) != len(columns) or not cell_keys or not evidence_keys:
            raise ValueError("NARRATIVE_COMPARISON_ROW_INVALID")
        if any(key not in cells_by_key for key in cell_keys):
            raise ValueError("NARRATIVE_UNKNOWN_CELL")
        bound_cells = [cells_by_key[key] for key in cell_keys]
        allowed_evidence = {
            key for cell in bound_cells for key in cell["evidence_keys"]  # type: ignore[union-attr]
        }
        if any(key not in allowed_evidence for key in evidence_keys):
            raise ValueError("NARRATIVE_UNKNOWN_EVIDENCE")
        source_text = " ".join([trusted_context, *(str(cell["candidate_value"]) for cell in bound_cells)])
        source_tokens = {_canonical_token(token) for token in _TYPED_TOKEN.findall(source_text)}
        row_tokens = {
            _canonical_token(token)
            for token in _TYPED_TOKEN.findall(" ".join(row_cells))
        }
        if not row_tokens.issubset(source_tokens):
            raise ValueError("NARRATIVE_UNSUPPORTED_TYPED_FACT")
        rows.append({
            "cells": row_cells,
            "cell_keys": cell_keys,
            "evidence_keys": evidence_keys,
        })
    return {"columns": columns, "rows": rows}


def _comparison_table_required(question: str) -> bool:
    normalized = question.casefold()
    comparison_markers = ("compare", "comparison", "versus", " vs ", "比较", "对比", "差异")
    products = [product for product in ("postgresql", "mysql", "mongodb", "redis", "openai", "anthropic") if product in normalized]
    return any(marker in normalized for marker in comparison_markers) and len(products) >= 2


def _escape_table_cell(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ").strip()


def _string_items(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def _canonical_token(value: str) -> str:
    return re.sub(r"\s+", "", value).lower().replace(",", "")


def _reader_heading(cell_key: str) -> str:
    field = cell_key.rsplit(":", 1)[-1].lower()
    return {
        "answer": "核心结论",
        "key_evidence": "关键证据",
        "implications": "影响与适用建议",
        "limitations": "限制与边界",
    }.get(field, field.replace("_", " ").strip().title() or "研究发现")
