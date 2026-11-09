"""Evidence-bound reader report synthesis with a deterministic fallback."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, replace

from app.json_repair import parse_json_payload
from app.llm_client import LlmClient
from app.execution_control import require_execution_active


_INLINE_CITATION = re.compile(r"\[(evidence:[^\[\]\s]+)\]")
_TYPED_TOKEN = re.compile(
    r"(?<![\w.])\d+(?:[.,]\d+)*(?:\s?(?:%|ms|s|MB|GB|TB|USD|EUR|CNY))?(?!\w|\.\d)"
    r"|(?<![\d.])v?\d+\.\d+(?:\.\d+)*(?!\d|\.\d)",
    re.IGNORECASE | re.ASCII,
)
# 原文常把百分比写成 60.6 percent，结论里写 60.6%；比对数字前统一成 %，与 Java 侧校验口径一致
_PERCENT_WORD = re.compile(r"(\d)\s*(?:per\s?cent\b|％)", re.IGNORECASE)


_MONTH_NAME = (r"Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
               r"Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?")
_MONTH_NUMBERS = {name: index for index, name in enumerate(
    ("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"), 1)}
_DAY = r"0?[1-9]|[12]\d|3[01]"
_MONTH = r"0?[1-9]|1[0-2]"
_YEAR = r"(?:19|20)\d{2}"
# 日期按整体比对：原文 December 15, 2026 与成文 2026 年 12 月 15 日是同一个日期。只认带年份或带"月"字的写法，
# 横向空白可以有，不跨换行；年月之间用点号的只认完整的年月日，避免把 2024.3 这类数字当成日期。
_DATE_PATTERNS = (
    re.compile(rf"(?<![\w.])(?P<day>{_DAY})[ \t]+(?P<mname>{_MONTH_NAME})\.?,?[ \t]+(?P<year>{_YEAR})(?!\w|\.\d)",
               re.I | re.ASCII),
    re.compile(rf"(?<![\w.])(?P<mname>{_MONTH_NAME})\.?[ \t]+(?:(?P<day>{_DAY})(?:st|nd|rd|th)?,?[ \t]+)?"
               rf"(?P<year>{_YEAR})(?!\w|\.\d)", re.I | re.ASCII),
    re.compile(rf"(?<![\w.])(?P<year>{_YEAR})[ \t]*[-/年][ \t]*(?P<month>{_MONTH})"
               rf"(?:[ \t]*[-/月][ \t]*(?P<day>{_DAY})[ \t]*日?|[ \t]*月)?(?![\d.]\d|\d)", re.ASCII),
    re.compile(rf"(?<![\w.])(?P<year>{_YEAR})\.(?P<month>{_MONTH})\.(?P<day>{_DAY})(?!\w|\.\d)", re.ASCII),
    re.compile(rf"(?<![\w.])(?P<month>{_MONTH})[ \t]*月(?:[ \t]*(?P<day>{_DAY})[ \t]*[日号])?", re.ASCII),
)


def _date_tokens(text: str) -> tuple[str, set[str]]:
    """把日期换成按精度分层的标记：年月日给出 年、年-月、年-月-日、月、月-日 各层，只到原文实际写出的精度。
    成文与原文用同一套规则，所以成文比原文更精确（多写了日或月）时仍然对不上；去掉日期后剩下的文字照常
    逐个核对数字。"""
    tokens: set[str] = set()
    spans: list[tuple[int, int]] = []
    for pattern in _DATE_PATTERNS:
        for match in pattern.finditer(text):
            if any(start < match.end() and match.start() < end for start, end in spans):
                continue
            groups = match.groupdict()
            year = groups.get("year")
            if groups.get("mname"):
                month = _MONTH_NUMBERS[groups["mname"][:3].lower()]
            elif groups.get("month"):
                month = int(groups["month"])
            else:
                continue
            day = int(groups["day"]) if groups.get("day") else None
            spans.append((match.start(), match.end()))
            tokens.add(f"month:{month}")
            if day is not None:
                tokens.add(f"monthday:{month}-{day}")
            if year:
                tokens.update({year, f"date:{year}-{month}"})
                if day is not None:
                    tokens.add(f"date:{year}-{month}-{day}")
    if not spans:
        return text, tokens
    stripped = []
    last = 0
    for start, end in sorted(spans):
        stripped.append(text[last:start])
        stripped.append(" ")
        last = end
    stripped.append(text[last:])
    return "".join(stripped), tokens


def _typed_tokens(text: str) -> set[str]:
    remaining, dates = _date_tokens(text)
    return dates | {_canonical_token(token)
                    for token in _TYPED_TOKEN.findall(_PERCENT_WORD.sub(r"\1%", remaining))}


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
        required_points = _clean_strings(synthesis_input.get("required_points"))
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
                "unstated_details": [{"point": "exact required_points text",
                                      "missing": "the requested detail that no quote states, without numbers or dates"}],
                "unanswered_points": ["exact required_points text with no evidence-backed answer"],
                "background_notes": [{"point": "exact required_points text that no cell answers",
                                      "text": "one or two sentences of well-established general knowledge"}],
            },
            "rules": [
                "Use only facts present in frozen_cells.",
                "Preserve each fact's source_scopes: a versioned document establishes that version's behavior, "
                "not an unversioned current status. freshness_status UNKNOWN does not establish publication, update "
                "or release dates. Do not infer a stable release from a development branch or turn a captured date into an update date.",
                "Every paragraph must cite at least one cell_key and evidence_key.",
                "Do not add sources, numbers, dates, versions, names, or conclusions.",
                "Write a cohesive professional research article, not an audit log.",
                "Never mention frozen cells, ledgers, cell keys or other internal terms in the prose; refer to the "
                "verified sources instead (for example '本次核验的资料显示').",
                "When a cell's quotes (evidence_quotes or supporting_quotes) contain several different facts or "
                "list items for its column, cover each relevant one instead of only candidate_value.",
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
                "Preserve the outcome each quote actually measures. Benefits or null effects in a different "
                "outcome (such as satisfaction, retention or preference when the target is productivity), or in "
                "unspecified outcomes, do not establish the target result. Do not place them in a "
                "supporting/opposing outcome paragraph or table row. A defined "
                "proxy must be identified as a proxy. Keep relevant methodological background separately and "
                "state its limits; do not convert it into evidence for the target result.",
                "executive_summary is the bottom line: one or two sentences that answer research_question with the "
                "verified facts, for example 'X became final in JDK 21 (JEP 444)'; never describe the report "
                "instead of answering, and never copy a sentence that also appears in the sections: the summary "
                "states the overall conclusion, the sections give the supporting details.",
                "Every factual summary sentence must cite its own frozen evidence inline. Unknown citations "
                "or unsupported numbers invalidate the summary; do not use tentative evidence there.",
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
                "evidence_quotes and supporting_quotes are verified source text you may draw details from; a "
                "leading [title] is the source's title. When the question asks for identifiers such as JEP, PEP or RFC numbers or versions, "
                "state them explicitly, taking them from the quote or its title.",
                "evidence_quotes pairs each evidence key with its own quote. After a sentence cite only the keys "
                "whose own quote states that sentence's fact; a sentence that lists several items (types, events, "
                "options, steps) may name only the items that appear in the quotes it cites, and a fact that no "
                "quote states is left out even when candidate_value implies it.",
                "Attribute findings to studies exactly as the quotes do. Name a study's authors and year only when "
                "the quote you cite names them; otherwise write '一项研究' / 'one study' and its setting. Never write "
                "'同一研究' or 'the same study' for a quote that names a different study or comes from another part of "
                "a source; adjacent quotes in one paper or review often describe different studies. When a quote is "
                "a review, meta-analysis or table summarizing another study (for example 'Bloom et al. (2015) - "
                "Empirical study: ...'), say the finding is reported by that review rather than presenting it as "
                "the original study you read.",
                "Do not place events in time order (before or after launch, 上线前, 此后) unless the quotes give the "
                "dates or state the order. When the quotes give no time for an arrangement or a metric, say that "
                "the sources do not state when it happens; never present it as preparation done before an event, "
                "and never conclude that something did not change when the same metric is not reported at both times.",
                "State each fact with exactly the scope its quote supports: a statement about one option, operator "
                "class, version or mode must not be generalized to the whole product or topic.",
                "When known_limitations is not empty, say briefly in the summary which parts of the question remain "
                "unanswered, and copy the relevant items into limitations.",
                "tentative_cells have a real source quote but did not pass automatic verification. evidence_level "
                "SOURCED means there is an answering quote, but the full cell is not verified: it may be a "
                "partial answer from an official source, a secondary source, or an unresolved version match. "
                "Do not call a source third-party just because its cell is tentative. RELATED means the quote is related background that does not directly "
                "answer. When no frozen cell answers a part of the question, write a SOURCED tentative "
                "cell's finding in the body or the comparison table as a plain statement attributed to its source, "
                "citing it like other cells and marking it once with （待核实） (or '(unverified)' in English), for "
                "example '据 RFC 9000 的说明，QUIC 的各条流相互独立（待核实）[evidence:ab12]。'. Do not add sentences "
                "that cast doubt on or deny the tentative finding itself, such as saying it is not an official record "
                "or cannot be confirmed; the marker already tells the reader. "
                "Only describe a source as third-party when the source information establishes that. Use RELATED cells "
                "only as background. Never use tentative cells in executive_summary and never let them contradict "
                "or replace a frozen cell.",
                "required_points are what an expert reviewer checks in a complete answer. In the direct-answer "
                "section give each point an explicit conclusion drawn from the cells, in the point's own terms: for "
                "'whether development bundles code' write whether each subject bundles, not only what each one "
                "does; for a comparison point state the contrast between the subjects in one sentence. Do not number "
                "or quote the points. A point that no frozen or SOURCED tentative cell answers goes to limitations "
                "and must not be guessed.",
                "When research_question asks to mark information the sources do not state, check each required "
                "point against the quotes: for every detail the point asks for (for example the currency of each "
                "budget item, or the number of schools in a later phase) that no quote states, add one "
                "unstated_details item naming that detail in the language of research_question, with the point's "
                "exact text. Never list a detail that any quote states, and never write numbers or dates there. "
                "Leave unstated_details empty when the question does not ask for this.",
                "For every required point not answered by the report, copy its exact text into unanswered_points. "
                "Required points are research questions, not verified facts; never use them as evidence. "
                "Preserve all known limitations even when other parts of the same column are answered.",
                "background_notes are only for required_points that neither frozen_cells nor tentative_cells "
                "answer. For such a point you may write one or two sentences of well-established general technical "
                "knowledge you are certain about, in the language of research_question (for example how many round "
                "trips a TLS 1.3 handshake needs, which workload a database is designed for, what an ownership "
                "system checks), and copy the point's exact text into point. Do not write statistics, study results, "
                "paper titles, authors or years, release dates, or which release introduced a feature, unless you are "
                "certain; leave the point out instead. Background notes never cite evidence keys, never repeat or "
                "contradict the cells, and never appear in executive_summary, the sections or the table; the report "
                "shows them in a separate section marked as general knowledge not verified by this research.",
            ],
            "frozen_cells": _prompt_cells(cells),
            "tentative_cells": _prompt_cells(leads),
            "required_points": required_points,
            "research_question": question,
            "known_limitations": limitations,
        }
        # 模型偶尔返回无法解析或不合契约的 JSON；带上失败原因重试一次，仍失败才退回确定性模板
        failure = ""
        for attempt in range(1, 3):
            payload = request_payload if not failure else {**request_payload, "previous_attempt_error": failure}
            require_execution_active()
            try:
                parsed = parse_json_payload(self.llm_client.complete_json("research.synthesis", payload), allow_truncated=True)
            except Exception:
                require_execution_active()
                failure = "NARRATIVE_PROVIDER_UNAVAILABLE"
                continue
            try:
                narrative = _normalize_narrative(parsed, cells, limitations, question, comparison_required, leads,
                                                 required_points)
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
            "evidence_quotes": _evidence_quotes(raw.get("evidence_quotes"), evidence_keys),
        })
    if not cells:
        raise ValueError("SYNTHESIS input has no frozen cells")
    return sorted(cells, key=lambda item: str(item["cell_key"]))


def _evidence_quotes(raw: object, evidence_keys: list[str]) -> list[dict[str, str]]:
    """按证据编号对应的原文，只保留属于这一格的编号；后端旧版本没有这个字段时为空。"""
    if not isinstance(raw, list):
        return []
    allowed = set(evidence_keys)
    quotes: list[dict[str, str]] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        key = str(item.get("evidence_key") or "").strip()
        quote = str(item.get("quote") or "").strip()
        if key in allowed and quote:
            quotes.append({"evidence_key": key, "quote": quote})
    return quotes


def _prompt_cells(cells: list[dict[str, object]]) -> list[dict[str, object]]:
    """交给模型的单元格：有按编号对应的原文时不再重复给不带编号的原文列表。"""
    return [{key: value for key, value in cell.items()
             if not (key == "supporting_quotes" and cell.get("evidence_quotes"))
             and not (key == "evidence_quotes" and not value)} for cell in cells]


_LEAD_LEVELS = ("SOURCED", "RELATED")
# 成文引用未经核实的线索时句子里要有的说明，没有时由程序补上
_UNVERIFIED_MARKERS = ("未经核实", "待核实", "未核实", "尚未完成核验", "未通过核验", "第三方", "unverified",
                       "third-party", "third party")


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
            "evidence_quotes": _evidence_quotes(raw.get("evidence_quotes"), evidence_keys),
        })
    return sorted(leads, key=lambda item: str(item["cell_key"]))


def _mark_unverified(text: str, lead_keys: set[str], chinese: bool) -> str:
    """引用了线索却没有说明未经核实时，在第一个线索引用前补上说明。"""
    if not any(f"[{key}]" in text for key in lead_keys):
        return text
    if any(marker in text.casefold() for marker in _UNVERIFIED_MARKERS):
        return text
    marker = "（待核实）" if chinese else " (unverified)"
    first = min(text.index(f"[{key}]") for key in lead_keys if f"[{key}]" in text)
    return text[:first].rstrip() + marker + " " + text[first:]


def _normalize_narrative(
    payload: object,
    cells: list[dict[str, object]],
    known_limitations: list[str],
    trusted_context: str = "",
    comparison_required: bool = False,
    leads: list[dict[str, object]] | None = None,
    required_points: list[str] | None = None,
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
    all_cells = list(cells_by_key.values())
    summary_refs = _INLINE_CITATION.findall(summary)
    summary_supported = (len(set(summary_refs)) <= 2 and _cited_text_supported(summary, cells, summary_refs, cells)
                         and not (_TYPED_TOKEN.search(summary) and not summary_refs))
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
            cell_keys = _with_cited_cells(cell_keys, [*_INLINE_CITATION.findall(text), *evidence_keys], cells_by_key)
            if not text or not cell_keys or not (evidence_keys or _INLINE_CITATION.search(text)):
                continue
            bound_cells = [cells_by_key[key] for key in cell_keys]
            allowed_evidence = {
                key for cell in bound_cells for key in cell["evidence_keys"]  # type: ignore[union-attr]
            }
            # 未知引用不能只删除标记后继续保留原句。
            if set(_INLINE_CITATION.findall(text)) - allowed_evidence:
                dropped_unsupported += 1
                continue
            text, inline_keys = _bind_inline_citations(text, allowed_evidence)
            # 不属于本段单元格的引用去掉；一个可用引用都不剩时去掉这一段
            evidence_keys = inline_keys or [key for key in evidence_keys if key in allowed_evidence]
            if not evidence_keys:
                continue
            if not _cited_text_supported(text, bound_cells, evidence_keys, all_cells, trusted_context):
                # 只处理通不过核对的句子（写了来源里没有的数字或版本号，或提到的对象与所引证据不符），同段其余
                # 句子照常保留：这句引用了本段已核验单元格时换成该单元格核验过的结论，否则删掉；
                # 一句可用的都不剩时去掉这一段，全部去掉时才退回模板
                dropped_unsupported += 1
                text = "".join(
                    statement if supported else _verified_restatement(
                        statement, text, bound_cells, cells, all_cells, trusted_context, chinese)
                    for statement, supported in _checked_statements(
                        text, bound_cells, evidence_keys, all_cells, trusted_context)).strip()
                if inline_keys:
                    text, evidence_keys = _bind_inline_citations(text, allowed_evidence)
                if not text or not evidence_keys or not _cited_text_supported(
                        text, bound_cells, evidence_keys, all_cells, trusted_context):
                    continue
            text = _mark_unverified(text, lead_evidence, chinese)
            cell_keys = [key for key in cell_keys if set(cells_by_key[key]["evidence_keys"]) & set(evidence_keys)]
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
    # 不能用自动生成的 claims 清单代替正文覆盖；遗漏的已核验结论补成有据段落。
    covered_cells = {key for section in sections for paragraph in section["paragraphs"] for key in paragraph["cell_keys"]}
    omitted = [cell for cell in cells if cell["cell_key"] not in covered_cells]
    if omitted:
        sections.append({"heading": "其他已核验要点" if chinese else "Additional verified findings", "paragraphs": [
            dict(zip(("text", "evidence_keys"), _omitted_cell_text(cell, cells, chinese)),
                 cell_keys=[cell["cell_key"]]) for cell in omitted]})
    if not summary_supported:
        # 摘要有误时先只删掉通不过核对的句子；一句都不剩才取正文里第一句有据的结论，
        # 那样摘要会与直接回答的第一句重复，读起来像复制。已经逐句校验的正文继续保留。
        summary = _supported_summary(summary, cells) or _verified_summary(sections, cells, chinese)
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
    elif comparison_table:
        # 模型的表漏了已核验的结论时按同样的行列补上，补的格子同样逐句核对，通不过的不补
        comparison_table = _complete_table_coverage(comparison_table, cells, cells_by_key, trusted_context)
    # 已知缺口始终保留；模型只能补充规划里原样存在的未回答要点，不能自行编造局限。
    # 已在通用知识补充里回答的要点不再重复列出：那一节的标题已经说明未经本次检索核验，
    # 规划要点又多是英文，在中文报告的局限里原样列出读起来很突兀。
    limitations = list(dict.fromkeys(known_limitations))
    allowed_points = set(required_points or [])
    background_notes = _background_notes(payload.get("background_notes"), required_points or [], sections,
                                         comparison_table)
    noted_points = {note["point"] for note in background_notes}
    for point in _clean_strings(payload.get("unanswered_points")):
        if point in allowed_points and point not in noted_points:
            limitation = f"尚未找到足够证据回答：{point}" if chinese else f"Insufficient evidence to answer: {point}"
            if limitation not in limitations:
                limitations.append(limitation)
    for missing in _unstated_details(payload.get("unstated_details"), allowed_points):
        limitation = f"资料未说明：{missing}" if chinese else f"Not stated in the sources: {missing}"
        if limitation not in limitations:
            limitations.append(limitation)
    return {
        "schema_version": "research-reader-report.v1",
        "title": title,
        "executive_summary": summary,
        "sections": sections,
        "comparison_table": comparison_table,
        "limitations": limitations,
        "background_notes": background_notes,
    }


_UNSTATED_DETAIL_CHARS = 80


def _unstated_details(raw: object, required_points: set[str]) -> list[str]:
    """资料没有写到的细节：只能挂在规划里原样存在的要点下，只写缺的是哪一项，不能带数字、日期或引用，
    这样标注缺口时不会顺带写出新的事实。"""
    if not isinstance(raw, list):
        return []
    details: list[str] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        point = str(item.get("point") or "").strip()
        missing = " ".join(str(item.get("missing") or "").split()).rstrip("。.；; ")
        if (point not in required_points or not missing or len(missing) > _UNSTATED_DETAIL_CHARS
                or "evidence:" in missing or _typed_tokens(missing) or missing in details):
            continue
        details.append(missing)
    return details


def _complete_table_coverage(table: dict[str, object], cells: list[dict[str, object]],
                             cells_by_key: dict[str, dict[str, object]], trusted_context: str) -> dict[str, object]:
    """表头是 方面 + 各研究对象 时，把表里没有出现的已核验单元格按方面补成行。格子写结论和第一条引用，
    通不过逐句核对的格子不补；表头不是这种排法时不动，避免把结论填进错的列。"""
    columns = [str(item) for item in table.get("columns", [])]  # type: ignore[union-attr]
    entity_columns = {column.strip().casefold(): index for index, column in enumerate(columns) if index > 0}
    covered = {key for row in table.get("rows", []) for key in row.get("cell_keys", [])}  # type: ignore[union-attr]
    missing = [cell for cell in cells if cell["cell_key"] not in covered]
    if not missing or not entity_columns:
        return table
    entities = {str(cell.get("entity_label") or "").strip().casefold() for cell in cells}
    if not entities or not entities.issubset(entity_columns):
        return table
    rows = [{"cells": list(row["cells"]), "cell_keys": list(row["cell_keys"]),
             "evidence_keys": list(row["evidence_keys"])} for row in table.get("rows", [])]  # type: ignore[union-attr]
    all_cells = list(cells_by_key.values())

    def aspect_of(row: dict[str, object]) -> set[str]:
        return {str(cells_by_key[key].get("column_label") or "") for key in row["cell_keys"]  # type: ignore[union-attr]
                if key in cells_by_key}

    def supported_text(cell: dict[str, object], label: str, column: str) -> tuple[str, str] | None:
        for key in [str(item) for item in cell["evidence_keys"]]:  # type: ignore[union-attr]
            text = f"{_first_sentence(str(cell['candidate_value']))} [{key}]"
            if _cited_text_supported(text, [cell], [key], all_cells, trusted_context, label + " " + column):
                return text, key
        return None

    for cell in missing:
        aspect = str(cell.get("column_label") or "")
        index = entity_columns[str(cell.get("entity_label") or "").strip().casefold()]
        # 同一方面已有一行、这一对象的格子空着时填进那一行，否则新起一行
        target = next((row for row in rows if aspect in aspect_of(row) and row["cells"][index] == "-"), None)
        label = str(target["cells"][0]) if target else (aspect or "-")
        found = supported_text(cell, label, columns[index])
        if found is None:
            continue
        text, key = found
        if target is None:
            target = {"cells": [label, *("-" for _ in columns[1:])], "cell_keys": [], "evidence_keys": []}
            rows.append(target)
        target["cells"][index] = text  # type: ignore[index]
        target["cell_keys"].append(str(cell["cell_key"]))  # type: ignore[union-attr]
        target["evidence_keys"].append(key)  # type: ignore[union-attr]
    return {"columns": columns, "rows": rows}


_BACKGROUND_NOTE_CHARS = 300
# 后端校验与前端样式按这两个标题识别通用知识补充一节，改动时要一起改
BACKGROUND_HEADING_ZH = "## 补充说明（通用知识，未经本次检索核验）"
BACKGROUND_HEADING_EN = "## Background notes (general knowledge, not verified by this research)"
BACKGROUND_NOTE_ZH = "以下内容依据公开的通用技术知识补充，本次检索没有找到可以逐句核验的原文，仅供参考。"
BACKGROUND_NOTE_EN = "These notes come from general technical knowledge; this research found no source text to verify them."


def _background_notes(raw: object, required_points: list[str], sections: list[dict[str, object]],
                      comparison_table: object) -> list[dict[str, str]]:
    """通用知识补充：只收规划要点里原样存在的点，每点一条，不带证据引用，不超过 300 字。

    离线对照（fix22 的 40 份报告，同一评分模型空缓存重评）里，两边都评分成功的 24 题加上这一节后
    通过从 10 题到 14 题、要点覆盖多 4 个，事实与引用分持平；人工核对 131 条补写里有 4 到 5 条错误或过时，
    集中在某个版本新增了哪些功能、论文年份这类细节，所以提示词禁止这类内容，报告里单独成节并注明未经核验。
    """
    if not isinstance(raw, list):
        return []
    body = " ".join(str(paragraph["text"]) for section in sections for paragraph in section["paragraphs"])
    notes: list[dict[str, str]] = []
    seen: set[str] = set()
    for item in raw:
        if not isinstance(item, dict):
            continue
        point = str(item.get("point") or "").strip()
        text = " ".join(str(item.get("text") or "").split())
        if (point not in required_points or point in seen or not text or len(text) > _BACKGROUND_NOTE_CHARS
                or _INLINE_CITATION.search(text) or "evidence:" in text or text in body
                or (isinstance(comparison_table, dict) and text in json.dumps(comparison_table, ensure_ascii=False))):
            continue
        seen.add(point)
        notes.append({"point": point, "text": text})
    return notes


def _verified_restatement(statement: str, paragraph: str, bound_cells: list[dict[str, object]],
                          verified: list[dict[str, object]], all_cells: list[dict[str, object]],
                          trusted_context: str, chinese: bool) -> str:
    """通不过核对的句子引用了本段某个已核验单元格时，换成该单元格核验过的结论并引用它的证据。

    段落的主题句常因多写了一个版本号被删，剩下的句子用代词承接，读者和评审都看不出在说什么；
    例如 OIDC 一段删掉主要扩展是 ID Token 这句后，后文的必备声明一句失去了主语，报告里再也没出现 ID Token。
    换上的句子同样逐句核对，通不过、或者本段已经写过这条结论时仍然删掉。线索不做替换。
    """
    cited = set(_INLINE_CITATION.findall(statement))
    verified_keys = {str(cell["cell_key"]) for cell in verified}
    lead = statement[:len(statement) - len(statement.lstrip())]
    for cell in bound_cells:
        keys = [str(key) for key in cell["evidence_keys"]]  # type: ignore[union-attr]
        if str(cell["cell_key"]) not in verified_keys or not cited & set(keys):
            continue
        value = " ".join(str(cell["candidate_value"]).split()).rstrip("。.；;，, ")
        if not value or value in " ".join(paragraph.split()):
            continue
        for key in sorted(keys, key=lambda item: item not in cited):
            candidate = f"{lead}{value} [{key}]{'。' if chinese else '.'}"
            if all(supported for _, supported in _checked_statements(candidate, [cell], [key], all_cells,
                                                                     trusted_context)):
                return candidate
    return ""


def _with_cited_cells(cell_keys: list[str], cited: list[str], cells_by_key: dict[str, dict[str, object]]) -> list[str]:
    """段落或表格行绑定了真实单元格、又引用了其他单元格的证据编号时，把那些单元格补进来。fix17 确认轮
    40 题重放成文，正文漏写的 51 个已核验单元格里有 28 个是整段因此被当成未知引用删掉。编造的编号不属于
    任何单元格，补不进来，仍按未知引用处理。"""
    if not cell_keys:
        return cell_keys
    extended = list(cell_keys)
    for evidence_key in cited:
        extended.extend(key for key, cell in cells_by_key.items()
                        if evidence_key in cell["evidence_keys"] and key not in extended)  # type: ignore[operator]
    return extended


def _omitted_cell_text(cell: dict[str, object], cells: list[dict[str, object]], chinese: bool) -> tuple[str, list[str]]:
    """正文没写到的已核验单元格补成一段，返回文字和这段引用的证据：先用结论；结论里的数字在原文中写法不同、
    通不过逐句核对时改用原文，补出的段落同样要能通过 Java 侧校验，否则整篇报告会被拒收。

    原文按网页排版常带换行和缩进，合成一行；段末只引用这句结论或原文自己的证据，不把整格的证据都堆在后面。
    """
    prefix = f"{cell['entity_label']}：" if cell.get("entity_label") else ""
    suffix = ("（证据仍有分歧）" if chinese else " (evidence still disputed)") if cell["guarded"] else ""
    keys = [str(key) for key in cell["evidence_keys"]]  # type: ignore[union-attr]
    quotes = cell.get("evidence_quotes") or []
    first_key = str(quotes[0]["evidence_key"]) if quotes else keys[0]
    text = prefix + " ".join(str(cell["candidate_value"]).split()) + suffix
    if _cited_text_supported(text, [cell], [first_key], cells):
        return text, [first_key]
    if _cited_text_supported(text, [cell], keys, cells):
        return text, keys
    quote = re.sub(r"^\[[^\]]*\]\s*", "", str(quotes[0]["quote"])) if quotes else str(cell["candidate_value"])
    return prefix + " ".join(quote.split()) + suffix, [first_key] if quotes else keys


def _supported_summary(summary: str, cells: list[dict[str, object]]) -> str:
    """模型摘要里能通过核对的句子按原顺序保留，引用合计不超过两个（与 Java 侧摘要校验一致）。"""
    allowed = {key for cell in cells for key in cell["evidence_keys"]}  # type: ignore[union-attr]
    kept: list[str] = []
    refs: set[str] = set()
    for statement in _STATEMENT.findall(summary):
        statement = statement.strip()
        own = set(_INLINE_CITATION.findall(statement))
        if (not _INLINE_CITATION.sub("", statement).strip(" .。！？!?") or own - allowed or len(refs | own) > 2
                or (_TYPED_TOKEN.search(_INLINE_CITATION.sub("", statement)) and not own)
                or not _cited_text_supported(statement, cells, sorted(own), cells)):
            continue
        kept.append(statement)
        refs |= own
    text = "".join(kept).strip()
    return text if refs else ""


def _verified_summary(sections: list[dict[str, object]], cells: list[dict[str, object]], chinese: bool) -> str:
    for section in sections:
        for paragraph in section["paragraphs"]:
            text = str(paragraph["text"])
            for statement in _STATEMENT.findall(text):
                statement = statement.strip()
                refs = _INLINE_CITATION.findall(statement)
                if (_INLINE_CITATION.sub("", statement).strip(" .。！？!?") and refs and len(set(refs)) <= 2
                        and _cited_text_supported(statement, cells, refs, cells)):
                    return statement
    return "具体核验结论、来源和尚未解决的问题列于下文。" if chinese else "Verified findings, sources and remaining gaps follow."


def _cited_text_supported(text: str, bound_cells: list[dict[str, object]], evidence_keys: list[str],
                          all_cells: list[dict[str, object]], trusted_context: str = "", subject: str = "") -> bool:
    """按句检查引用对应的原文、数字和研究对象，禁止借用其他引用中的数字。"""
    allowed = {key for cell in bound_cells for key in cell["evidence_keys"]}
    if set(_INLINE_CITATION.findall(text)) - allowed or set(evidence_keys) - allowed:
        return False
    return all(supported for _, supported in _checked_statements(
        text, bound_cells, evidence_keys, all_cells, trusted_context, subject))


# 句末之后的引用归于前一句；小数点不作为句号。各句按顺序拼接即为原文
# et al. 里的点号不是句号，否则作者名和年份会被拆到两句里
_STATEMENT = re.compile(r".+?(?:[。！？!?]|(?<!\bet al)\.(?!\d)(?=\s|\[|$)|$)(?:\s*\[evidence:[^\[\]\s]+\])*", re.S)
# 按作者和年份点名一项研究：Bloom 等（2015）、Gibbs et al. (2022)、Bloom and Han (2023)
_AUTHOR_YEAR = re.compile(r"(?<![A-Za-z])([A-Z][A-Za-z'\-]{1,30})(?:\s+(?:et\s+al\.?|and|&)(?:\s+[A-Z][A-Za-z'\-]{1,30})?)?"
                          r"\s*(?:等人?)?\s*[（(]\s*(?:19|20)\d{2}[a-z]?\s*[)）]")
# 指回上一句那项研究的说法
_SAME_STUDY = re.compile(r"同一(?:项)?研究|该研究|这项研究|上述研究|\bthe same study\b|\bthis study\b", re.I)


def _checked_statements(text: str, bound_cells: list[dict[str, object]], evidence_keys: list[str],
                        all_cells: list[dict[str, object]], trusted_context: str = "",
                        subject: str = "") -> list[tuple[str, bool]]:
    """逐句核对，返回每句及其是否通过。段落里有内联引用时每句只用自己引用的原文核对，
    不带引用的句子不能有数字，也不能提到研究对象。"""
    has_inline = bool(_INLINE_CITATION.search(text))
    checked: list[tuple[str, bool]] = []
    previous_refs: set[str] = set()
    previous_sources: str | None = None
    for statement in _STATEMENT.findall(text):
        if not statement.strip():
            checked.append((statement, True))
            continue
        refs = set(_INLINE_CITATION.findall(statement)) if has_inline else set(evidence_keys)
        cited = [cell for cell in bound_cells if set(cell["evidence_keys"]) & refs]
        sources: list[str] = []
        for cell in cited:
            quotes = cell.get("evidence_quotes") or []
            if quotes:
                sources.extend(str(item["quote"]) for item in quotes if item["evidence_key"] in refs)
                sources.append(str(cell.get("entity_label") or ""))
            else:
                sources.extend([trusted_context, _cell_source_text(cell)])
        source_tokens = _typed_tokens(" ".join(sources))
        fact_tokens = _typed_tokens(_INLINE_CITATION.sub("", statement))
        if not fact_tokens.issubset(source_tokens):
            checked.append((statement, False))
            continue
        # 句子提到了研究对象时，所引证据至少要属于其中一个，与 Java 侧校验口径一致；比较句同时提到两边、
        # 只引用其中一边的原文属于正常写法。同名实体的不同版本由上面的数字检查进一步区分。
        prose = subject + " " + _INLINE_CITATION.sub("", statement)
        mentioned = {_entity_stem(str(cell.get("entity_label") or "")) for cell in all_cells
                     if _mentions_entity(prose, str(cell.get("entity_label") or ""))}
        cited_stems = {_entity_stem(str(cell.get("entity_label") or "")) for cell in cited}
        attributed = _attribution_supported(_INLINE_CITATION.sub("", statement), " ".join(sources))
        # 说"同一研究"时要和上一句引用同一条证据，不能把相邻引用的不同研究写成一项
        same_study = not (has_inline and previous_refs and refs and _SAME_STUDY.search(statement)
                          and not refs & previous_refs)
        quote_texts = [str(item["quote"]) if quotes else _cell_source_text(cell)
                       for cell in cited for quotes in [cell.get("evidence_quotes") or []]
                       for item in (quotes if quotes else [{}]) if not quotes or item["evidence_key"] in refs]
        ordered = not has_inline or _sequence_supported(statement, quote_texts, previous_sources)
        checked.append((statement, attributed and same_study and ordered
                        and not (mentioned and not mentioned & cited_stems)))
        if refs:
            previous_refs = refs
            previous_sources = " ".join(sources)
    return checked


# 明确表示先后的说法；只收时间顺序专用的词，"之后""然后"这类在技术说明里常表示条件的词不收
_SEQUENCE = re.compile(r"随后|此后|上线前|上线后|在此之前|\bsubsequently\b|\bafterwards\b|\b(?:before|after|prior to)(?: the)? launch\b",
                       re.I)


def _has_date(tokens: set[str]) -> bool:
    return any(token.startswith("date:") or re.fullmatch(r"(?:19|20)\d{2}", token) for token in tokens)


def _sequence_supported(statement: str, quote_texts: list[str], previous_sources: str | None) -> bool:
    """句子用随后、上线前这类词排先后时，句中引用的每条原文都要写出日期（一句话用逗号连起多件事时，
    没有日期的那件不能被排进先后）；先后词出现在本句第一个引用之前、承接上一句时，上一句的原文也要有日期。
    资料没写时间的安排不能写成发生在某事件之前或之后。"""
    match = _SEQUENCE.search(statement)
    if not match:
        return True
    if not quote_texts or not all(_has_date(_typed_tokens(text)) for text in quote_texts):
        return False
    first_citation = _INLINE_CITATION.search(statement)
    refers_back = first_citation is None or match.start() < first_citation.start()
    return not refers_back or previous_sources is None or _has_date(_typed_tokens(previous_sources))


def _attribution_supported(prose: str, sources: str) -> bool:
    """句子按作者和年份点名研究时，作者姓氏必须出现在所引原文里，不能把一项研究的结论挂到另一项名下。"""
    for match in _AUTHOR_YEAR.finditer(prose):
        surname = match.group(1)
        if not re.search(r"(?<![A-Za-z])" + re.escape(surname) + r"(?![A-Za-z])", sources):
            return False
    return True


def _entity_stem(label: str) -> str:
    return re.sub(r"\s+v?\d[\w. -]*$", "", label.strip(), flags=re.I).casefold()


def _mentions_entity(text: str, label: str) -> bool:
    stem = _entity_stem(label)
    return len(stem) >= 3 and bool(re.search(r"(?<![A-Za-z0-9])" + re.escape(stem) + r"(?![A-Za-z0-9])",
                                           text.casefold()))


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
    if chinese:
        summary = "下文按问题的各个方面列出已核验的结论及其来源。"
        if limitations:
            summary += "部分问题尚未找到可核验的来源，见文末局限。"
    else:
        summary = "This report lists verified findings and their sources by aspect."
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
    entities = list(dict.fromkeys(str(cell.get("entity_label") or "") for cell in [*cells, *(leads or [])]))
    if len(entities) > 5:
        # 对象过多时改用纵向表，遵守列数限制并保留每个对象。
        rows = []
        for cell in [*cells, *(leads or [])]:
            keys = list(cell["evidence_keys"])
            pending = ("（待核实）" if question_chinese else " (unverified)") if cell in (leads or []) else ""
            rows.append({"cells": [str(cell.get("entity_label") or "-"),
                         str(cell.get("column_label") or "") + ": " + _first_sentence(str(cell["candidate_value"]))
                         + pending + " " + " ".join(f"[{key}]" for key in keys)],
                         "cell_keys": [cell["cell_key"]], "evidence_keys": keys})
        return {"columns": ["研究对象", "方面与结论"] if question_chinese else ["Subject", "Aspect and finding"], "rows": rows}
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
    notes = narrative.get("background_notes")
    if isinstance(notes, list) and notes:
        chinese = _writes_chinese(str(narrative["title"]))
        lines.extend([BACKGROUND_HEADING_ZH if chinese else BACKGROUND_HEADING_EN, "",
                      BACKGROUND_NOTE_ZH if chinese else BACKGROUND_NOTE_EN, ""])
        lines.extend(f"- {note['text']}" for note in notes)
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
        cell_keys = _with_cited_cells([key for key in cell_keys if key in cells_by_key],
                                      [*(key for cell in row_cells for key in _INLINE_CITATION.findall(cell)),
                                       *evidence_keys], cells_by_key)
        if not cell_keys:
            continue
        bound_cells = [cells_by_key[key] for key in cell_keys]
        allowed_evidence = {
            key for cell in bound_cells for key in cell["evidence_keys"]  # type: ignore[union-attr]
        }
        inline_keys: list[str] = []
        if any(set(_INLINE_CITATION.findall(cell)) - allowed_evidence for cell in row_cells):
            continue
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
        if any(not _cited_text_supported(cell_text, bound_cells,
                                        _INLINE_CITATION.findall(cell_text) or evidence_keys,
                                        list(cells_by_key.values()), trusted_context,
                                        row_cells[0] + " " + columns[index])
               for index, cell_text in enumerate(row_cells[1:], start=1) if cell_text != "-"):
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
    normalized = re.sub(r"\s+", "", value).lower().replace(",", "")
    return re.sub(r"^v(?=\d)", "", normalized)


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
