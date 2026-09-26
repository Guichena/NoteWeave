"""DR-106：抽取诊断词表的跨语言一致性守卫。

背景（DR-102 复核立案）：抽取诊断的**真值**在 Python 侧
``workers/research-worker/app/extraction_result.py`` 的两个 StrEnum 与
``research_agent_completion_contract.py`` 的 schema 常量里；Java 侧
``ResearchAgentCompletionCanonicalizer.java`` 硬编码了一份镜像副本。

已存在的 golden 向量只覆盖**当前已存在**的取值，因此 Python 新增一个原因码而
Java 未同步时，运行时信封才会被判 ``extraction_diagnostics ... is invalid``。
本脚本把两侧词表从源码里解析出来做**双向集合比较**，让漂移在 CI 暴露。

设计约束：
- 零第三方依赖（只用标准库），不 import ``app.*``（CI 可能没有 pydantic）；
- 解析失败（找不到符号、集合为空、成员不是可解析字面量）一律抛错退出，
  绝不允许「解析不到就默认通过」。

用法（仓库根目录）：
    python scripts/deepresearch/check_contract_vocabulary.py
"""

import ast
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

REPO_ROOT = Path(__file__).resolve().parents[2]

PYTHON_ENUM_SOURCE = REPO_ROOT / "workers" / "research-worker" / "app" / "extraction_result.py"
PYTHON_CONTRACT_SOURCE = (
    REPO_ROOT / "workers" / "research-worker" / "app" / "research_agent_completion_contract.py"
)
JAVA_SOURCE = (
    REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "noteweave" / "research"
    / "ResearchAgentCompletionCanonicalizer.java"
)

TERMINATION_ENUM = "ExtractionTerminationReason"
FAILURE_ENUM = "ExtractionFailureReason"
PY_SCHEMA_CONSTANT = "_EXTRACTION_DIAGNOSTICS_SCHEMA"
PY_ACCEPTED_CONSTANT = "_EXTRACTION_ACCEPTED_REASON"
JAVA_SCHEMA_CONSTANT = "EXTRACTION_DIAGNOSTICS_SCHEMA"
JAVA_ACCEPTED_CONSTANT = "EXTRACTION_ACCEPTED_REASON"
JAVA_TERMINATION_SET = "EXTRACTION_TERMINATION_REASONS"
JAVA_FAILURE_SET = "EXTRACTION_FAILURE_REASONS"


class VocabularyGuardError(RuntimeError):
    """解析或比较守卫无法给出可信结论时抛出；调用方必须视为失败。"""


@dataclass(frozen=True)
class Vocabulary:
    """一侧（Python 或 Java）的抽取诊断词表。"""

    schema: str
    accepted_reason: str
    termination_reasons: tuple[str, ...]
    failure_reasons: tuple[str, ...]


@dataclass(frozen=True)
class VocabularyComparison:
    """双向比较结果；全部字段为空表示两侧一致。"""

    schema_mismatch: tuple[str, str] | None = None
    accepted_mismatch: tuple[str, str] | None = None
    termination_missing_in_java: tuple[str, ...] = ()
    termination_extra_in_java: tuple[str, ...] = ()
    failure_missing_in_java: tuple[str, ...] = ()
    failure_extra_in_java: tuple[str, ...] = ()

    @property
    def is_clean(self) -> bool:
        return (
            self.schema_mismatch is None
            and self.accepted_mismatch is None
            and not self.termination_missing_in_java
            and not self.termination_extra_in_java
            and not self.failure_missing_in_java
            and not self.failure_extra_in_java
        )

    def problem_lines(self) -> list[str]:
        lines: list[str] = []
        if self.schema_mismatch is not None:
            python_value, java_value = self.schema_mismatch
            lines.append(f"[fail] schema_version drift: python={python_value!r} java={java_value!r}")
        if self.accepted_mismatch is not None:
            python_value, java_value = self.accepted_mismatch
            lines.append(f"[fail] accepted_reason drift: python={python_value!r} java={java_value!r}")
        for label, missing, extra in (
            ("termination_reasons", self.termination_missing_in_java, self.termination_extra_in_java),
            ("failure_reasons", self.failure_missing_in_java, self.failure_extra_in_java),
        ):
            for value in missing:
                lines.append(f"[fail] {label}: {value!r} exists in Python but is MISSING in Java")
            for value in extra:
                lines.append(f"[fail] {label}: {value!r} exists in Java but is UNKNOWN in Python")
        return lines


