from __future__ import annotations

import copy
import json

import pytest
from pydantic import ValidationError


def _payload() -> dict[str, object]:
    return {
        "schema_version": "research-agent-completion.v1",
        "task_id": "00000000-0000-0000-0000-000000000001",
        "worker_instance_id": "worker-a",
        "lease_epoch": 2,
        "fencing_token": 7,
        "execution_key": "deep-cell:00000000-0000-0000-0000-000000000001:2:7",
        "task_snapshot_digest": "sha256:" + "1" * 64,
        "termination_reason": "CANDIDATES_PROPOSED",
        "budget_usage": {
            "llm_calls": 0,
            "search_calls": 1,
            "fetch_calls": 1,
            "read_calls": 1,
            "extract_calls": 1,
            "evidence_cards": 2,
            "candidates_submitted": 2,
        },
        "telemetry": {"search_hits": 2, "documents": 1, "windows": 2},
        "trace_digest": "sha256:" + "2" * 64,
        "evidence": [
            {
                "evidence_key": "evidence-b",
                "window_id": "window-b",
                "source_id": "source-b",
                "source_title": "乙：引号 \" 与反斜杠 \\",
                "search_query": "怎么验证？\n第二行",
                "read_focus": "方法 / 结果",
                "quote_text": "原文 B",
                "claim_text": "结论 B",
                "relation_type": "SUPPORTS",
                "support_score_ppm": 800_000,
                "conflict_score_ppm": 0,
                "snapshot_status": "WORKSPACE",
            },
            {
                "evidence_key": "evidence-a",
                "window_id": "window-a",
                "source_id": "source-a",
                "source_title": "甲",
                "search_query": "怎么验证？",
                "read_focus": "方法",
                "quote_text": "原文 A",
                "claim_text": "结论 A",
                "relation_type": "SUPPORTS",
                "support_score_ppm": 900_000,
                "conflict_score_ppm": 0,
                "snapshot_status": "WORKSPACE",
            },
        ],
        "candidates": [
            {
                "candidate_key": "candidate-b",
                "cell_key": "entity-1:result",
                "base_cell_version": 0,
                "candidate_value": "结论 B",
                "evidence_keys": ["evidence-b", "evidence-a"],
                "confidence_ppm": 800_000,
            },
            {
                "candidate_key": "candidate-a",
                "cell_key": "entity-1:method",
                "base_cell_version": 3,
                "candidate_value": "结论 A",
                "evidence_keys": ["evidence-a"],
                "confidence_ppm": 900_000,
            },
        ],
    }


def _build(payload: dict[str, object]):
    from app.research_agent_completion_contract import build_completion_envelope

    return build_completion_envelope(payload)


def test_completion_digest_should_ignore_map_and_identity_list_input_order() -> None:
    from app.research_agent_completion_contract import canonical_envelope_bytes

    first_payload = _payload()
    second_payload = copy.deepcopy(first_payload)
    second_payload["budget_usage"] = dict(reversed(list(first_payload["budget_usage"].items())))  # type: ignore[union-attr]
    second_payload["telemetry"] = dict(reversed(list(first_payload["telemetry"].items())))  # type: ignore[union-attr]
    second_payload["evidence"] = list(reversed(first_payload["evidence"]))  # type: ignore[arg-type]
    second_payload["candidates"] = list(reversed(first_payload["candidates"]))  # type: ignore[arg-type]
    second_payload["candidates"][0]["evidence_keys"] = list(  # type: ignore[index]
        reversed(second_payload["candidates"][0]["evidence_keys"])  # type: ignore[index]
    )

    first = _build(first_payload)
    second = _build(second_payload)

    assert first.envelope_digest == second.envelope_digest
    assert canonical_envelope_bytes(first, include_envelope_digest=False) == canonical_envelope_bytes(
        second, include_envelope_digest=False
    )


