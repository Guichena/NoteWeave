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


def test_translated_month_keeps_quantity_and_date_boundaries() -> None:
    quote = "Phase two: 600 teachers in February 2027."
    assert validate_claim_facts("第二阶段计划为2027年2月覆盖600名教师", quote).status == "ENTAILED"
    assert validate_claim_facts("第二阶段计划为2027年3月覆盖600名教师", quote).status == "CONTRADICTED"
    assert validate_claim_facts("第二阶段计划为2027年2月覆盖601名教师", quote).status == "CONTRADICTED"
    assert validate_claim_facts("2 schools in 2027年2月", quote).status != "ENTAILED"
    assert validate_claim_facts("2027年2月覆盖600名教师", "600 teachers in 2027.").status != "ENTAILED"


def test_named_month_orders_and_date_precision() -> None:
    for quote in ["December 15, 2026", "15 December 2026", "Dec. 15, 2026"]:
        assert validate_claim_facts("2026年12月15日", quote).status == "ENTAILED"
        assert validate_claim_facts("Released before 2026-12", "Released before " + quote).status != "ENTAILED"
        assert validate_claim_facts("Not released in 2026-12", "Not released on " + quote).status != "ENTAILED"
        assert validate_claim_facts("2026年12月16日", quote).status == "CONTRADICTED"


def test_ungrouped_quantities_keep_every_digit() -> None:
    assert validate_claim_facts("预算300000", "预算300001").status == "CONTRADICTED"
    assert validate_claim_facts("1024 people", "1025 people").status == "CONTRADICTED"
    assert validate_claim_facts("300000 TWD", "300,000 TWD").status == "ENTAILED"


def test_named_dates_do_not_leak_day_or_year_as_quantities() -> None:
    atoms = extract_fact_atoms("600 teachers on 15 December 2026")
    assert [a.value for a in atoms if a.kind == "DATE"] == ["2026-12-15"]
    assert [a.value for a in atoms if a.kind == "NUMBER"] == ["600"]


def test_year_like_decimal_with_unit_remains_a_quantity() -> None:
    assert "UNIT_CONTRADICTION" in validate_claim_facts("Memory 2027.2 GB", "Memory 2027.2 MB").reason_codes
    assert validate_claim_facts("Memory 2027.2 GB", "Memory 2027.3 GB").status == "CONTRADICTED"
    assert not [a for a in extract_fact_atoms("Memory 2027.2 GB") if a.kind == "DATE"]
