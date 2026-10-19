"""Evidence-bound reader report synthesis with a deterministic fallback."""

from __future__ import annotations

import re
from dataclasses import dataclass, replace

from app.json_repair import parse_json_payload
from app.llm_client import LlmClient


_INLINE_CITATION = re.compile(r"\[(evidence:[^\[\]\s]+)\]")
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
        leads = _validated_leads(synthesis_input, {str(cell["cell_key"]) for cell in cells})
        # 后端偶尔把整次研究的原因码（如 UNRESOLVED_CELLS_REMAIN）放进局限列表，读者看不懂，成文时略去
        limitations = [item for item in _clean_strings(synthesis_input.get("limitations"))
                       if not re.fullmatch(r"[A-Z0-9_]+", item)]
        question = str(synthesis_input.get("question") or "").strip()
        required_points = _clean_strings(synthesis_input.get("required_points"))[:6]
        comparison_required = _comparison_table_required(question, [*cells, *leads])
        if self.llm_client is None:
            return _fallback(cells, limitations, "LLM_UNAVAILABLE", question)
        request_payload: dict[str, object] = {
            "contract": {
                "title": "non-empty string",
                "executive_summary": "evidence-bound summary",
                "sections": [{
                    "heading": "non-empty string",
                    "paragraphs": [{
                        "text": "article-style prose with inline [evidence:...] markers after the sentences they support",
                        "cell_keys": ["frozen cell keys"],
                        "evidence_keys": ["evidence keys attached to those cells"],
                    }],
                }],
                "comparison_table": {
                    "columns": ["comparison dimensions, only when useful"],
                    "rows": [{
                        "cells": ["one value per column, with inline [evidence:...] markers in the cells they support"],
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
                "Never mention frozen cells, ledgers, cell keys or other internal terms in the prose; refer to the "
                "verified sources instead (for example '本次核验的资料显示').",
                "When a cell's supporting_quotes contain several different facts or list items for its column, cover "
                "each relevant one instead of only candidate_value.",
                "Cite inline like a research article: right after each sentence (or inside each table cell) write "
                "the one or two evidence keys whose quotes directly support it, for example "
                "'虚拟线程在 JDK 21 正式发布 [evidence:ab12]。'; never pile every key of a cell at the end. "
                "evidence_keys must list exactly the keys cited inline. The summary cites at most two keys, and "
                "the first table column names the row without citations.",
                "Like a published research report, cite every specific fact (a number, version, name, default or "
                "behaviour) but leave background, transitions and explanations of why a fact matters uncited; such "
                "sentences must not introduce facts that no frozen cell supports.",
                "For a question that weighs supporting against opposing evidence, present both sides with their "
                "sources and the conditions that explain the difference before giving the conclusion.",
                "executive_summary is the bottom line: one or two sentences that answer research_question with the "
                "verified facts, for example 'X became final in JDK 21 (JEP 444)'; never describe the report "
                "instead of answering.",
                "The first section must be headed '直接回答' (or 'Direct answer' for an English question) and give "
                "one short paragraph per part of the question, each bound to the cells that answer it. These "
                "paragraphs add what the summary leaves out (versions, numbers, conditions, exceptions) and must not "
                "repeat the summary sentence. A part that no frozen cell answers is not written there; list it in "
                "limitations instead.",
                "State each fact once in the prose: when two parts of the question share a fact, write it in the "
                "first paragraph and refer back instead of repeating it (the comparison table may restate it briefly).",
                "For a comparison question the summary names the key difference between the compared subjects; "
                "the direct-answer section then gives the details without restating that difference.",
                "Return JSON only.",
                "For a comparison question, comparison_table is required and must contain at least two evidence-bound rows.",
                "Write title, summary, headings, prose and table in the same language as research_question; keep "
                "product names, API names and quoted terms in their original form.",
                "Use entity_label and column_label to say which subject and aspect each fact belongs to; "
                "supporting_quotes are verified source text you may draw details from; a leading [title] is the "
                "source's title. When the question asks for identifiers such as JEP, PEP or RFC numbers or versions, "
                "state them explicitly, taking them from the quote or its title.",
                "State each fact with exactly the scope its quote supports: a statement about one option, operator "
                "class, version or mode must not be generalized to the whole product or topic.",
                "When known_limitations is not empty, say briefly in the summary which parts of the question remain "
                "unanswered, and copy the relevant items into limitations.",
                "tentative_cells have a real source quote but did not pass automatic verification. evidence_level "
                "SOURCED means the quote answers the aspect but the source is third-party or a number or version "
                "did not match exactly; RELATED means the quote is related background that does not directly "
                "answer. When no frozen cell answers a part of the question, you may write a SOURCED tentative "
                "cell's finding in the body or the comparison table, citing it like other cells, and the same "
                "sentence must say it is unverified, for example '据第三方资料（未经核实），……'. Use RELATED cells "
                "only as background. Never use tentative cells in executive_summary and never let them contradict "
                "or replace a frozen cell.",
                "required_points are what an expert reviewer checks in a complete answer. In the direct-answer "
                "section give each point an explicit conclusion drawn from the cells, in the point's own terms: for "
                "'whether development bundles code' write whether each subject bundles, not only what each one "
                "does; for a comparison point state the contrast between the subjects in one sentence. Do not number "
                "or quote the points. A point that no frozen or SOURCED tentative cell answers goes to limitations "
                "and must not be guessed.",
            ],
            "frozen_cells": cells,
            "tentative_cells": leads,
            "required_points": required_points,
            "research_question": question,
            "known_limitations": limitations,
        }
        # 模型偶尔返回无法解析或不合契约的 JSON；带上失败原因重试一次，仍失败才退回确定性模板
        failure = ""
        for attempt in range(1, 3):
            payload = request_payload if not failure else {**request_payload, "previous_attempt_error": failure}
            parsed = parse_json_payload(self.llm_client.complete_json("research.synthesis", payload), allow_truncated=True)
            try:
                narrative = _normalize_narrative(parsed, cells, limitations, question, comparison_required, leads)
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
            "entity_label": str(raw.get("entity_label") or "").strip(),
            "column_label": str(raw.get("column_label") or "").strip(),
            "supporting_quotes": _clean_strings(raw.get("supporting_quotes")),
        })
    if not cells:
        raise ValueError("SYNTHESIS input has no frozen cells")
    return sorted(cells, key=lambda item: str(item["cell_key"]))


_LEAD_LEVELS = ("SOURCED", "RELATED")
# 成文引用未经核实的线索时句子里要有的说明，没有时由程序补上
_UNVERIFIED_MARKERS = ("未经核实", "待核实", "未核实", "第三方", "unverified", "third-party", "third party")


def _validated_leads(synthesis_input: dict[str, object], verified_keys: set[str]) -> list[dict[str, object]]:
    """未通过核验但有原文出处的线索。格式不对或与已核验单元格重复的线索直接略去，不影响成文。"""
    leads: list[dict[str, object]] = []
    seen: set[str] = set()
    for raw in synthesis_input.get("leads", []) or []:
        if not isinstance(raw, dict):
            continue
        cell_key = str(raw.get("cell_key") or "").strip()
        value = str(raw.get("candidate_value") or "").strip()
        evidence_keys = _clean_strings(raw.get("evidence_keys"))
        level = str(raw.get("evidence_level") or "").strip()
        if (not cell_key or not value or not evidence_keys or level not in _LEAD_LEVELS
                or cell_key in verified_keys or cell_key in seen):
            continue
        seen.add(cell_key)
        leads.append({
            "cell_key": cell_key,
            "candidate_value": value,
            "evidence_keys": evidence_keys,
            "evidence_level": level,
            "entity_label": str(raw.get("entity_label") or "").strip(),
            "column_label": str(raw.get("column_label") or "").strip(),
            "supporting_quotes": _clean_strings(raw.get("supporting_quotes")),
        })
    return sorted(leads, key=lambda item: str(item["cell_key"]))


def _mark_unverified(text: str, lead_keys: set[str], chinese: bool) -> str:
    """引用了线索却没有说明未经核实时，在第一个线索引用前补上说明。"""
    if not any(f"[{key}]" in text for key in lead_keys):
        return text
    if any(marker in text.casefold() for marker in _UNVERIFIED_MARKERS):
        return text
    marker = "（未经核实）" if chinese else " (unverified)"
    first = min(text.index(f"[{key}]") for key in lead_keys if f"[{key}]" in text)
    return text[:first].rstrip() + marker + " " + text[first:]


def _normalize_narrative(
    payload: object,
    cells: list[dict[str, object]],
    known_limitations: list[str],
    trusted_context: str = "",
    comparison_required: bool = False,
    leads: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    if not isinstance(payload, dict):
        raise ValueError("NARRATIVE_INVALID_JSON")
    title = str(payload.get("title") or "").strip()
    summary = str(payload.get("executive_summary") or "").strip()
    if not title or not summary:
        raise ValueError("NARRATIVE_HEADER_MISSING")
    dropped_unsupported = 0
    leads = leads or []
    chinese = _writes_chinese(title)
    lead_evidence = {key for lead in leads for key in lead["evidence_keys"]}  # type: ignore[union-attr]
    # 正文和对比表可以引用线索，摘要只用已核验的单元格
    cells_by_key = {str(cell["cell_key"]): cell for cell in [*cells, *leads]}
    # 摘要只是一两句结论，最多保留前两个引用；不属于任何已核验单元格的引用删掉
    summary = _limit_citations(summary, {key for cell in cells for key in cell["evidence_keys"]}, 2)  # type: ignore[union-attr]
    sections: list[dict[str, object]] = []
    for raw_section in payload.get("sections", []):
        # 格式不对的小节、段落只去掉这一处，其余内容照常保留，避免一处小错让整篇退回模板
        if not isinstance(raw_section, dict):
            continue
        heading = str(raw_section.get("heading") or "").strip()
        paragraphs: list[dict[str, object]] = []
        if not heading:
            continue
        for raw_paragraph in raw_section.get("paragraphs", []):
            if not isinstance(raw_paragraph, dict):
                continue
            text = str(raw_paragraph.get("text") or "").strip()
            cell_keys = [key for key in _clean_strings(raw_paragraph.get("cell_keys")) if key in cells_by_key]
            evidence_keys = _clean_strings(raw_paragraph.get("evidence_keys"))
            if not text or not cell_keys or not (evidence_keys or _INLINE_CITATION.search(text)):
                continue
            bound_cells = [cells_by_key[key] for key in cell_keys]
            allowed_evidence = {
                key for cell in bound_cells for key in cell["evidence_keys"]  # type: ignore[union-attr]
            }
            text, inline_keys = _bind_inline_citations(text, allowed_evidence)
            # 不属于本段单元格的引用去掉；一个可用引用都不剩时去掉这一段
            evidence_keys = inline_keys or [key for key in evidence_keys if key in allowed_evidence]
            if not evidence_keys:
                continue
            source_text = " ".join([trusted_context, *(_cell_source_text(cell) for cell in bound_cells)])
            source_tokens = {_canonical_token(token) for token in _TYPED_TOKEN.findall(source_text)}
            paragraph_tokens = {_canonical_token(token) for token in _TYPED_TOKEN.findall(text)}
            if not paragraph_tokens.issubset(source_tokens):
                # 只去掉写了来源里没有的数字或版本号的那一段，其余段落照常保留；全部去掉时才退回模板
                dropped_unsupported += 1
                continue
            text = _mark_unverified(text, lead_evidence, chinese)
            paragraphs.append({
                "text": text,
                "cell_keys": cell_keys,
                "evidence_keys": evidence_keys,
            })
        if not paragraphs:
            continue
        sections.append({"heading": heading, "paragraphs": paragraphs})
    if not sections:
        raise ValueError("NARRATIVE_UNSUPPORTED_TYPED_FACT" if dropped_unsupported else "NARRATIVE_SECTIONS_MISSING")
    comparison_table = _normalize_comparison_table(
        payload.get("comparison_table"), cells_by_key, trusted_context
    )
    if comparison_table:
        for row in comparison_table["rows"]:  # type: ignore[index]
            row["cells"] = [row["cells"][0], *(_mark_unverified(cell, lead_evidence, chinese)
                                               for cell in row["cells"][1:])]
    if comparison_required and not comparison_table:
        # 模型给的对比表整张不可用时，用已核验的单元格和有出处的线索直接排出对比表，不让整篇退回模板
        comparison_table = _cells_table(cells, question_chinese=chinese,
                                        leads=[lead for lead in leads if lead["evidence_level"] == "SOURCED"])
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
    """模型成文两次都不可用时的确定性报告：按问题的各个方面分节，每节列出各研究对象的已核验结论，
    多个研究对象时附对比表。每句话都来自已核验的单元格，并带上对应引用。"""
    chinese = _writes_chinese(question) if question else True
    entities = list(dict.fromkeys(str(cell.get("entity_label") or "") for cell in cells))
    sections = []
    for column in dict.fromkeys(str(cell.get("column_label") or "") for cell in cells):
        column_cells = [cell for cell in cells if str(cell.get("column_label") or "") == column]
        paragraphs = []
        for cell in column_cells:
            value = str(cell["candidate_value"]).strip()
            if cell["guarded"]:
                value += "（该结论的证据仍有分歧）" if chinese else " (evidence still disputed)"
            entity = str(cell.get("entity_label") or "")
            text = f"{entity}：{value}" if chinese and entity and len(entities) > 1 else (
                f"{entity}: {value}" if entity and len(entities) > 1 else value)
            paragraphs.append({"text": text, "cell_keys": [str(cell["cell_key"])],
                               "evidence_keys": list(cell["evidence_keys"])[:2]})
        sections.append({"heading": column or _cell_heading(column_cells[0]), "paragraphs": paragraphs})
    covered = "、".join(name for name in entities if name) if chinese else ", ".join(name for name in entities if name)
    if chinese:
        summary = f"本次研究核验了 {len(cells)} 项结论" + (f"，涉及{covered}" if covered else "") + "，下文按问题的各个方面列出。"
        if limitations:
            summary += "部分问题尚未找到可核验的来源，见文末局限。"
    else:
        summary = f"This report lists {len(cells)} verified findings" + (f" on {covered}" if covered else "") + " by aspect."
        if limitations:
            summary += " Some parts remain unanswered; see Limitations."
    narrative: dict[str, object] = {
        "schema_version": "research-reader-report.v1",
        "title": question or "研究报告",
        "executive_summary": summary,
        "sections": sections,
        "comparison_table": _cells_table(cells, question_chinese=chinese) if len(entities) > 1 else {},
        "limitations": limitations,
    }
    return NarrativeReportResult(narrative, _render_markdown(narrative), "FALLBACK", reason)


def _cells_table(cells: list[dict[str, object]], *, question_chinese: bool = True,
                 leads: list[dict[str, object]] | None = None) -> dict[str, object]:
    """用已核验的单元格直接排出对比表：每行一个方面，每列一个研究对象，格子里是结论和第一条引用。
    已核验的单元格空着的位置用有出处的线索填上，并标明待核实。"""
    entities = list(dict.fromkeys(str(cell.get("entity_label") or "") for cell in [*cells, *(leads or [])]))[:5]
    if not entities:
        return {}
    by_position = {(str(cell.get("entity_label") or ""), str(cell.get("column_label") or "")): cell for cell in cells}
    lead_keys = {str(lead["cell_key"]) for lead in leads or []}
    for lead in leads or []:
        by_position.setdefault((str(lead.get("entity_label") or ""), str(lead.get("column_label") or "")), lead)
    rows = []
    for column in dict.fromkeys(str(cell.get("column_label") or "") for cell in [*cells, *(leads or [])]):
        row_cells = [column or "-"]
        cell_keys: list[str] = []
        evidence_keys: list[str] = []
        for entity in entities:
            cell = by_position.get((entity, column))
            if cell is None:
                row_cells.append("-")
                continue
            key = str(list(cell["evidence_keys"])[0])
            pending = ("（待核实）" if question_chinese else " (unverified)") if str(cell["cell_key"]) in lead_keys else ""
            row_cells.append(f"{_first_sentence(str(cell['candidate_value']))}{pending} [{key}]")
            cell_keys.append(str(cell["cell_key"]))
            evidence_keys.append(key)
        if cell_keys:
            rows.append({"cells": row_cells, "cell_keys": cell_keys, "evidence_keys": evidence_keys})
    if not rows:
        return {}
    header = "方面" if question_chinese else "Aspect"
    return {"columns": [header, *(entity or "-" for entity in entities)], "rows": rows}


def _first_sentence(value: str) -> str:
    """表格里只放结论的第一句，避免整段长文挤进一个格子。"""
    value = " ".join(value.split())
    match = re.search(r"[。；;](?=.)|\.\s", value)
    return value[: match.start() + 1].strip() if match and match.start() < 200 else value


def _render_markdown(narrative: dict[str, object]) -> str:
    lines = [f"# {narrative['title']}", "", str(narrative["executive_summary"]), ""]
    for section in narrative["sections"]:  # type: ignore[union-attr]
        lines.extend([f"## {section['heading']}", ""])
        for paragraph in section["paragraphs"]:
            text = str(paragraph["text"])
            # 已经逐句内联引用的段落原样输出，没有内联引用时才把证据附在段末
            citations = "" if _INLINE_CITATION.search(text) else " ".join(
                f"[{key}]" for key in paragraph["evidence_keys"])
            lines.extend([f"{text} {citations}".strip(), ""])
    comparison_table = narrative.get("comparison_table")
    if isinstance(comparison_table, dict) and comparison_table:
        columns = comparison_table["columns"]
        lines.extend(["## 对比表", "", "| " + " | ".join(_escape_table_cell(item) for item in columns) + " |"])
        lines.append("| " + " | ".join("---" for _ in columns) + " |")
        for row in comparison_table["rows"]:
            cells = list(row["cells"])
            if not any(_INLINE_CITATION.search(str(cell)) for cell in cells):
                citations = " ".join(f"[{key}]" for key in row["evidence_keys"])
                cells[-1] = f"{cells[-1]} {citations}".strip()
            lines.append("| " + " | ".join(_escape_table_cell(item) for item in cells) + " |")
        lines.append("")
    limitations = narrative.get("limitations")
    if isinstance(limitations, list) and limitations:
        lines.extend(["## 局限" if _writes_chinese(str(narrative["title"])) else "## Limitations", ""])
        lines.extend(f"- {item}" for item in limitations)
        lines.append("")
    return "\n".join(lines).strip() + "\n"


def _bind_inline_citations(text: str, allowed: set[str]) -> tuple[str, list[str]]:
    """取出文字里的内联引用：不属于绑定单元格的引用直接删掉，返回清理后的文字和按出现顺序排列的引用。"""
    keys: list[str] = []

    def keep(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in allowed:
            return ""
        if key not in keys:
            keys.append(key)
        return match.group(0)

    cleaned = _INLINE_CITATION.sub(keep, text)
    if len(cleaned) != len(text):
        cleaned = re.sub(r"[ \t]{2,}", " ", cleaned).replace(" 。", "。").strip()
    return cleaned, keys


def _limit_citations(text: str, allowed: set[str], max_keys: int) -> str:
    """只保留属于 allowed 的前 max_keys 个不同引用，其余引用标记删掉。"""
    kept: list[str] = []

    def keep(match: re.Match[str]) -> str:
        key = match.group(1)
        if key in kept:
            return match.group(0)
        if key in allowed and len(kept) < max_keys:
            kept.append(key)
            return match.group(0)
        return ""

    cleaned = _INLINE_CITATION.sub(keep, text)
    if len(cleaned) != len(text):
        cleaned = re.sub(r"[ \t]{2,}", " ", cleaned).replace(" 。", "。").strip()
    return cleaned


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
        return {}
    columns = _string_items(value.get("columns"))
    raw_rows = value.get("rows")
    if len(columns) < 2 or len(columns) > 6 or not isinstance(raw_rows, list) or not raw_rows:
        return {}
    rows: list[dict[str, object]] = []
    for raw_row in raw_rows:
        # 列数不对或没有绑定单元格的行只是格式问题，去掉这一行即可，不必让整篇退回模板；
        # 引用了未知单元格、证据或数字的行仍然整篇拒绝
        if not isinstance(raw_row, dict):
            continue
        row_cells = _string_items(raw_row.get("cells"))
        cell_keys = _clean_strings(raw_row.get("cell_keys"))
        evidence_keys = _clean_strings(raw_row.get("evidence_keys"))
        cited_inline = any(_INLINE_CITATION.search(cell) for cell in row_cells)
        if len(row_cells) != len(columns) or not cell_keys or not (evidence_keys or cited_inline):
            continue
        # 行里有问题（未知单元格、越界引用、来源没有的数字）只去掉这一行
        cell_keys = [key for key in cell_keys if key in cells_by_key]
        if not cell_keys:
            continue
        bound_cells = [cells_by_key[key] for key in cell_keys]
        allowed_evidence = {
            key for cell in bound_cells for key in cell["evidence_keys"]  # type: ignore[union-attr]
        }
        inline_keys: list[str] = []
        for index, cell_text in enumerate(row_cells):
            row_cells[index], keys = _bind_inline_citations(cell_text, allowed_evidence)
            inline_keys.extend(key for key in keys if key not in inline_keys)
        # 第一列是行名，其他格已有引用时行名上的引用只是重复，去掉
        if row_cells and _INLINE_CITATION.search(row_cells[0]) and any(
                _INLINE_CITATION.search(cell) for cell in row_cells[1:]):
            row_cells[0] = _limit_citations(row_cells[0], set(), 0)
        evidence_keys = inline_keys or [key for key in evidence_keys if key in allowed_evidence]
        if not evidence_keys:
            continue
        source_text = " ".join([trusted_context, *(_cell_source_text(cell) for cell in bound_cells)])
        source_tokens = {_canonical_token(token) for token in _TYPED_TOKEN.findall(source_text)}
        row_tokens = {
            _canonical_token(token)
            for token in _TYPED_TOKEN.findall(" ".join(row_cells))
        }
        if not row_tokens.issubset(source_tokens):
            continue
        rows.append({
            "cells": row_cells,
            "cell_keys": cell_keys,
            "evidence_keys": evidence_keys,
        })
    if not rows:
        return {}
    return {"columns": columns, "rows": rows}


_COMPARISON_MARKERS = ("compare", "comparison", "versus", " vs ", " vs.", "比较", "对比", "差异", "区别", "异同")


def _comparison_table_required(question: str, cells: list[dict[str, object]]) -> bool:
    """问题是比较类、且已核验的结论与线索涉及至少两个研究对象时必须给对比表，与 Java 侧判断一致。"""
    normalized = f" {question.casefold()} "
    entities = {str(cell.get("entity_label") or "").strip().casefold() for cell in cells}
    entities.discard("")
    return any(marker in normalized for marker in _COMPARISON_MARKERS) and len(entities) >= 2


def _escape_table_cell(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ").strip()


def _string_items(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def _canonical_token(value: str) -> str:
    return re.sub(r"\s+", "", value).lower().replace(",", "")


def _cell_source_text(cell: dict[str, object]) -> str:
    """成文时可以使用的已核验内容：结论、研究对象名、列名与支撑原文，与 Java 侧校验口径一致。"""
    quotes = cell.get("supporting_quotes") or []
    return " ".join([str(cell["candidate_value"]), str(cell.get("entity_label") or ""),
                     str(cell.get("column_label") or ""), *(str(item) for item in quotes)])


def _cell_heading(cell: dict[str, object]) -> str:
    entity = str(cell.get("entity_label") or "")
    column = str(cell.get("column_label") or "")
    if entity and column:
        return f"{entity} · {column}"
    return column or entity or _reader_heading(str(cell["cell_key"]))


def _writes_chinese(question: str) -> bool:
    return any(0x4E00 <= ord(char) <= 0x9FFF for char in question)


def _reader_heading(cell_key: str) -> str:
    field = cell_key.rsplit(":", 1)[-1].lower()
    return {
        "answer": "核心结论",
        "key_evidence": "关键证据",
        "implications": "影响与适用建议",
        "limitations": "限制与边界",
    }.get(field, field.replace("_", " ").strip().title() or "研究发现")