def test_completion_digest_golden_vector_should_use_nfc_compact_utf8_and_json_escaping() -> None:
    from app.research_agent_completion_contract import canonical_envelope_bytes

    payload = _payload()
    payload["evidence"] = [payload["evidence"][0]]  # type: ignore[index]
    payload["candidates"] = [
        {
            "candidate_key": "candidate-b",
            "cell_key": "entity-1:result",
            "base_cell_version": 0,
            "candidate_value": "Cafe\u0301 / 结论 \"B\" \\",
            "evidence_keys": ["evidence-b"],
            "confidence_ppm": 800_000,
        }
    ]
    payload["budget_usage"] = {
        "llm_calls": 0,
        "search_calls": 1,
        "fetch_calls": 1,
        "read_calls": 1,
        "extract_calls": 1,
        "evidence_cards": 1,
        "candidates_submitted": 1,
    }
    envelope = _build(payload)
    canonical = canonical_envelope_bytes(envelope, include_envelope_digest=False)

    assert b'": ' not in canonical and b", " not in canonical
    assert "Caf\u00e9 / 结论" in canonical.decode("utf-8")
    assert b"\\n" in canonical and b'\\"B\\"' in canonical and b"\\\\" in canonical
    # This is a cross-runtime golden value, not a digest recomputed by the test.
    assert envelope.envelope_digest == "sha256:e45e94f3afc622fcddbba9794c481ace8b9003d5aa97e7b637b6593b2a5abeda"


@pytest.mark.parametrize(
    ("field", "mutation"),
    [
        ("task_id", lambda _value: "00000000-0000-0000-0000-000000000002"),
        ("task_snapshot_digest", lambda _value: "sha256:" + "3" * 64),
        ("trace_digest", lambda _value: "sha256:" + "4" * 64),
        ("budget_usage", lambda value: {**value, "search_calls": 2}),
        ("telemetry", lambda value: {**value, "windows": 3}),
        ("evidence", lambda value: [{**value[0], "quote_text": "different quote"}, *value[1:]]),
        (
            "candidates",
            lambda value: [{**value[0], "evidence_keys": ["evidence-a"]}, *value[1:]],
        ),
    ],
)
def test_completion_digest_should_change_when_any_semantic_field_changes(field, mutation) -> None:
    original_payload = _payload()
    changed_payload = copy.deepcopy(original_payload)
    changed_payload[field] = mutation(changed_payload[field])

    assert _build(original_payload).envelope_digest != _build(changed_payload).envelope_digest


def test_completion_contract_should_be_frozen_strict_and_reject_unknown_or_ambiguous_values() -> None:
    envelope = _build(_payload())
    with pytest.raises(ValidationError):
        envelope.task_id = "different"  # type: ignore[misc]

    unknown = _payload()
    unknown["future_field"] = True
    with pytest.raises(ValidationError, match="future_field"):
        _build(unknown)

    float_ppm = _payload()
    float_ppm["evidence"][0]["support_score_ppm"] = 0.9  # type: ignore[index]
    with pytest.raises(ValidationError):
        _build(float_ppm)

    boolean_usage = _payload()
    boolean_usage["budget_usage"]["search_calls"] = True  # type: ignore[index]
    with pytest.raises(ValidationError):
        _build(boolean_usage)

    negative_telemetry = _payload()
    negative_telemetry["telemetry"]["documents"] = -1  # type: ignore[index]
    with pytest.raises(ValidationError):
        _build(negative_telemetry)


def test_completion_contract_should_reject_duplicate_or_cross_envelope_references() -> None:
    duplicate_evidence = _payload()
    duplicate_evidence["evidence"][1]["evidence_key"] = "evidence-b"  # type: ignore[index]
    with pytest.raises(ValidationError, match="evidence_key"):
        _build(duplicate_evidence)

    duplicate_cell = _payload()
    duplicate_cell["candidates"][1]["cell_key"] = "entity-1:result"  # type: ignore[index]
    with pytest.raises(ValidationError, match="cell_key"):
        _build(duplicate_cell)

    outside_reference = _payload()
    outside_reference["candidates"][0]["evidence_keys"] = ["not-in-envelope"]  # type: ignore[index]
    with pytest.raises(ValidationError, match="evidence_keys"):
        _build(outside_reference)


