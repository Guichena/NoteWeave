"""Deterministic typed-fact checks for claims and their exact citation spans."""

from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation
from typing import Literal

from pydantic import BaseModel, Field


class FactAtom(BaseModel):
    kind: Literal["NUMBER", "DATE", "COMPARISON", "POLARITY"]
    value: str
    unit: str = ""
    comparison: str = ""
    start: int = Field(ge=0)
    end: int = Field(ge=0)


class TypedClaimValidation(BaseModel):
    status: Literal["ENTAILED", "CONTRADICTED", "UNKNOWN", "NOT_APPLICABLE"]
    reason_codes: list[str] = Field(default_factory=list)
    claim_facts: list[FactAtom] = Field(default_factory=list)
    quote_facts: list[FactAtom] = Field(default_factory=list)

    @property
    def has_high_risk_facts(self) -> bool:
        return any(fact.kind in {"NUMBER", "DATE", "COMPARISON"} for fact in self.claim_facts)


_NUMBER_RE = re.compile(
    r"(?<![A-Za-z0-9_.])(?P<number>[+-]?\d{1,3}(?:,\d{3})*(?:\.\d+)?|[+-]?\d+(?:\.\d+)?)"
    r"\s*(?P<unit>%|percent(?:age)?|百分比|个百分点|mb|gb|kb|tb|ms|毫秒|秒|分钟|小时|s\b)?",
    re.IGNORECASE,
)
_DATE_RE = re.compile(
    r"(?<!\d)(?P<year>(?:19|20)\d{2})(?:[-/.年](?P<month>0?[1-9]|1[0-2])"
    r"(?:[-/.月](?P<day>0?[1-9]|[12]\d|3[01])日?)?)?(?!\d)"
)
_COMPARISONS = (
    (re.compile(r"至少|不低于|at\s+least|no\s+less\s+than", re.IGNORECASE), "AT_LEAST"),
    (re.compile(r"至多|不高于|at\s+most|no\s+more\s+than", re.IGNORECASE), "AT_MOST"),
    (re.compile(r"少于|低于|小于|less\s+than|below|lower\s+than", re.IGNORECASE), "LESS_THAN"),
    (re.compile(r"多于|高于|大于|more\s+than|above|higher\s+than", re.IGNORECASE), "GREATER_THAN"),
    (re.compile(r"增加|上升|增长|increase[ds]?|rose|grew", re.IGNORECASE), "INCREASE"),
    (re.compile(r"减少|下降|降低|decrease[ds]?|fell|declined", re.IGNORECASE), "DECREASE"),
    (re.compile(r"之前|以前|before|earlier\s+than", re.IGNORECASE), "BEFORE"),
    (re.compile(r"之后|以后|after|later\s+than", re.IGNORECASE), "AFTER"),
)
_NEGATION_RE = re.compile(
    r"\b(?:not|no|never|without|cannot|can't|doesn't|didn't|isn't|wasn't|aren't|weren't)\b|"
    r"不|未|没有|并非|不能|无法|从未",
    re.IGNORECASE,
)
_UNIT_ALIASES = {
    "%": "PERCENT",
    "percent": "PERCENT",
    "percentage": "PERCENT",
    "百分比": "PERCENT",
    "个百分点": "PERCENT_POINT",
    "kb": "KB",
    "mb": "MB",
    "gb": "GB",
    "tb": "TB",
    "ms": "MS",
    "毫秒": "MS",
    "s": "SECOND",
    "秒": "SECOND",
    "分钟": "MINUTE",
    "小时": "HOUR",
}
_OPPOSITES = {
    "AT_LEAST": {"LESS_THAN"},
    "LESS_THAN": {"AT_LEAST", "GREATER_THAN"},
    "AT_MOST": {"GREATER_THAN"},
    "GREATER_THAN": {"AT_MOST", "LESS_THAN"},
    "INCREASE": {"DECREASE"},
    "DECREASE": {"INCREASE"},
    "BEFORE": {"AFTER"},
    "AFTER": {"BEFORE"},
}