# --------------------------------------------------------------------------- #
# Python 侧：用 ast 解析源码文本（不 import app.*）
# --------------------------------------------------------------------------- #
def _dotted_name(node: ast.expr) -> str | None:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        owner = _dotted_name(node.value)
        return f"{owner}.{node.attr}" if owner is not None else None
    return None


def _resolve_string(node: ast.expr, external_values: Mapping[str, str]) -> str | None:
    """把赋值右侧解析成字符串字面量。

    两种形态都必须支持：

    - 直接字面量：``INVALID_JSON = "INVALID_JSON"``；
    - 跨枚举引用：``LLM_UNAVAILABLE = ExtractionFailureReason.LLM_UNAVAILABLE.value``，
      必须解析成 ``"LLM_UNAVAILABLE"`` 而不是整段表达式文本。
    """
    if isinstance(node, ast.Constant):
        return node.value if isinstance(node.value, str) else None
    if isinstance(node, ast.Attribute) and node.attr == "value":
        owner = _dotted_name(node.value)
        if owner is None:
            return None
        if owner in external_values:
            return external_values[owner]
        short_name = owner.rsplit(".", 1)[-1]
        if short_name in external_values:
            return external_values[short_name]
    return None


def _parse_or_raise(source: str, origin: str) -> ast.Module:
    try:
        return ast.parse(source)
    except SyntaxError as error:  # pragma: no cover - 源码损坏才会触发
        raise VocabularyGuardError(f"cannot parse Python source {origin}: {error}") from error


def parse_python_enum_literals(
    source: str,
    class_name: str,
    external_values: Mapping[str, str] | None = None,
) -> dict[str, str]:
    """解析一个 StrEnum 的成员名 -> 字面量取值。"""
    external = dict(external_values or {})
    tree = _parse_or_raise(source, f"enum {class_name}")
    members: dict[str, str] = {}
    for node in ast.walk(tree):
        if not isinstance(node, ast.ClassDef) or node.name != class_name:
            continue
        for statement in node.body:
            if not isinstance(statement, ast.Assign) or len(statement.targets) != 1:
                continue
            target = statement.targets[0]
            if not isinstance(target, ast.Name) or target.id.startswith("_"):
                continue
            value = _resolve_string(statement.value, external)
            if value is None:
                raise VocabularyGuardError(
                    f"{class_name}.{target.id} is not a resolvable string literal; "
                    "the vocabulary guard refuses to guess"
                )
            members[target.id] = value
        if not members:
            raise VocabularyGuardError(f"enum {class_name} exposes no string members")
        return members
    raise VocabularyGuardError(f"enum class {class_name} not found in Python source")


def parse_python_module_strings(
    source: str,
    names: tuple[str, ...],
    external_values: Mapping[str, str] | None = None,
) -> dict[str, str]:
    """解析模块级字符串常量，支持直接字面量与 ``Enum.MEMBER.value`` 引用。"""
    external = dict(external_values or {})
    tree = _parse_or_raise(source, "contract module")
    resolved: dict[str, str] = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign) or len(node.targets) != 1:
            continue
        target = node.targets[0]
        if not isinstance(target, ast.Name) or target.id not in names:
            continue
        value = _resolve_string(node.value, external)
        if value is None:
            raise VocabularyGuardError(
                f"module constant {target.id} is not a resolvable string literal"
            )
        resolved[target.id] = value
    missing = [name for name in names if name not in resolved]
    if missing:
        raise VocabularyGuardError(f"module constants not found in Python source: {', '.join(missing)}")
    return resolved