def test_completion_termination_should_distinguish_evidence_only_from_no_result() -> None:
    evidence_only = _payload()
    evidence_only["termination_reason"] = "EVIDENCE_ONLY"
    evidence_only["candidates"] = []
    evidence_only["budget_usage"]["candidates_submitted"] = 0  # type: ignore[index]
    assert _build(evidence_only).termination_reason == "EVIDENCE_ONLY"

    ambiguous_no_result = copy.deepcopy(evidence_only)
    ambiguous_no_result["termination_reason"] = "NO_SUPPORTED_CANDIDATE"
    with pytest.raises(ValidationError, match="must not contain evidence"):
        _build(ambiguous_no_result)


def test_completion_should_preserve_body_whitespace_but_reject_identifier_whitespace() -> None:
    from app.research_agent_completion_contract import canonical_envelope_bytes

    original = _build(_payload())
    spaced = _payload()
    spaced["evidence"][0]["quote_text"] = "  原文 B  "  # type: ignore[index]
    spaced["candidates"][0]["candidate_value"] = "  结论 B  "  # type: ignore[index]
    spaced_envelope = _build(spaced)

    assert spaced_envelope.evidence[0].quote_text == "  原文 B  "
    assert spaced_envelope.candidates[0].candidate_value == "  结论 B  "
    assert "  原文 B  " in canonical_envelope_bytes(
        spaced_envelope, include_envelope_digest=False
    ).decode("utf-8")
    assert spaced_envelope.envelope_digest != original.envelope_digest

    invalid_identifier = _payload()
    invalid_identifier["evidence"][0]["evidence_key"] = " evidence-b "  # type: ignore[index]
    with pytest.raises(ValidationError):
        _build(invalid_identifier)


def test_completion_should_use_shared_explicit_unicode_white_space_set() -> None:
    from app.research_agent_completion_contract import (
        UNICODE_WHITE_SPACE_CODEPOINTS,
        is_unicode_blank,
    )

    assert UNICODE_WHITE_SPACE_CODEPOINTS == frozenset({
        *range(0x0009, 0x000E), 0x0020, 0x0085, 0x00A0, 0x1680,
        *range(0x2000, 0x200B), 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    })
    for whitespace in ("\u00a0", "\u2003"):
        assert is_unicode_blank(whitespace * 2) is True
        invalid_identifier = _payload()
        invalid_identifier["evidence"][0]["window_id"] = whitespace + "window-b"  # type: ignore[index]
        with pytest.raises(ValidationError):
            _build(invalid_identifier)
        blank_text = _payload()
        blank_text["evidence"][0]["quote_text"] = whitespace * 2  # type: ignore[index]
        with pytest.raises(ValidationError):
            _build(blank_text)
        content_whitespace = _payload()
        content_whitespace["evidence"][0]["quote_text"] = whitespace + "原文 B" + whitespace  # type: ignore[index]
        envelope = _build(content_whitespace)
        assert envelope.evidence[0].quote_text == whitespace + "原文 B" + whitespace


def test_completion_contract_should_match_persistence_lengths_and_ascii_stable_keys() -> None:
    accepted = _payload()
    accepted["task_id"] = "00000000-0000-0000-0000-000000000001"
    accepted["worker_instance_id"] = "w" * 128
    accepted["evidence"][0]["window_id"] = "w" * 64  # type: ignore[index]
    accepted["evidence"][0]["source_title"] = "题" * 300  # type: ignore[index]
    accepted["candidates"][0]["cell_key"] = "c" * 160  # type: ignore[index]
    _build(accepted)

    emoji_boundary = _payload()
    emoji_boundary["evidence"][0]["source_title"] = "😀" * 300  # type: ignore[index]
    _build(emoji_boundary)
    emoji_boundary["evidence"][0]["source_title"] += "😀"  # type: ignore[index]
    with pytest.raises(ValidationError):
        _build(emoji_boundary)

    for container, field, value in (
        ("evidence", "window_id", "w" * 65),
        ("evidence", "source_title", "题" * 301),
        ("candidates", "cell_key", "c" * 161),
        ("evidence", "evidence_key", "Evidence-UPPER"),
        ("candidates", "candidate_key", "候选-key"),
    ):
        invalid = _payload()
        invalid[container][0][field] = value  # type: ignore[index]
        with pytest.raises(ValidationError):
            _build(invalid)

    for invalid_execution_key in ("Deep-Cell:task-1", "执行:task-1"):
        invalid = _payload()
        invalid["execution_key"] = invalid_execution_key
        with pytest.raises(ValidationError):
            _build(invalid)

    for field, invalid_identity in (
        ("task_id", "t" * 37),
        ("task_id", "task-1"),
        ("task_id", "Task-UPPER"),
        ("worker_instance_id", "Worker-UPPER"),
        ("worker_instance_id", "工作者"),
    ):
        invalid = _payload()
        invalid[field] = invalid_identity
        with pytest.raises(ValidationError):
            _build(invalid)

    max_mergeable = _payload()
    max_mergeable["candidates"][0]["base_cell_version"] = 2_147_483_646  # type: ignore[index]
    _build(max_mergeable)
    overflow_on_accept = _payload()
    overflow_on_accept["candidates"][0]["base_cell_version"] = 2_147_483_647  # type: ignore[index]
    with pytest.raises(ValidationError):
        _build(overflow_on_accept)


