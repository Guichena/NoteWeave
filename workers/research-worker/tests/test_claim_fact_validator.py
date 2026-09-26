from app.claim_fact_validator import extract_fact_atoms, validate_claim_facts


def test_typed_claim_matches_number_unit_date_and_direction() -> None:
    result = validate_claim_facts(
        "Latency decreased to 120 ms after 2025-03-02.",
        "Latency decreased to 120 ms after 2025-03-02.",
    )

    assert result.status == "ENTAILED"
    assert result.has_high_risk_facts is True


def test_typed_claim_rejects_numeric_and_direction_contradictions() -> None:
    result = validate_claim_facts(
        "Latency decreased to 120 ms.",
        "Latency increased to 180 ms.",
    )

    assert result.status == "CONTRADICTED"
    assert "NUMBER_VALUE_CONTRADICTION" in result.reason_codes
    assert "COMPARISON_DIRECTION_CONTRADICTION" in result.reason_codes


def test_typed_claim_does_not_lexically_promote_missing_number() -> None:
    result = validate_claim_facts(
        "The error rate is 2%.",
        "The report discusses the error rate but gives no value.",
    )

    assert result.status == "UNKNOWN"
    assert result.reason_codes == ["TYPED_FACT_MISSING_FROM_CITATION_SPAN"]


def test_extracts_chinese_typed_facts() -> None:
    atoms = extract_fact_atoms("2026年8月7日延迟下降到至少50毫秒")

    assert any(atom.kind == "DATE" and atom.value == "2026-08-07" for atom in atoms)
    assert any(atom.kind == "NUMBER" and atom.value == "50" and atom.unit == "MS" for atom in atoms)
    assert any(atom.kind == "COMPARISON" and atom.value == "AT_LEAST" for atom in atoms)
    assert any(atom.kind == "COMPARISON" and atom.value == "DECREASE" for atom in atoms)


def test_negation_is_scoped_to_the_nearby_fact() -> None:
    contradiction = validate_claim_facts(
        "The release does not support 15 GB files.",
        "The release supports 15 GB files.",
    )
    unrelated = validate_claim_facts(
        "The release supports 15 GB files.",
        "The release supports 15 GB files; it does not change the 2 GB default.",
    )

    assert contradiction.status == "CONTRADICTED"
    assert "LOCAL_POLARITY_CONTRADICTION" in contradiction.reason_codes
    assert unrelated.status == "ENTAILED"
