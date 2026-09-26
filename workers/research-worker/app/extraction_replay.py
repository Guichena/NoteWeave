"""DR-104：脱敏 replay fixture 与离线重放。

本模块把一次 Deep Research 抽取调用所需的最小输入（问题、schema 列、read
window 文本）与 Provider 的原始 JSON 响应固化成
``research-extraction-replay.v1`` fixture，并在不触网、不调用真实 LLM 的
前提下重放 :func:`app.extractor.extract_evidence_cards_detailed`。

DR-104 的退出条件是「同一 fixture 产生稳定判断」，证据产物是「重放命令与
结果」。因此本模块只做确定性工作：给定 fixture，重放路径不读取环境变量、
不访问网络、不依赖时间。:func:`verify_replay_fixture` 会把实际
``termination_reason`` / ``accepted_count`` / ``rejection_counts`` 与 fixture
中声明的 ``expected`` 逐一比对，任何偏差都抛出 :class:`ReplayExpectationError`。

约定：

- fixture 是脱敏产物：只保留结构特征，不保留真实域名、真实公司/产品名、
  真实 URL、真实人物与真实凭据；
- ``load_replay_fixture`` 做严格结构校验，词表越界（终止原因、拒绝原因）
  直接拒绝，避免「fixture 随便写、重放永远通过」；
- 本模块不修改抽取实现，只充当可复现的调用壳。
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

from app.extraction_result import (
    ExtractionFailureReason,
    ExtractionResult,
    ExtractionTerminationReason,
)
from app.extractor import extract_evidence_cards_detailed
from app.llm_client import FakeLlmClient
from app.models import (
    ControlPack,
    ResearchColumn,
    ResearchPlan,
    ResearchReadWindow,
    ResearchSchema,
    ResearchTaskInput,
    ResearchTaskInputPayload,
)

#: fixture 顶层 schema 标识；不匹配即拒绝加载。
FIXTURE_SCHEMA = "research-extraction-replay.v1"

#: ``expected.termination_reason`` 允许的取值（ExtractionTerminationReason 词表）。
_TERMINATION_VALUES = frozenset(reason.value for reason in ExtractionTerminationReason)
#: ``expected.rejection_counts`` 允许的键（ExtractionFailureReason 词表）。
_FAILURE_VALUES = frozenset(reason.value for reason in ExtractionFailureReason)

_REQUIRED_TOP_LEVEL_FIELDS = (
    "fixture_schema",
    "fixture_key",
    "fixture_version",
    "desensitized",
    "provider",
    "question",
    "schema_columns",
    "stop_contract",
    "windows",
    "provider_response_raw",
    "expected",
)
_REQUIRED_WINDOW_FIELDS = (
    "window_id",
    "source_id",
    "source_title",
    "query",
    "read_focus",
    "window_text",
)
_REQUIRED_EXPECTED_FIELDS = (
    "termination_reason",
    "accepted_count",
    "rejection_counts",
)

__all__ = [
    "FIXTURE_SCHEMA",
    "ReplayExpectation",
    "ReplayExpectationError",
    "ReplayFixture",
    "ReplayFixtureError",
    "load_replay_fixture",
    "main",
    "replay_directory",
    "replay_extraction_fixture",
    "verify_replay_fixture",
]


class ReplayFixtureError(Exception):
    """fixture 结构非法：缺字段、未脱敏、schema/词表越界。"""


class ReplayExpectationError(Exception):
    """重放结果与 fixture 声明的 ``expected`` 不一致。"""


@dataclass(frozen=True)
class ReplayExpectation:
    """fixture 声明的期望结果。"""

    termination_reason: str
    accepted_count: int
    rejection_counts: dict[str, int]


@dataclass(frozen=True)
class ReplayFixture:
    """已校验的脱敏 replay fixture。"""

    path: Path
    fixture_schema: str
    fixture_key: str
    fixture_version: int
    desensitized: bool
    provider: dict[str, object]
    question: str
    schema_columns: tuple[str, ...]
    stop_contract: dict[str, object]
    windows: tuple[dict[str, object], ...]
    provider_response_raw: str
    expected: ReplayExpectation


def load_replay_fixture(path: Path) -> ReplayFixture:
    """读取并严格校验一个 fixture 文件。

    校验项：

    - 顶层必须是 JSON object，且 ``fixture_schema`` 精确匹配；
    - 所有必需字段存在；``desensitized`` 必须严格为 ``true``；
    - ``expected.termination_reason`` 必须命中 ``ExtractionTerminationReason``；
    - ``expected.rejection_counts`` 的每个键必须命中 ``ExtractionFailureReason``。
    """

    path = Path(path)
    try:
        raw_text = path.read_text(encoding="utf-8")
    except OSError as exc:  # pragma: no cover - 文件系统错误路径
        raise ReplayFixtureError(f"{path}: 无法读取 fixture: {exc}") from exc
    try:
        data = json.loads(raw_text)
    except json.JSONDecodeError as exc:
        raise ReplayFixtureError(f"{path}: fixture 不是合法 JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ReplayFixtureError(f"{path}: fixture 顶层必须是 JSON object")

    for field_name in _REQUIRED_TOP_LEVEL_FIELDS:
        if field_name not in data:
            raise ReplayFixtureError(f"{path}: 缺少必需字段 '{field_name}'")

    fixture_schema = data["fixture_schema"]
    if fixture_schema != FIXTURE_SCHEMA:
        raise ReplayFixtureError(
            f"{path}: fixture_schema 必须是 '{FIXTURE_SCHEMA}'，实际为 {fixture_schema!r}"
        )

    if data["desensitized"] is not True:
        raise ReplayFixtureError(
            f"{path}: desensitized 必须严格为 true，拒绝重放未脱敏 fixture"
        )

    fixture_key = data["fixture_key"]
    if not isinstance(fixture_key, str) or not fixture_key.strip():
        raise ReplayFixtureError(f"{path}: fixture_key 必须是非空字符串")

    fixture_version = data["fixture_version"]
    if not _is_int(fixture_version) or fixture_version < 1:
        raise ReplayFixtureError(f"{path}: fixture_version 必须是 >=1 的整数")

    provider = data["provider"]
    if not isinstance(provider, dict):
        raise ReplayFixtureError(f"{path}: provider 必须是 JSON object")

    question = data["question"]
    if not isinstance(question, str) or not question.strip():
        raise ReplayFixtureError(f"{path}: question 必须是非空字符串")

    schema_columns = data["schema_columns"]
    if not isinstance(schema_columns, list) or not schema_columns:
        raise ReplayFixtureError(f"{path}: schema_columns 必须是非空字符串数组")
    for column in schema_columns:
        if not isinstance(column, str) or not column.strip():
            raise ReplayFixtureError(f"{path}: schema_columns 只能包含非空字符串")

    stop_contract = data["stop_contract"]
    if not isinstance(stop_contract, dict):
        raise ReplayFixtureError(f"{path}: stop_contract 必须是 JSON object")

    windows = data["windows"]
    if not isinstance(windows, list) or not windows:
        raise ReplayFixtureError(f"{path}: windows 必须是非空数组")
    for index, window in enumerate(windows):
        if not isinstance(window, dict):
            raise ReplayFixtureError(f"{path}: windows[{index}] 必须是 JSON object")
        for window_field in _REQUIRED_WINDOW_FIELDS:
            if window_field not in window:
                raise ReplayFixtureError(
                    f"{path}: windows[{index}] 缺少必需字段 '{window_field}'"
                )
            if not isinstance(window[window_field], str):
                raise ReplayFixtureError(
                    f"{path}: windows[{index}].{window_field} 必须是字符串"
                )
        if not window["window_id"].strip():
            raise ReplayFixtureError(f"{path}: windows[{index}].window_id 不能为空")

    provider_response_raw = data["provider_response_raw"]
    if not isinstance(provider_response_raw, str):
        raise ReplayFixtureError(f"{path}: provider_response_raw 必须是字符串")

    expected = _load_expectation(path, data["expected"])

    return ReplayFixture(
        path=path,
        fixture_schema=fixture_schema,
        fixture_key=fixture_key,
        fixture_version=fixture_version,
        desensitized=True,
        provider=dict(provider),
        question=question,
        schema_columns=tuple(schema_columns),
        stop_contract=dict(stop_contract),
        windows=tuple(dict(window) for window in windows),
        provider_response_raw=provider_response_raw,
        expected=expected,
    )


def replay_extraction_fixture(fixture: ReplayFixture) -> ExtractionResult:
    """用 fixture 构造最小调用上下文并离线重放抽取。

    不读取环境变量、不访问网络：Provider 由 :class:`FakeLlmClient` 顶替，
    并按 fixture 的 ``provider.model`` 标注收据里的模型身份（纯元数据）。
    """

    plan = ResearchPlan(
        normalized_question=fixture.question,
        research_schema=ResearchSchema(
            columns=[
                ResearchColumn(key=column, label=column, required=True)
                for column in fixture.schema_columns
            ]
        ),
        stop_contract=dict(fixture.stop_contract),
    )
    read_windows = [_build_read_window(window) for window in fixture.windows]
    task_input = ResearchTaskInput(
        task_id="replay-task",
        workspace_id="replay-workspace",
        target_id=fixture.fixture_key,
        control_pack=ControlPack(
            pack_type="research",
            target_key=fixture.fixture_key,
            task_neighborhood="REPLAY_FIXTURE",
        ),
        input_payload=ResearchTaskInputPayload(
            question=fixture.question,
            profile_key="REPLAY",
        ),
    )
    llm_client = FakeLlmClient({"research.extract": fixture.provider_response_raw})
    # FakeLlmClient 默认没有 model 属性；补上脱敏模型名，让 provider 收据可读。
    llm_client.model = str(fixture.provider.get("model") or "")
    return extract_evidence_cards_detailed(
        task_input,
        plan,
        read_windows,
        llm_client=llm_client,
    )


def verify_replay_fixture(fixture: ReplayFixture) -> ExtractionResult:
    """重放并比对 ``expected``；不一致时抛出 :class:`ReplayExpectationError`。"""

    result = replay_extraction_fixture(fixture)
    mismatches = _expectation_mismatches(fixture, result)
    if mismatches:
        raise ReplayExpectationError(
            f"fixture {fixture.fixture_key} 重放结果不符合 expected: "
            + "; ".join(mismatches)
        )
    return result


def replay_directory(path: Path) -> list[dict[str, object]]:
    """重放目录下所有 ``*.json`` fixture，返回稳定排序的摘要列表。"""

    directory = Path(path)
    if not directory.is_dir():
        raise ReplayFixtureError(f"{directory}: fixture 目录不存在")

    summaries = [
        _summarize_fixture(fixture_path)
        for fixture_path in sorted(directory.glob("*.json"), key=lambda item: item.name)
    ]
    summaries.sort(
        key=lambda item: (
            str(item.get("fixture_key") or ""),
            str(item.get("fixture_file") or ""),
        )
    )
    return summaries


def main(argv: list[str] | None = None) -> int:
    """CLI 入口：``python -m app.extraction_replay --fixtures <dir> [--json]``。"""

    parser = argparse.ArgumentParser(
        prog="python -m app.extraction_replay",
        description="离线重放脱敏的 research.extract fixture（DR-104）。",
    )
    parser.add_argument(
        "--fixtures",
        required=True,
        help="包含 *.json fixture 的目录",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="以 JSON 输出每个 fixture 的摘要",
    )
    args = parser.parse_args(argv)

    try:
        summaries = replay_directory(Path(args.fixtures))
    except ReplayFixtureError as exc:
        print(f"ERROR: {exc}")
        return 2

    if not summaries:
        print(f"ERROR: 目录 {args.fixtures} 下没有 *.json fixture")
        return 1

    if args.json:
        print(json.dumps(summaries, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        for summary in summaries:
            print(
                f"[{summary['status']}] {summary['fixture_key'] or summary['fixture_file']} "
                f"termination_reason={summary['termination_reason'] or 'N/A'} "
                f"accepted_count={summary['accepted_count']} "
                f"rejection_counts="
                f"{json.dumps(summary['rejection_counts'], ensure_ascii=False, sort_keys=True)}"
            )
            if summary["error"]:
                print(f"    error: {summary['error']}")

    failed = [summary for summary in summaries if summary["status"] != "PASS"]
    return 1 if failed else 0


def _load_expectation(path: Path, raw_expected: object) -> ReplayExpectation:
    if not isinstance(raw_expected, dict):
        raise ReplayFixtureError(f"{path}: expected 必须是 JSON object")
    for field_name in _REQUIRED_EXPECTED_FIELDS:
        if field_name not in raw_expected:
            raise ReplayFixtureError(f"{path}: expected 缺少必需字段 '{field_name}'")

    termination_reason = raw_expected["termination_reason"]
    if termination_reason not in _TERMINATION_VALUES:
        raise ReplayFixtureError(
            f"{path}: expected.termination_reason={termination_reason!r} 不在 "
            f"ExtractionTerminationReason 词表 {sorted(_TERMINATION_VALUES)} 中"
        )

    accepted_count = raw_expected["accepted_count"]
    if not _is_int(accepted_count) or accepted_count < 0:
        raise ReplayFixtureError(
            f"{path}: expected.accepted_count 必须是 >=0 的整数"
        )

    raw_counts = raw_expected["rejection_counts"]
    if not isinstance(raw_counts, dict):
        raise ReplayFixtureError(f"{path}: expected.rejection_counts 必须是 JSON object")
    rejection_counts: dict[str, int] = {}
    for reason, count in raw_counts.items():
        if reason not in _FAILURE_VALUES:
            raise ReplayFixtureError(
                f"{path}: expected.rejection_counts 键 {reason!r} 不在 "
                f"ExtractionFailureReason 词表 {sorted(_FAILURE_VALUES)} 中"
            )
        if not _is_int(count) or count < 0:
            raise ReplayFixtureError(
                f"{path}: expected.rejection_counts[{reason!r}] 必须是 >=0 的整数"
            )
        rejection_counts[str(reason)] = int(count)

    return ReplayExpectation(
        termination_reason=str(termination_reason),
        accepted_count=int(accepted_count),
        rejection_counts=rejection_counts,
    )


def _build_read_window(window: dict[str, object]) -> ResearchReadWindow:
    window_text = str(window["window_text"])
    return ResearchReadWindow(
        window_id=str(window["window_id"]),
        hit_id=str(window.get("hit_id") or window["window_id"]),
        source_id=str(window["source_id"]),
        source_title=str(window["source_title"]),
        query=str(window["query"]),
        read_focus=str(window["read_focus"]),
        window_text=window_text,
        retention_reason=str(window.get("retention_reason") or "replay fixture window"),
        token_estimate=int(window.get("token_estimate") or max(1, len(window_text) // 4)),
        adapter=str(window.get("adapter") or "workspace"),
        snapshot_status=str(window.get("snapshot_status") or "WORKSPACE"),
        url=str(window.get("url") or ""),
        provider=str(window.get("provider") or ""),
        source_domain=str(window.get("source_domain") or ""),
        source_quality=str(window.get("source_quality") or "GENERAL_WEB"),
    )


def _expectation_mismatches(
    fixture: ReplayFixture,
    result: ExtractionResult,
) -> list[str]:
    """返回实际值与 expected 的所有偏差描述（空列表表示完全一致）。"""

    mismatches: list[str] = []
    actual_reason = result.termination_reason.value
    if actual_reason != fixture.expected.termination_reason:
        mismatches.append(
            "termination_reason "
            f"expected={fixture.expected.termination_reason} actual={actual_reason}"
        )
    if result.accepted_count != fixture.expected.accepted_count:
        mismatches.append(
            "accepted_count "
            f"expected={fixture.expected.accepted_count} actual={result.accepted_count}"
        )
    actual_counts = result.rejection_counts()
    if actual_counts != dict(fixture.expected.rejection_counts):
        mismatches.append(
            "rejection_counts "
            f"expected={dict(fixture.expected.rejection_counts)} actual={actual_counts}"
        )
    return mismatches


def _summarize_fixture(fixture_path: Path) -> dict[str, object]:
    """重放单个 fixture 并生成机器可读摘要；失败时记录原因而不抛异常。"""

    summary: dict[str, object] = {
        "fixture_file": fixture_path.name,
        "fixture_key": "",
        "termination_reason": "",
        "accepted_count": 0,
        "rejection_counts": {},
        "expected": {},
        "status": "FAIL",
        "error": "",
    }

    try:
        fixture = load_replay_fixture(fixture_path)
    except ReplayFixtureError as exc:
        summary["error"] = f"LOAD_ERROR: {exc}"
        return summary

    summary["fixture_key"] = fixture.fixture_key
    summary["expected"] = {
        "termination_reason": fixture.expected.termination_reason,
        "accepted_count": fixture.expected.accepted_count,
        "rejection_counts": dict(fixture.expected.rejection_counts),
    }
    result = replay_extraction_fixture(fixture)
    summary["termination_reason"] = result.termination_reason.value
    summary["accepted_count"] = result.accepted_count
    summary["rejection_counts"] = result.rejection_counts()

    mismatches = _expectation_mismatches(fixture, result)
    if mismatches:
        summary["error"] = "EXPECTATION_MISMATCH: " + "; ".join(mismatches)
        return summary

    summary["status"] = "PASS"
    return summary


def _is_int(value: object) -> bool:
    """布尔是 int 的子类，这里把 bool 排除，避免伪造布尔计数。"""

    return isinstance(value, int) and not isinstance(value, bool)


if __name__ == "__main__":  # pragma: no cover - CLI 入口
    raise SystemExit(main())