def test_completion_contract_should_reject_explicit_null_usage_and_unpaired_surrogate() -> None:
    null_usage = _payload()
    null_usage["budget_usage"]["llm_calls"] = None  # type: ignore[index]
    with pytest.raises(ValidationError):
        _build(null_usage)

    null_digest = _payload()
    null_digest["envelope_digest"] = None
    with pytest.raises(ValueError, match="envelope_digest"):
        _build(null_digest)

    unpaired = _payload()
    unpaired["evidence"][0]["quote_text"] = "invalid-\ud800"  # type: ignore[index]
    with pytest.raises((ValidationError, ValueError), match="surrogate"):
        _build(unpaired)


def test_completion_canonical_utf8_should_preserve_emoji_u2028_and_u2029_without_ascii_escaping() -> None:
    from app.research_agent_completion_contract import (
        canonical_envelope_bytes,
        parse_completion_envelope_json,
    )

    payload = _payload()
    payload["evidence"][0]["quote_text"] = "emoji 😀\u2028next\u2029last"  # type: ignore[index]
    envelope = _build(payload)
    canonical = canonical_envelope_bytes(envelope, include_envelope_digest=False)

    assert "emoji 😀\u2028next\u2029last" in canonical.decode("utf-8")
    assert b"\\ud83d" not in canonical and b"\\u2028" not in canonical and b"\\u2029" not in canonical
    escaped_wire = json.dumps(envelope.model_dump(mode="json"), ensure_ascii=True)
    assert "\\ud83d\\ude00" in escaped_wire
    assert parse_completion_envelope_json(escaped_wire).envelope_digest == envelope.envelope_digest


def test_completion_json_parser_should_reject_duplicate_object_keys_before_pydantic() -> None:
    from app.research_agent_completion_contract import parse_completion_envelope_json

    raw = json.dumps({**_payload(), "envelope_digest": "sha256:" + "0" * 64}, ensure_ascii=False)
    raw = raw.replace(
        '"task_id": "00000000-0000-0000-0000-000000000001"',
        '"task_id":"00000000-0000-0000-0000-000000000001",'
        '"task_id":"00000000-0000-0000-0000-000000000002"',
        1,
    )
    with pytest.raises(ValueError, match="duplicate JSON object key"):
        parse_completion_envelope_json(raw)


def test_completion_json_parser_should_reject_utf8_or_text_bom() -> None:
    from app.research_agent_completion_contract import (
        parse_completion_envelope_json,
        serialize_completion_envelope,
    )

    serialized = serialize_completion_envelope(_build(_payload()))
    with pytest.raises(ValueError, match="BOM"):
        parse_completion_envelope_json(b"\xef\xbb\xbf" + serialized)
    with pytest.raises(ValueError, match="BOM"):
        parse_completion_envelope_json("\ufeff" + serialized.decode("utf-8"))


