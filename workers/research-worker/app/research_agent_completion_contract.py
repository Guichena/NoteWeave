"""Strict MA4G atomic completion contract and cross-runtime canonical digest.

The Worker may propose evidence and candidates, but this envelope grants no
authority to merge cells or settle a reservation.  It is immutable execution
input for the Backend's single atomic completion transaction.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import unicodedata
from collections.abc import Mapping
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from app.extraction_result import ExtractionFailureReason, ExtractionTerminationReason
from app.unicode_contract import (
    UNICODE_WHITE_SPACE_CODEPOINTS,
    has_unicode_boundary_whitespace,
    is_unicode_blank,
)


_DIGEST_PATTERN = r"^sha256:[0-9a-f]{64}$"
_STABLE_KEY_PATTERN = r"^[a-z0-9][a-z0-9._:-]*$"
_TASK_ID_PATTERN = r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
_MAX_COUNTER = 1_000_000
_MAX_ENVELOPE_BYTES = 256 * 1024
_MISSING = object()
_STABLE_KEY_RE = re.compile(_STABLE_KEY_PATTERN)
_DIGEST_RE = re.compile(_DIGEST_PATTERN)

# DR-102: fixed vocabulary mirrored from app.extraction_result; the enums are
# reused verbatim so a Python-side rename can never silently widen the contract.
_EXTRACTION_DIAGNOSTICS_SCHEMA = "research-extraction-diagnostics.v1"
_EXTRACTION_ACCEPTED_REASON = ExtractionTerminationReason.ACCEPTED_CARDS.value
_EXTRACTION_TERMINATION_REASONS = frozenset(item.value for item in ExtractionTerminationReason)
_EXTRACTION_FAILURE_REASONS = frozenset(item.value for item in ExtractionFailureReason)
_MAX_EXTRACTION_DIAGNOSTICS_BYTES = 8_192
_MAX_EXTRACTION_MODEL_CHARS = 128
_MAX_EXTRACTION_WINDOW_CHARS = 64
_MAX_EXTRACTION_COLUMN_CHARS = 64
_MAX_EXTRACTION_DETAIL_CHARS = 240
_MAX_EXTRACTION_RESPONSE_CHARS = 100_000_000
_MAX_REJECTION_SAMPLES = 8


def _normalize_unicode(value: object) -> object:
    """Normalize every JSON string/key to NFC and reject NFC key collisions."""
    if isinstance(value, str):
        normalized = unicodedata.normalize("NFC", value)
        if any(0xD800 <= ord(character) <= 0xDFFF for character in normalized):
            raise ValueError("Unicode surrogate code points are not valid completion text")
        return normalized
    if isinstance(value, list):
        return [_normalize_unicode(item) for item in value]
    if isinstance(value, tuple):
        return tuple(_normalize_unicode(item) for item in value)
    if isinstance(value, dict):
        normalized: dict[object, object] = {}
        for key, item in value.items():
            normalized_key = _normalize_unicode(key)
            if normalized_key in normalized:
                raise ValueError(f"duplicate JSON object key after NFC normalization: {normalized_key!r}")
            normalized[normalized_key] = _normalize_unicode(item)
        return normalized
    return value


class _FrozenStrictContract(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    @field_validator("*", mode="before")
    @classmethod
    def normalize_unicode(cls, value: object) -> object:
        return _normalize_unicode(value)


def _require_identifier(value: str) -> str:
    if has_unicode_boundary_whitespace(value):
        raise ValueError("identifier must not have Unicode White_Space at its boundary")
    if any(ord(character) < 0x20 or ord(character) == 0x7F for character in value):
        raise ValueError("identifier must not contain control characters")
    return value


def require_worker_instance_id(value: str) -> str:
    """Fail before claim/provider work when the configured Worker id is noncanonical."""
    if (
        not isinstance(value, str)
        or len(value) > 128
        or unicodedata.normalize("NFC", value) != value
        or _STABLE_KEY_RE.fullmatch(value) is None
    ):
        raise ValueError("worker_instance_id must use canonical lowercase ASCII stable-key syntax")
    return value


def _require_nonblank_text(value: str) -> str:
    if is_unicode_blank(value):
        raise ValueError("text must not be blank")
    return value


def _require_unpadded(value: str) -> str:
    """Diagnostics text must be NFC (applied globally) and free of boundary whitespace."""
    if has_unicode_boundary_whitespace(value):
        raise ValueError("diagnostics text must not have Unicode White_Space at its boundary")
    return value


class ResearchAgentBudgetUsage(_FrozenStrictContract):
    """Worker-observable usage only; merge/release amounts remain server-owned."""

    llm_calls: int = Field(ge=0, le=_MAX_COUNTER)
    search_calls: int = Field(ge=0, le=_MAX_COUNTER)
    fetch_calls: int = Field(ge=0, le=_MAX_COUNTER)
    read_calls: int = Field(ge=0, le=_MAX_COUNTER)
    extract_calls: int = Field(ge=0, le=_MAX_COUNTER)
    evidence_cards: int = Field(ge=0, le=_MAX_COUNTER)
    candidates_submitted: int = Field(ge=0, le=_MAX_COUNTER)

    @model_validator(mode="before")
    @classmethod
    def explicit_null_is_not_a_counter(cls, value: object) -> object:
        if isinstance(value, Mapping) and value.get("llm_calls") is None:
            raise ValueError("budget_usage.llm_calls must be an explicit integer")
        return value


class ResearchAgentCompletionTelemetry(_FrozenStrictContract):
    search_hits: int = Field(ge=0, le=_MAX_COUNTER)
    documents: int = Field(ge=0, le=_MAX_COUNTER)
    windows: int = Field(ge=0, le=_MAX_COUNTER)


class ResearchAgentExtractionProviderReceipt(_FrozenStrictContract):
    """Provider call receipt: identity, counts and response digest, never the raw text."""

    purpose: Literal["research.extract"]
    transport: Literal["openai-compatible"]
    model: str = Field(max_length=_MAX_EXTRACTION_MODEL_CHARS)
    call_count: int = Field(ge=0, le=_MAX_COUNTER)
    response_digest: str
    response_chars: int = Field(ge=0, le=_MAX_EXTRACTION_RESPONSE_CHARS)

    @field_validator("model")
    @classmethod
    def model_is_unpadded(cls, value: str) -> str:
        return _require_unpadded(value)

    @field_validator("response_digest")
    @classmethod
    def digest_is_absent_or_canonical(cls, value: str) -> str:
        if value and _DIGEST_RE.fullmatch(value) is None:
            raise ValueError("provider response_digest must be empty or a canonical sha256 digest")
        return value


class ResearchAgentRejectionSample(_FrozenStrictContract):
    """One bounded rejection witness; only locating fields, never model-authored text."""

    reason: str
    window_id: str = Field(max_length=_MAX_EXTRACTION_WINDOW_CHARS)
    column_key: str = Field(max_length=_MAX_EXTRACTION_COLUMN_CHARS)
    detail: str = Field(max_length=_MAX_EXTRACTION_DETAIL_CHARS)

    @field_validator("reason")
    @classmethod
    def reason_is_known(cls, value: str) -> str:
        if value not in _EXTRACTION_FAILURE_REASONS:
            raise ValueError("rejection sample reason is not an ExtractionFailureReason")
        return value

    @field_validator("window_id", "column_key", "detail")
    @classmethod
    def sample_text_is_unpadded(cls, value: str) -> str:
        return _require_unpadded(value)


class ResearchAgentExtractionDiagnostics(_FrozenStrictContract):
    """DR-102 explainable extraction outcome: why zero cards were accepted."""

    schema_version: Literal["research-extraction-diagnostics.v1"]
    termination_reason: str
    accepted_count: int = Field(ge=0, le=_MAX_COUNTER)
    rejected_count: int = Field(ge=0, le=_MAX_COUNTER)
    rejection_counts: dict[str, int]
    provider_receipt: ResearchAgentExtractionProviderReceipt
    rejection_samples: tuple[ResearchAgentRejectionSample, ...] = Field(
        default_factory=tuple, max_length=_MAX_REJECTION_SAMPLES
    )

    @field_validator("termination_reason")
    @classmethod
    def termination_reason_is_known(cls, value: str) -> str:
        if value not in _EXTRACTION_TERMINATION_REASONS:
            raise ValueError("extraction termination_reason is not an ExtractionTerminationReason")
        return value

    @field_validator("rejection_counts")
    @classmethod
    def rejection_counts_are_known(cls, value: dict[str, int]) -> dict[str, int]:
        for reason, count in value.items():
            if reason not in _EXTRACTION_FAILURE_REASONS:
                raise ValueError(f"rejection_counts key is not an ExtractionFailureReason: {reason!r}")
            if isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= _MAX_COUNTER:
                raise ValueError(f"rejection_counts value must be a 1..{_MAX_COUNTER} integer: {reason!r}")
        return value

    @field_validator("rejection_samples", mode="before")
    @classmethod
    def freeze_rejection_samples(cls, value: object) -> object:
        return tuple(value) if isinstance(value, list) else value

    @model_validator(mode="after")
    def validate_diagnostics(self) -> "ResearchAgentExtractionDiagnostics":
        if sum(self.rejection_counts.values()) != self.rejected_count:
            raise ValueError("extraction rejection_counts must sum to rejected_count")
        if self.accepted_count == 0 and self.termination_reason == _EXTRACTION_ACCEPTED_REASON:
            raise ValueError("zero accepted cards cannot terminate as ACCEPTED_CARDS")
        if self.accepted_count >= 1 and self.termination_reason != _EXTRACTION_ACCEPTED_REASON:
            raise ValueError("accepted cards must terminate as ACCEPTED_CARDS")
        if len(self.rejection_samples) > self.rejected_count:
            raise ValueError("extraction rejection_samples must not exceed rejected_count")
        return self


class ResearchAgentCompletionEvidence(_FrozenStrictContract):
    evidence_key: str = Field(min_length=1, max_length=64, pattern=_STABLE_KEY_PATTERN)
    window_id: str = Field(min_length=1, max_length=64)
    source_id: str = Field(min_length=1, max_length=64)
    source_title: str = Field(min_length=1, max_length=300)
    search_query: str = Field(min_length=1, max_length=4096)
    read_focus: str = Field(min_length=1, max_length=4096)
    quote_text: str = Field(min_length=1, max_length=16_384)
    claim_text: str = Field(min_length=1, max_length=16_384)
    relation_type: Literal["SUPPORTS", "WEAK_SUPPORT", "CONFLICTS"]
    support_score_ppm: int = Field(ge=0, le=1_000_000)
    conflict_score_ppm: int = Field(ge=0, le=1_000_000)
    # EXTERNAL_ARCHIVED never trusts a Worker supplied URL or snapshot key: the
    # Backend resolves it by (task, window, source) from its immutable archive.
    snapshot_status: Literal["WORKSPACE", "EXTERNAL_ARCHIVED"]

    @field_validator("evidence_key", "window_id", "source_id")
    @classmethod
    def identifiers_are_canonical(cls, value: str) -> str:
        return _require_identifier(value)

    @field_validator("source_title", "search_query", "read_focus", "quote_text", "claim_text")
    @classmethod
    def text_is_not_blank(cls, value: str) -> str:
        # Preserve leading/trailing whitespace because exact quotes are content.
        return _require_nonblank_text(value)


class ResearchAgentCompletionCandidate(_FrozenStrictContract):
    candidate_key: str = Field(min_length=1, max_length=160, pattern=_STABLE_KEY_PATTERN)
    cell_key: str = Field(min_length=1, max_length=160)
    base_cell_version: int = Field(ge=0, le=2_147_483_646)
    candidate_value: str = Field(min_length=1, max_length=16_384)
    evidence_keys: tuple[str, ...] = Field(min_length=1, max_length=12)
    confidence_ppm: int = Field(ge=0, le=1_000_000)

    @field_validator("candidate_key", "cell_key")
    @classmethod
    def identifiers_are_canonical(cls, value: str) -> str:
        return _require_identifier(value)

    @field_validator("candidate_value")
    @classmethod
    def value_is_not_blank(cls, value: str) -> str:
        return _require_nonblank_text(value)

    @field_validator("evidence_keys")
    @classmethod
    def evidence_key_list_is_canonical(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        for item in value:
            _require_identifier(item)
        if len(set(value)) != len(value):
            raise ValueError("candidate evidence_keys must be unique")
        return value

    @field_validator("evidence_keys", mode="before")
    @classmethod
    def freeze_evidence_keys(cls, value: object) -> object:
        return tuple(value) if isinstance(value, list) else value


class _ResearchAgentCompletionContent(_FrozenStrictContract):
    schema_version: Literal["research-agent-completion.v1", "research-agent-completion.v2"]
    task_id: str = Field(min_length=36, max_length=36, pattern=_TASK_ID_PATTERN)
    worker_instance_id: str = Field(min_length=1, max_length=128, pattern=_STABLE_KEY_PATTERN)
    lease_epoch: int = Field(ge=1, le=2_147_483_647)
    fencing_token: int = Field(ge=1, le=9_223_372_036_854_775_807)
    execution_key: str = Field(min_length=1, max_length=160, pattern=_STABLE_KEY_PATTERN)
    task_snapshot_digest: str = Field(pattern=_DIGEST_PATTERN)
    termination_reason: Literal["CANDIDATES_PROPOSED", "EVIDENCE_ONLY", "NO_SUPPORTED_CANDIDATE", "ROLE_RESULT"]
    budget_usage: ResearchAgentBudgetUsage
    telemetry: ResearchAgentCompletionTelemetry
    trace_digest: str = Field(pattern=_DIGEST_PATTERN)
    evidence: tuple[ResearchAgentCompletionEvidence, ...] = Field(default_factory=tuple, max_length=12)
    candidates: tuple[ResearchAgentCompletionCandidate, ...] = Field(default_factory=tuple, max_length=3)
    role_result: dict[str, object] | None = None
    extraction_diagnostics: ResearchAgentExtractionDiagnostics | None = None

    @field_validator("task_id", "worker_instance_id", "execution_key", "task_snapshot_digest", "trace_digest")
    @classmethod
    def identifiers_are_canonical(cls, value: str) -> str:
        return _require_identifier(value)

    @field_validator("evidence", "candidates", mode="before")
    @classmethod
    def freeze_identity_lists(cls, value: object) -> object:
        return tuple(value) if isinstance(value, list) else value

    @model_validator(mode="after")
    def validate_aggregate(self) -> "_ResearchAgentCompletionContent":
        evidence_keys = [item.evidence_key for item in self.evidence]
        if len(set(evidence_keys)) != len(evidence_keys):
            raise ValueError("completion evidence_key values must be unique")
        candidate_keys = [item.candidate_key for item in self.candidates]
        if len(set(candidate_keys)) != len(candidate_keys):
            raise ValueError("completion candidate_key values must be unique")
        candidate_cells = [item.cell_key for item in self.candidates]
        if len(set(candidate_cells)) != len(candidate_cells):
            raise ValueError("completion candidate cell_key values must be unique")
        evidence_key_set = set(evidence_keys)
        for candidate in self.candidates:
            if not set(candidate.evidence_keys).issubset(evidence_key_set):
                raise ValueError("candidate evidence_keys must reference evidence in the same completion")
        if self.termination_reason == "CANDIDATES_PROPOSED" and (not self.candidates or not self.evidence):
            raise ValueError("CANDIDATES_PROPOSED requires evidence and candidates")
        if self.termination_reason == "EVIDENCE_ONLY" and (not self.evidence or self.candidates):
            raise ValueError("EVIDENCE_ONLY requires evidence and no candidates")
        if self.termination_reason == "NO_SUPPORTED_CANDIDATE" and (self.evidence or self.candidates):
            raise ValueError("NO_SUPPORTED_CANDIDATE must not contain evidence or candidates")
        if self.schema_version == "research-agent-completion.v1":
            if self.role_result is not None:
                raise ValueError("v1 completion cannot contain role_result")
            if self.extraction_diagnostics is not None:
                raise ValueError("v1 completion cannot contain extraction_diagnostics")
        if self.termination_reason == "ROLE_RESULT":
            if self.schema_version != "research-agent-completion.v2" or self.role_result is None:
                raise ValueError("ROLE_RESULT requires v2 completion role_result")
            if self.evidence or self.candidates:
                raise ValueError("ROLE_RESULT must not contain evidence or candidates")
            if self.extraction_diagnostics is not None:
                raise ValueError("ROLE_RESULT must not contain extraction_diagnostics")
        else:
            if self.role_result is not None:
                raise ValueError("role_result requires ROLE_RESULT termination")
            if (
                self.schema_version == "research-agent-completion.v2"
                and self.extraction_diagnostics is None
            ):
                raise ValueError("v2 non-ROLE_RESULT completion requires extraction_diagnostics")
        if self.extraction_diagnostics is not None:
            diagnostics_bytes = _compact_json_bytes(
                self.extraction_diagnostics.model_dump(mode="json", exclude_none=True)
            )
            if len(diagnostics_bytes) > _MAX_EXTRACTION_DIAGNOSTICS_BYTES:
                raise ValueError("extraction diagnostics exceed the maximum canonical payload size")
        if self.budget_usage.evidence_cards != len(self.evidence):
            raise ValueError("budget_usage.evidence_cards must equal envelope evidence count")
        if self.budget_usage.candidates_submitted != len(self.candidates):
            raise ValueError("budget_usage.candidates_submitted must equal envelope candidate count")
        unsigned = self.model_dump(mode="json", exclude_none=True)
        if len(_compact_json_bytes(unsigned)) > _MAX_ENVELOPE_BYTES:
            raise ValueError("completion envelope exceeds the maximum canonical payload size")
        return self


class ResearchAgentCompletionEnvelope(_ResearchAgentCompletionContent):
    envelope_digest: str = Field(pattern=_DIGEST_PATTERN)

    @model_validator(mode="after")
    def digest_must_match_content(self) -> "ResearchAgentCompletionEnvelope":
        expected = compute_envelope_digest(self)
        if not hmac.compare_digest(self.envelope_digest, expected):
            raise ValueError("completion envelope_digest does not match canonical content")
        if len(canonical_envelope_bytes(self, include_envelope_digest=True)) > _MAX_ENVELOPE_BYTES:
            raise ValueError("completion full serialized payload size exceeds 256 KiB")
        return self


def _canonical_payload(value: ResearchAgentCompletionEnvelope | _ResearchAgentCompletionContent) -> dict[str, object]:
    payload = value.model_dump(mode="json", exclude_none=True)
    payload.pop("envelope_digest", None)
    payload["evidence"] = sorted(payload["evidence"], key=lambda item: item["evidence_key"])  # type: ignore[index,return-value]
    candidates = sorted(payload["candidates"], key=lambda item: item["candidate_key"])  # type: ignore[index,return-value]
    for candidate in candidates:
        candidate["evidence_keys"] = sorted(candidate["evidence_keys"])  # type: ignore[index]
    payload["candidates"] = candidates
    return _normalize_unicode(payload)  # type: ignore[return-value]


def _compact_json_bytes(payload: object) -> bytes:
    return json.dumps(
        _normalize_unicode(payload),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def canonical_envelope_bytes(
    envelope: ResearchAgentCompletionEnvelope | _ResearchAgentCompletionContent,
    *,
    include_envelope_digest: bool,
) -> bytes:
    """Serialize an envelope with stable object and identity-list ordering."""
    payload = _canonical_payload(envelope)
    if include_envelope_digest:
        if not isinstance(envelope, ResearchAgentCompletionEnvelope):
            raise TypeError("only a validated completion envelope has an envelope_digest")
        payload["envelope_digest"] = envelope.envelope_digest
    return _compact_json_bytes(payload)


def compute_envelope_digest(
    envelope: ResearchAgentCompletionEnvelope | _ResearchAgentCompletionContent,
) -> str:
    canonical = canonical_envelope_bytes(envelope, include_envelope_digest=False)
    return "sha256:" + hashlib.sha256(canonical).hexdigest()


def canonical_json_digest(payload: object) -> str:
    """Digest a bounded trace-like JSON value with NFC/compact/key-sort rules."""
    return "sha256:" + hashlib.sha256(_compact_json_bytes(payload)).hexdigest()


def canonical_json_bytes(payload: object) -> bytes:
    """Canonicalize a generic JSON value without envelope identity-list sorting."""
    return _compact_json_bytes(payload)


def domain_separated_json_digest(domain: str, payload: object) -> str:
    """Digest canonical JSON under an explicit lowercase ASCII protocol domain."""
    if not isinstance(domain, str) or not domain or not all(
        character.isascii() and (character.islower() or character.isdigit() or character in "._:-")
        for character in domain
    ):
        raise ValueError("canonical digest domain must use lowercase ASCII stable-key syntax")
    return "sha256:" + hashlib.sha256(domain.encode("ascii") + b"\n" + _compact_json_bytes(payload)).hexdigest()


def build_completion_envelope(payload: Mapping[str, Any]) -> ResearchAgentCompletionEnvelope:
    """Validate content, normalize it once, and bind its immutable digest."""
    raw = dict(payload)
    supplied_digest = raw.pop("envelope_digest", _MISSING)
    content = _ResearchAgentCompletionContent.model_validate(raw)
    expected_digest = compute_envelope_digest(content)
    if supplied_digest is not _MISSING:
        if not isinstance(supplied_digest, str) or not hmac.compare_digest(supplied_digest, expected_digest):
            raise ValueError("completion envelope_digest does not match canonical content")
    return ResearchAgentCompletionEnvelope.model_validate(
        {**content.model_dump(mode="json", exclude_none=True), "envelope_digest": expected_digest}
    )


def serialize_completion_envelope(envelope: ResearchAgentCompletionEnvelope) -> bytes:
    """Return the exact compact request bytes used for every /complete retry."""
    # Revalidate to prevent callers from bypassing the digest check through an
    # unvalidated model_copy(update=...) operation.
    validated = ResearchAgentCompletionEnvelope.model_validate(envelope.model_dump(mode="json", exclude_none=True))
    serialized = canonical_envelope_bytes(validated, include_envelope_digest=True)
    if len(serialized) > _MAX_ENVELOPE_BYTES:
        raise ValueError("completion full serialized payload size exceeds 256 KiB")
    return serialized


def _reject_duplicate_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        normalized_key = unicodedata.normalize("NFC", key)
        if normalized_key in result:
            raise ValueError(f"duplicate JSON object key: {normalized_key}")
        result[normalized_key] = value
    return result


def parse_completion_envelope_json(raw: str | bytes) -> ResearchAgentCompletionEnvelope:
    """Parse untrusted JSON without allowing duplicate object keys to collapse."""
    if isinstance(raw, bytes):
        if raw.startswith(b"\xef\xbb\xbf"):
            raise ValueError("completion envelope JSON must not contain a UTF-8 BOM")
        text = raw.decode("utf-8", errors="strict")
    else:
        text = raw
    if text.startswith("\ufeff"):
        raise ValueError("completion envelope JSON must not contain a text BOM")
    parsed = json.loads(text, object_pairs_hook=_reject_duplicate_pairs)
    if not isinstance(parsed, dict):
        raise ValueError("completion envelope JSON must contain one object")
    return ResearchAgentCompletionEnvelope.model_validate(parsed)