def parse_python_vocabulary(enum_source: str, contract_source: str) -> Vocabulary:
    """解析 Python 侧（真值）词表。"""
    failure_members = parse_python_enum_literals(enum_source, FAILURE_ENUM)
    # 终止原因里有成员引用失败原因（...= ExtractionFailureReason.X.value），
    # 因此先解析失败原因，再把它的取值作为可解析的外部引用表传下去。
    termination_members = parse_python_enum_literals(
        enum_source,
        TERMINATION_ENUM,
        _external_value_map({FAILURE_ENUM: failure_members}),
    )
    external = _external_value_map(
        {FAILURE_ENUM: failure_members, TERMINATION_ENUM: termination_members}
    )
    constants = parse_python_module_strings(
        contract_source,
        (PY_SCHEMA_CONSTANT, PY_ACCEPTED_CONSTANT),
        external,
    )
    return Vocabulary(
        schema=constants[PY_SCHEMA_CONSTANT],
        accepted_reason=constants[PY_ACCEPTED_CONSTANT],
        termination_reasons=_dedupe(termination_members.values(), f"{TERMINATION_ENUM}"),
        failure_reasons=_dedupe(failure_members.values(), f"{FAILURE_ENUM}"),
    )


def _external_value_map(enums: Mapping[str, Mapping[str, str]]) -> dict[str, str]:
    values: dict[str, str] = {}
    for class_name, members in enums.items():
        for member_name, literal in members.items():
            values[f"{class_name}.{member_name}"] = literal
            values[member_name] = literal
    return values


def _dedupe(values, vocabulary_name: str) -> tuple[str, ...]:
    ordered: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value in seen:
            raise VocabularyGuardError(f"duplicate literal {value!r} in {vocabulary_name}")
        seen.add(value)
        ordered.append(value)
    if not ordered:
        raise VocabularyGuardError(f"vocabulary {vocabulary_name} parsed as empty")
    return tuple(ordered)


# --------------------------------------------------------------------------- #
# Java 侧：源码文本正则解析
# --------------------------------------------------------------------------- #
_JAVA_STRING_SET_PATTERN = re.compile(
    r"Set<String>\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*Set\.of\((?P<body>.*?)\)\s*;",
    re.DOTALL,
)
_JAVA_STRING_CONSTANT_PATTERN = re.compile(
    r"\bString\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\"((?:[^\"\\]|\\.)*)\"\s*;"
)
_JAVA_LITERAL_PATTERN = re.compile(r'"((?:[^"\\]|\\.)*)"')


def parse_java_vocabulary(source: str) -> Vocabulary:
    """解析 Java 侧镜像词表。"""
    sets: dict[str, tuple[str, ...]] = {}
    for match in _JAVA_STRING_SET_PATTERN.finditer(source):
        name = match.group(1)
        literals = tuple(_JAVA_LITERAL_PATTERN.findall(match.group("body")))
        if not literals:
            raise VocabularyGuardError(f"Java Set.of(...) {name} contains no string literals")
        if len(set(literals)) != len(literals):
            raise VocabularyGuardError(f"Java Set.of(...) {name} contains duplicate literals")
        sets[name] = literals
    constants = {
        match.group(1): match.group(2)
        for match in _JAVA_STRING_CONSTANT_PATTERN.finditer(source)
    }

    def require_set(name: str) -> tuple[str, ...]:
        if name not in sets:
            raise VocabularyGuardError(f"Java constant {name} was not found; refusing to pass silently")
        return sets[name]

    def require_constant(name: str) -> str:
        if name not in constants:
            raise VocabularyGuardError(f"Java constant {name} was not found; refusing to pass silently")
        return constants[name]

    return Vocabulary(
        schema=require_constant(JAVA_SCHEMA_CONSTANT),
        accepted_reason=require_constant(JAVA_ACCEPTED_CONSTANT),
        termination_reasons=require_set(JAVA_TERMINATION_SET),
        failure_reasons=require_set(JAVA_FAILURE_SET),
    )