def test_completion_full_serialized_envelope_should_enforce_exact_256kib_boundary() -> None:
    from app.research_agent_completion_contract import serialize_completion_envelope

    def payload(extra_character: int) -> dict[str, object]:
        result = _payload()
        template = copy.deepcopy(result["evidence"][0])  # type: ignore[index]
        evidence: list[dict[str, object]] = []
        for index in range(12):
            item = copy.deepcopy(template)
            item["evidence_key"] = f"evidence-{index:02d}"
            item["window_id"] = f"window-{index:02d}"
            item["quote_text"] = "q" * 9_000
            item["claim_text"] = "c" * 9_000
            evidence.append(item)
        for index in range(3):
            evidence[index]["quote_text"] = "q" * 15_384
            evidence[index]["claim_text"] = "c" * 15_384
        evidence[3]["quote_text"] = "q" * (11_973 + extra_character)
        result["evidence"] = evidence
        result["candidates"] = []
        result["termination_reason"] = "EVIDENCE_ONLY"
        result["budget_usage"]["evidence_cards"] = 12  # type: ignore[index]
        result["budget_usage"]["candidates_submitted"] = 0  # type: ignore[index]
        return result

    boundary = _build(payload(0))
    assert len(serialize_completion_envelope(boundary)) == 256 * 1024
    with pytest.raises(ValidationError, match="payload size"):
        _build(payload(1))


#: Cross-runtime golden value shared with ResearchAgentCompletionCanonicalizerTest.java.
_DR102_GOLDEN_DIGEST = "sha256:3307022eef5d046b75c609e8dba679017cd5ddcfa670b5f5170861769821aa44"


def _diagnostics(**updates: object) -> dict[str, object]:
    value: dict[str, object] = {
        "schema_version": "research-extraction-diagnostics.v1",
        "termination_reason": "ALL_CARDS_REJECTED",
        "accepted_count": 0,
        "rejected_count": 2,
        "rejection_counts": {"NON_EXACT_QUOTE": 1, "WRONG_COLUMN": 1},
        "provider_receipt": {
            "purpose": "research.extract",
            "transport": "openai-compatible",
            "model": "gpt-test",
            "call_count": 1,
            "response_digest": "sha256:" + "a" * 64,
            "response_chars": 1234,
        },
        "rejection_samples": [
            {
                "reason": "NON_EXACT_QUOTE",
                "window_id": "window-a",
                "column_key": "method",
                "detail": "window_id=window-a column_key=method quote_len=12",
            },
            {
                "reason": "WRONG_COLUMN",
                "window_id": "window-b",
                "column_key": "unknown",
                "detail": "window_id=window-b column_key=unknown",
            },
        ],
    }
    value.update(updates)
    return value


def _v2_extraction_payload(**diagnostics_updates: object) -> dict[str, object]:
    return {
        "schema_version": "research-agent-completion.v2",
        "task_id": "00000000-0000-0000-0000-000000000001",
        "worker_instance_id": "worker-a",
        "lease_epoch": 2,
        "fencing_token": 7,
        "execution_key": "deep-cell:00000000-0000-0000-0000-000000000001:2:7",
        "task_snapshot_digest": "sha256:" + "1" * 64,
        "termination_reason": "NO_SUPPORTED_CANDIDATE",
        "budget_usage": {
            "llm_calls": 1,
            "search_calls": 1,
            "fetch_calls": 1,
            "read_calls": 1,
            "extract_calls": 1,
            "evidence_cards": 0,
            "candidates_submitted": 0,
        },
        "telemetry": {"search_hits": 2, "documents": 1, "windows": 2},
        "trace_digest": "sha256:" + "2" * 64,
        "evidence": [],
        "candidates": [],
        "extraction_diagnostics": _diagnostics(**diagnostics_updates),
    }


def test_v2_extraction_envelope_should_match_cross_runtime_golden_digest() -> None:
    from app.research_agent_completion_contract import canonical_envelope_bytes

    envelope = _build(_v2_extraction_payload())

    assert envelope.schema_version == "research-agent-completion.v2"
    assert envelope.role_result is None
    assert envelope.extraction_diagnostics is not None
    assert envelope.extraction_diagnostics.rejection_counts == {
        "NON_EXACT_QUOTE": 1,
        "WRONG_COLUMN": 1,
    }
    canonical = canonical_envelope_bytes(envelope, include_envelope_digest=False)
    assert b'"extraction_diagnostics"' in canonical and b'"role_result"' not in canonical
    assert envelope.envelope_digest == _DR102_GOLDEN_DIGEST