def validate_claim_facts(claim: str, quote: str) -> TypedClaimValidation:
    claim_facts = extract_fact_atoms(claim)
    quote_facts = extract_fact_atoms(quote)
    typed_claim = [fact for fact in claim_facts if fact.kind != "POLARITY"]
    if not typed_claim:
        return TypedClaimValidation(
            status="NOT_APPLICABLE",
            reason_codes=["NO_TYPED_CLAIM_FACTS"],
            claim_facts=claim_facts,
            quote_facts=quote_facts,
        )

    reasons: list[str] = []
    claim_dates = [fact for fact in typed_claim if fact.kind == "DATE"]
    quote_dates = [fact for fact in quote_facts if fact.kind == "DATE"]
    if claim_dates and quote_dates and not _all_values_present(claim_dates, quote_dates):
        reasons.append("DATE_VALUE_CONTRADICTION")

    claim_numbers = [fact for fact in typed_claim if fact.kind == "NUMBER"]
    quote_numbers = [fact for fact in quote_facts if fact.kind == "NUMBER"]
    for fact in claim_numbers:
        same_value = [other for other in quote_numbers if other.value == fact.value]
        if same_value and fact.unit and all(other.unit and other.unit != fact.unit for other in same_value):
            reasons.append("UNIT_CONTRADICTION")
        elif quote_numbers and not any(
            other.value == fact.value and (not fact.unit or fact.unit == other.unit)
            for other in quote_numbers
        ):
            reasons.append("NUMBER_VALUE_CONTRADICTION")

    claim_comparisons = [fact for fact in typed_claim if fact.kind == "COMPARISON"]
    quote_comparisons = [fact for fact in quote_facts if fact.kind == "COMPARISON"]
    for fact in claim_comparisons:
        if any(other.value in _OPPOSITES.get(fact.value, set()) for other in quote_comparisons):
            reasons.append("COMPARISON_DIRECTION_CONTRADICTION")

    for fact in typed_claim:
        matching = [other for other in quote_facts if _same_typed_fact(fact, other)]
        if matching and all(_locally_negated(claim, fact) != _locally_negated(quote, other) for other in matching):
            reasons.append("LOCAL_POLARITY_CONTRADICTION")

    if reasons:
        return TypedClaimValidation(
            status="CONTRADICTED",
            reason_codes=list(dict.fromkeys(reasons)),
            claim_facts=claim_facts,
            quote_facts=quote_facts,
        )

    missing = [fact for fact in typed_claim if not _fact_present(fact, quote_facts)]
    if missing:
        return TypedClaimValidation(
            status="UNKNOWN",
            reason_codes=["TYPED_FACT_MISSING_FROM_CITATION_SPAN"],
            claim_facts=claim_facts,
            quote_facts=quote_facts,
        )

    return TypedClaimValidation(
        status="ENTAILED",
        reason_codes=["TYPED_FACTS_MATCH"],
        claim_facts=claim_facts,
        quote_facts=quote_facts,
    )


def extract_fact_atoms(text: str) -> list[FactAtom]:
    value = str(text or "")
    atoms: list[FactAtom] = []
    date_spans: set[tuple[int, int]] = set()
    for match in _DATE_RE.finditer(value):
        rendered = match.group("year")
        if match.group("month"):
            rendered += f"-{int(match.group('month')):02d}"
        if match.group("day"):
            rendered += f"-{int(match.group('day')):02d}"
        atoms.append(FactAtom(kind="DATE", value=rendered, start=match.start(), end=match.end()))
        date_spans.add((match.start("year"), match.end("year")))
    for match in _NUMBER_RE.finditer(value):
        if (match.start("number"), match.end("number")) in date_spans:
            continue
        normalized_number = _normalize_number(match.group("number"))
        if normalized_number is None:
            continue
        unit = _UNIT_ALIASES.get((match.group("unit") or "").lower(), "")
        atoms.append(FactAtom(
            kind="NUMBER",
            value=normalized_number,
            unit=unit,
            start=match.start(),
            end=match.end(),
        ))
    for pattern, comparison in _COMPARISONS:
        for match in pattern.finditer(value):
            atoms.append(FactAtom(
                kind="COMPARISON",
                value=comparison,
                comparison=comparison,
                start=match.start(),
                end=match.end(),
            ))
    for match in _NEGATION_RE.finditer(value):
        atoms.append(FactAtom(kind="POLARITY", value="NEGATIVE", start=match.start(), end=match.end()))
    return sorted(atoms, key=lambda atom: (atom.start, atom.end, atom.kind, atom.value))


def _normalize_number(raw: str) -> str | None:
    try:
        value = Decimal(raw.replace(",", ""))
    except InvalidOperation:
        return None
    rendered = format(value.normalize(), "f")
    return "0" if rendered in {"-0", ""} else rendered


def _all_values_present(expected: list[FactAtom], actual: list[FactAtom]) -> bool:
    return all(any(item.value == fact.value for item in actual) for fact in expected)


def _fact_present(fact: FactAtom, actual: list[FactAtom]) -> bool:
    return any(
        item.kind == fact.kind
        and item.value == fact.value
        and (fact.kind != "NUMBER" or not fact.unit or item.unit == fact.unit)
        for item in actual
    )


def _same_typed_fact(expected: FactAtom, actual: FactAtom) -> bool:
    return (
        expected.kind == actual.kind
        and expected.value == actual.value
        and (expected.kind != "NUMBER" or not expected.unit or expected.unit == actual.unit)
    )


def _locally_negated(text: str, fact: FactAtom) -> bool:
    # Scope negation to the fact's nearby clause; a negation elsewhere in the
    # citation span must not flip every fact in the paragraph.
    start = max(0, fact.start - 40)
    end = min(len(text), fact.end + 12)
    return _NEGATION_RE.search(text[start:end]) is not None