# --------------------------------------------------------------------------- #
# 加载与比较
# --------------------------------------------------------------------------- #
def load_python_vocabulary() -> Vocabulary:
    return parse_python_vocabulary(
        PYTHON_ENUM_SOURCE.read_text(encoding="utf-8"),
        PYTHON_CONTRACT_SOURCE.read_text(encoding="utf-8"),
    )


def load_java_vocabulary() -> Vocabulary:
    return parse_java_vocabulary(JAVA_SOURCE.read_text(encoding="utf-8"))


def compare_vocabulary(python_side: Vocabulary, java_side: Vocabulary) -> VocabularyComparison:
    """双向比较：Python 有而 Java 缺、Java 有而 Python 缺都要报告。"""
    return VocabularyComparison(
        schema_mismatch=None
        if python_side.schema == java_side.schema
        else (python_side.schema, java_side.schema),
        accepted_mismatch=None
        if python_side.accepted_reason == java_side.accepted_reason
        else (python_side.accepted_reason, java_side.accepted_reason),
        termination_missing_in_java=_difference(
            python_side.termination_reasons, java_side.termination_reasons
        ),
        termination_extra_in_java=_difference(
            java_side.termination_reasons, python_side.termination_reasons
        ),
        failure_missing_in_java=_difference(python_side.failure_reasons, java_side.failure_reasons),
        failure_extra_in_java=_difference(java_side.failure_reasons, python_side.failure_reasons),
    )


def _difference(left, right) -> tuple[str, ...]:
    return tuple(sorted(set(left) - set(right)))


def evidence_lines(python_side: Vocabulary, java_side: Vocabulary) -> list[str]:
    """通过时可直接引用的证据行：每个词表的元素计数与排序后的取值。"""
    lines = [
        f"[ok] schema_version: python={python_side.schema!r} java={java_side.schema!r}",
        f"[ok] accepted_reason: python={python_side.accepted_reason!r} "
        f"java={java_side.accepted_reason!r}",
    ]
    for label, python_values, java_values in (
        ("termination_reasons", python_side.termination_reasons, java_side.termination_reasons),
        ("failure_reasons", python_side.failure_reasons, java_side.failure_reasons),
    ):
        lines.append(
            f"[ok] {label}: python_count={len(set(python_values))} java_count={len(set(java_values))}"
        )
        lines.append(f"     values: {', '.join(sorted(set(python_values)))}")
    return lines


def main() -> int:
    print("DR-106 extraction diagnostics vocabulary guard (Python <-> Java)")
    print(f"  python enum source    : {PYTHON_ENUM_SOURCE.relative_to(REPO_ROOT)}")
    print(f"  python contract source: {PYTHON_CONTRACT_SOURCE.relative_to(REPO_ROOT)}")
    print(f"  java source           : {JAVA_SOURCE.relative_to(REPO_ROOT)}")
    try:
        python_side = load_python_vocabulary()
        java_side = load_java_vocabulary()
    except VocabularyGuardError as error:
        print(f"[fail] vocabulary parsing failed: {error}")
        return 2
    except OSError as error:
        print(f"[fail] source file unreadable: {error}")
        return 2

    comparison = compare_vocabulary(python_side, java_side)
    for line in evidence_lines(python_side, java_side):
        print(line)
    if not comparison.is_clean:
        print(f"[fail] {len(comparison.problem_lines())} vocabulary drift(s) detected:")
        for line in comparison.problem_lines():
            print(f"  {line}")
        return 1
    total = len(set(python_side.termination_reasons)) + len(set(python_side.failure_reasons))
    print(f"[ok] DR-106 guard passed: {total} values compared, both directions clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