@pytest.mark.parametrize(
    "diagnostics_updates",
    [
        {"accepted_count": 0, "termination_reason": "ACCEPTED_CARDS", "rejected_count": 0,
         "rejection_counts": {}},
        {"accepted_count": 1, "termination_reason": "ALL_CARDS_REJECTED", "rejected_count": 2,
         "rejection_counts": {"NON_EXACT_QUOTE": 2}},
        {"rejection_counts": {"NON_EXACT_QUOTE": 1}},
        {"rejection_counts": {"NON_EXACT_QUOTE": 1, "UNKNOWN_REASON": 1}},
        {"rejected_count": 1},
        {"provider_receipt": {"purpose": "research.extract", "transport": "openai-compatible",
                              "model": "gpt-test", "call_count": 1, "response_digest": "not-a-digest",
                              "response_chars": 1}},
        {"provider_receipt": {"purpose": "research.other", "transport": "openai-compatible",
                              "model": "gpt-test", "call_count": 1, "response_digest": "",
                              "response_chars": 1}},
        {"future_field": 1},
    ],
)
def test_extraction_diagnostics_hard_gates_should_reject_inconsistent_shapes(diagnostics_updates) -> None:
    with pytest.raises(ValidationError):
        _build(_v2_extraction_payload(**diagnostics_updates))


def test_extraction_diagnostics_should_accept_a_clean_accepted_extraction() -> None:
    envelope = _build(_v2_extraction_payload(
        termination_reason="ACCEPTED_CARDS",
        accepted_count=1,
        rejected_count=0,
        rejection_counts={},
        rejection_samples=[],
    ))

    assert envelope.extraction_diagnostics is not None
    assert envelope.extraction_diagnostics.accepted_count == 1


def test_extraction_diagnostics_should_reject_more_samples_than_rejections() -> None:
    samples = [
        {"reason": "NON_EXACT_QUOTE", "window_id": f"window-{index}", "column_key": "method", "detail": ""}
        for index in range(3)
    ]
    with pytest.raises(ValidationError, match="rejection_samples"):
        _build(_v2_extraction_payload(rejection_samples=samples))


def test_completion_shapes_should_bind_v1_role_and_extraction_variants_exactly() -> None:
    from app.research_agent_completion_contract import ResearchAgentExtractionDiagnostics

    # v1 must never carry extraction_diagnostics.
    v1_with_diagnostics = _payload()
    v1_with_diagnostics["extraction_diagnostics"] = _diagnostics()
    with pytest.raises(ValidationError, match="extraction_diagnostics"):
        _build(v1_with_diagnostics)

    # v2 non-ROLE_RESULT must carry extraction_diagnostics (not None).
    v2_without_diagnostics = _v2_extraction_payload()
    v2_without_diagnostics.pop("extraction_diagnostics")
    with pytest.raises(ValidationError, match="extraction_diagnostics"):
        _build(v2_without_diagnostics)

    # v2 ROLE_RESULT must not carry extraction_diagnostics.
    role_result = _payload()
    role_result["schema_version"] = "research-agent-completion.v2"
    role_result["termination_reason"] = "ROLE_RESULT"
    role_result["evidence"] = []
    role_result["candidates"] = []
    role_result["budget_usage"]["evidence_cards"] = 0  # type: ignore[index]
    role_result["budget_usage"]["candidates_submitted"] = 0  # type: ignore[index]
    role_result["role_result"] = {"result_schema_version": "research-evidence-audit-result.v1"}
    _build(role_result)
    role_result["extraction_diagnostics"] = _diagnostics()
    with pytest.raises(ValidationError, match="extraction_diagnostics"):
        _build(role_result)

    # The diagnostics model itself is frozen/strict and rejects unknown subfields.
    with pytest.raises(ValidationError):
        ResearchAgentExtractionDiagnostics.model_validate(_diagnostics(future_field=1))
