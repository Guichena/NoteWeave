"""DR-106：抽取诊断词表跨语言守卫的自身测试。

守卫脚本位于 ``scripts/deepresearch/check_contract_vocabulary.py``（仓库级，
不属于 Worker 包），这里通过 ``importlib`` 以绝对路径加载，不修改 ``sys.path``、
不把它注册进 ``sys.modules``，避免污染其他测试。

被测的不是「词表本身」，而是「守卫是否真的能抓到漂移」：

- 正向：脚本解析出的 Python 侧词表必须与真实枚举取值完全一致（防止静默误解析）；
- 正向：脚本解析出的 Java 侧词表必须与 Python 侧完全一致（当前仓库处于一致状态）；
- 负向：在内存里变异 Java 源码，断言比较函数报告出预期差异而不是「一致」，
  且「Java 少一个」与「Java 多一个」两个方向都被覆盖；
- 负向：符号被删除时必须抛错，而不是「解析不到就默认通过」。
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

from app.extraction_result import ExtractionFailureReason, ExtractionTerminationReason

_SCRIPT_PATH = (
    Path(__file__).resolve().parents[3] / "scripts" / "deepresearch" / "check_contract_vocabulary.py"
)


def _load_guard_module():
    spec = importlib.util.spec_from_file_location("dr106_contract_vocabulary_guard", _SCRIPT_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


guard = _load_guard_module()

JAVA_SOURCE_TEXT = guard.JAVA_SOURCE.read_text(encoding="utf-8")
PYTHON_SIDE = guard.load_python_vocabulary()
JAVA_SIDE = guard.load_java_vocabulary()


def _compare(mutated_java_source: str) -> guard.VocabularyComparison:
    return guard.compare_vocabulary(PYTHON_SIDE, guard.parse_java_vocabulary(mutated_java_source))


# --------------------------------------------------------------------------- #
# 1. 脚本存在且路径约定未被搬动
# --------------------------------------------------------------------------- #
def test_guard_script_resolves_repo_sources() -> None:
    assert _SCRIPT_PATH.is_file(), f"guard script missing: {_SCRIPT_PATH}"
    assert guard.JAVA_SOURCE.is_file()
    assert guard.PYTHON_ENUM_SOURCE.is_file()
    assert guard.PYTHON_CONTRACT_SOURCE.is_file()
    assert "dr106_contract_vocabulary_guard" not in sys.modules


# --------------------------------------------------------------------------- #
# 2. Python 侧解析 == 真实枚举取值（防止静默误解析）
# --------------------------------------------------------------------------- #
def test_python_termination_vocabulary_matches_real_enum() -> None:
    assert set(PYTHON_SIDE.termination_reasons) == {item.value for item in ExtractionTerminationReason}
    assert len(PYTHON_SIDE.termination_reasons) == len(set(PYTHON_SIDE.termination_reasons))


def test_python_failure_vocabulary_matches_real_enum() -> None:
    assert set(PYTHON_SIDE.failure_reasons) == {item.value for item in ExtractionFailureReason}
    assert len(PYTHON_SIDE.failure_reasons) == len(set(PYTHON_SIDE.failure_reasons))


def test_python_accepted_reason_matches_real_enum() -> None:
    assert PYTHON_SIDE.accepted_reason == ExtractionTerminationReason.ACCEPTED_CARDS.value
    assert PYTHON_SIDE.accepted_reason in set(PYTHON_SIDE.termination_reasons)


def test_aliased_enum_member_resolves_to_referenced_literal() -> None:
    """``X = ExtractionFailureReason.LLM_UNAVAILABLE.value`` 必须解析成 ``LLM_UNAVAILABLE``。

    这是本守卫最容易解析错的一处：把表达式文本而不是被引用的字面量当成取值，
    会让 Java 侧凭空多出一个非法值，或让真值被静默改写。
    """
    assert "LLM_UNAVAILABLE" in PYTHON_SIDE.termination_reasons
    assert ExtractionTerminationReason.LLM_UNAVAILABLE.value == "LLM_UNAVAILABLE"
    assert not any("ExtractionFailureReason" in value for value in PYTHON_SIDE.termination_reasons)
    assert not any("." in value for value in PYTHON_SIDE.termination_reasons)


def test_aliased_enum_member_resolution_on_synthetic_source() -> None:
    synthetic_enum = '''
from enum import StrEnum


class ExtractionFailureReason(StrEnum):
    LLM_UNAVAILABLE = "LLM_UNAVAILABLE"
    INVALID_JSON = "INVALID_JSON"


class ExtractionTerminationReason(StrEnum):
    """docstring must be skipped."""

    ACCEPTED_CARDS = "ACCEPTED_CARDS"
    LLM_UNAVAILABLE = ExtractionFailureReason.LLM_UNAVAILABLE.value
    INVALID_JSON = ExtractionFailureReason.INVALID_JSON.value
'''
    synthetic_contract = '''
_EXTRACTION_DIAGNOSTICS_SCHEMA = "schema-under-test.v1"
_EXTRACTION_ACCEPTED_REASON = ExtractionTerminationReason.ACCEPTED_CARDS.value
'''
    parsed = guard.parse_python_vocabulary(synthetic_enum, synthetic_contract)

    assert parsed.termination_reasons == ("ACCEPTED_CARDS", "LLM_UNAVAILABLE", "INVALID_JSON")
    assert parsed.failure_reasons == ("LLM_UNAVAILABLE", "INVALID_JSON")
    assert parsed.schema == "schema-under-test.v1"
    assert parsed.accepted_reason == "ACCEPTED_CARDS"


# --------------------------------------------------------------------------- #
# 3. Java 侧解析 == Python 侧（当前仓库一致）
# --------------------------------------------------------------------------- #
def test_java_vocabulary_matches_python_vocabulary() -> None:
    comparison = guard.compare_vocabulary(PYTHON_SIDE, JAVA_SIDE)

    assert comparison.is_clean, comparison.problem_lines()
    assert comparison.problem_lines() == []
    assert set(JAVA_SIDE.termination_reasons) == set(PYTHON_SIDE.termination_reasons)
    assert set(JAVA_SIDE.failure_reasons) == set(PYTHON_SIDE.failure_reasons)


def test_diagnostics_schema_matches_on_both_sides() -> None:
    from app.research_agent_completion_contract import _EXTRACTION_DIAGNOSTICS_SCHEMA

    assert PYTHON_SIDE.schema == _EXTRACTION_DIAGNOSTICS_SCHEMA
    assert JAVA_SIDE.schema == _EXTRACTION_DIAGNOSTICS_SCHEMA
    assert PYTHON_SIDE.schema == JAVA_SIDE.schema


# --------------------------------------------------------------------------- #
# 4. 负例：守卫必须真的抓到漂移
# --------------------------------------------------------------------------- #
def test_java_typo_is_reported_in_both_directions() -> None:
    mutated = JAVA_SOURCE_TEXT.replace('"WRONG_COLUMN"', '"WRONG_COLUMN_TYPO"')

    comparison = _compare(mutated)

    assert not comparison.is_clean
    assert comparison.failure_missing_in_java == ("WRONG_COLUMN",)
    assert comparison.failure_extra_in_java == ("WRONG_COLUMN_TYPO",)
    assert comparison.termination_missing_in_java == ()
    assert comparison.termination_extra_in_java == ()


def test_java_missing_value_is_reported() -> None:
    assert ', "EMPTY_QUOTE"' in JAVA_SOURCE_TEXT
    mutated = JAVA_SOURCE_TEXT.replace(', "EMPTY_QUOTE"', "")

    comparison = _compare(mutated)

    assert not comparison.is_clean
    assert comparison.failure_missing_in_java == ("EMPTY_QUOTE",)
    assert comparison.failure_extra_in_java == ()


def test_java_extra_value_is_reported() -> None:
    mutated = JAVA_SOURCE_TEXT.replace(
        '"DUPLICATE_EVIDENCE"', '"DUPLICATE_EVIDENCE", "JAVA_ONLY_REASON"'
    )

    comparison = _compare(mutated)

    assert not comparison.is_clean
    assert comparison.failure_extra_in_java == ("JAVA_ONLY_REASON",)
    assert comparison.failure_missing_in_java == ()


def test_java_termination_drift_is_reported() -> None:
    mutated = JAVA_SOURCE_TEXT.replace('"ALL_CARDS_REJECTED"', '"ALL_CARDS_REJECTED_V2"')

    comparison = _compare(mutated)

    assert not comparison.is_clean
    assert comparison.termination_missing_in_java == ("ALL_CARDS_REJECTED",)
    assert comparison.termination_extra_in_java == ("ALL_CARDS_REJECTED_V2",)
    assert comparison.failure_missing_in_java == ()
    assert comparison.failure_extra_in_java == ()


def test_schema_drift_is_reported() -> None:
    mutated = JAVA_SOURCE_TEXT.replace(
        'EXTRACTION_DIAGNOSTICS_SCHEMA = "research-extraction-diagnostics.v1"',
        'EXTRACTION_DIAGNOSTICS_SCHEMA = "research-extraction-diagnostics.v2"',
    )

    comparison = _compare(mutated)

    assert not comparison.is_clean
    assert comparison.schema_mismatch == (
        "research-extraction-diagnostics.v1",
        "research-extraction-diagnostics.v2",
    )
    assert any("schema_version drift" in line for line in comparison.problem_lines())


def test_accepted_reason_drift_is_reported() -> None:
    mutated = JAVA_SOURCE_TEXT.replace(
        'EXTRACTION_ACCEPTED_REASON = "ACCEPTED_CARDS"',
        'EXTRACTION_ACCEPTED_REASON = "ACCEPTED_CARD"',
    )

    comparison = _compare(mutated)

    assert not comparison.is_clean
    assert comparison.accepted_mismatch == ("ACCEPTED_CARDS", "ACCEPTED_CARD")


# --------------------------------------------------------------------------- #
# 5. 负例：解析不到必须失败，不允许「默认通过」
# --------------------------------------------------------------------------- #
def test_missing_java_set_fails_loudly() -> None:
    mutated = JAVA_SOURCE_TEXT.replace("EXTRACTION_FAILURE_REASONS = Set.of(", "EXTRACTION_FAILURE_REASONS_X = Set.of(")

    with pytest.raises(guard.VocabularyGuardError, match="EXTRACTION_FAILURE_REASONS"):
        guard.parse_java_vocabulary(mutated)


def test_missing_python_enum_fails_loudly() -> None:
    with pytest.raises(guard.VocabularyGuardError, match="ExtractionTerminationReason"):
        guard.parse_python_enum_literals("class SomethingElse:\n    A = 'A'\n", "ExtractionTerminationReason")


def test_unresolvable_enum_member_fails_loudly() -> None:
    source = "class ExtractionFailureReason:\n    INVALID_JSON = build_value('INVALID_JSON')\n"

    with pytest.raises(guard.VocabularyGuardError, match="not a resolvable string literal"):
        guard.parse_python_enum_literals(source, "ExtractionFailureReason")


def test_empty_java_set_fails_loudly() -> None:
    """空集合必须失败：否则「Java 词表被清空」会退化成「一致」。"""
    synthetic = (
        "public static final Set<String> EXTRACTION_FAILURE_REASONS = Set.of();\n"
        "public static final Set<String> EXTRACTION_TERMINATION_REASONS = Set.of();\n"
    )

    with pytest.raises(guard.VocabularyGuardError, match="no string literals"):
        guard.parse_java_vocabulary(synthetic)


# --------------------------------------------------------------------------- #
# 6. 证据输出：成功时给出可直接引用的计数与取值
# --------------------------------------------------------------------------- #
def test_evidence_lines_report_counts_and_values() -> None:
    lines = guard.evidence_lines(PYTHON_SIDE, JAVA_SIDE)

    joined = "\n".join(lines)
    assert f"python_count={len(set(PYTHON_SIDE.termination_reasons))}" in joined
    assert f"python_count={len(set(PYTHON_SIDE.failure_reasons))}" in joined
    assert "values: " + ", ".join(sorted(set(PYTHON_SIDE.failure_reasons))) in joined
    assert all(line.startswith("[ok]") or line.startswith("     ") for line in lines)
