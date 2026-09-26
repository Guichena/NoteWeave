"""DR-104：脱敏 replay fixture 的离线重放回归测试。

覆盖三类验收：

1. 每个 fixture 都能被加载并验证通过（阶段「同一 fixture 产生稳定判断」）；
2. 同一 fixture 连续两次重放的 ``diagnostics()`` 完全一致（稳定性直接证据）；
3. 脱敏自检：fixture 中不得出现真实 URL；未脱敏 fixture 必须被拒绝。
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from app.extraction_replay import (
    ReplayExpectationError,
    ReplayFixtureError,
    load_replay_fixture,
    replay_directory,
    replay_extraction_fixture,
    verify_replay_fixture,
)
from app.extraction_result import (  # noqa: F401 - 用于词表同源交叉断言
    ExtractionFailureReason,
    ExtractionTerminationReason,
)

#: <repo>/experiments/deep-research/replay-fixtures
FIXTURE_DIR = (
    Path(__file__).resolve().parents[3]
    / "experiments"
    / "deep-research"
    / "replay-fixtures"
)

_URL_PATTERN = re.compile(r"https?://")


def _fixture_paths() -> list[Path]:
    return sorted(FIXTURE_DIR.glob("*.json"), key=lambda item: item.name)


def _fixture_ids() -> list[str]:
    return [path.stem for path in _fixture_paths()]


def test_fixture_directory_exists_and_is_populated() -> None:
    assert FIXTURE_DIR.is_dir(), f"缺少 fixture 目录: {FIXTURE_DIR}"
    assert _fixture_paths(), "replay-fixtures 目录下没有任何 *.json fixture"


@pytest.mark.parametrize("fixture_path", _fixture_paths(), ids=_fixture_ids())
def test_each_fixture_loads_and_verifies(fixture_path: Path) -> None:
    fixture = load_replay_fixture(fixture_path)
    assert fixture.fixture_schema == "research-extraction-replay.v1"
    assert fixture.desensitized is True

    result = verify_replay_fixture(fixture)

    assert result.termination_reason.value == fixture.expected.termination_reason
    assert result.accepted_count == fixture.expected.accepted_count
    assert result.rejection_counts() == dict(fixture.expected.rejection_counts)


@pytest.mark.parametrize("fixture_path", _fixture_paths(), ids=_fixture_ids())
def test_replaying_fixture_twice_is_stable(fixture_path: Path) -> None:
    """同一 fixture 连续重放两次，诊断 JSON 必须逐字节相同。"""

    fixture = load_replay_fixture(fixture_path)
    first = replay_extraction_fixture(fixture).diagnostics()
    second = replay_extraction_fixture(fixture).diagnostics()

    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)


def test_fixture_directory_contains_no_real_urls() -> None:
    """脱敏硬要求：window_text 与 provider_response_raw 均不得出现 http(s) URL。"""

    for fixture_path in _fixture_paths():
        fixture = load_replay_fixture(fixture_path)
        assert not _URL_PATTERN.search(fixture.provider_response_raw), (
            f"{fixture_path.name}: provider_response_raw 含真实 URL"
        )
        for window in fixture.windows:
            assert not _URL_PATTERN.search(str(window["window_text"])), (
                f"{fixture_path.name}: window_text 含真实 URL"
            )


def test_load_rejects_non_desensitized_fixture(tmp_path: Path) -> None:
    """构造 desensitized=false 的 fixture，加载必须失败。"""

    payload = json.loads(_fixture_paths()[0].read_text(encoding="utf-8"))
    payload["desensitized"] = False
    bad_fixture = tmp_path / "extract-not-desensitized.json"
    bad_fixture.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(ReplayFixtureError):
        load_replay_fixture(bad_fixture)


def test_load_rejects_unknown_termination_reason(tmp_path: Path) -> None:
    """expected.termination_reason 不在词表内必须拒绝。"""

    payload = json.loads(_fixture_paths()[0].read_text(encoding="utf-8"))
    payload["expected"]["termination_reason"] = "TOTALLY_MADE_UP"
    bad_fixture = tmp_path / "extract-bad-termination.json"
    bad_fixture.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(ReplayFixtureError):
        load_replay_fixture(bad_fixture)


def test_load_rejects_unknown_rejection_reason(tmp_path: Path) -> None:
    """expected.rejection_counts 的键不在词表内必须拒绝。"""

    payload = json.loads(_fixture_paths()[0].read_text(encoding="utf-8"))
    payload["expected"]["rejection_counts"] = {"NOT_A_REAL_REASON": 1}
    bad_fixture = tmp_path / "extract-bad-rejection.json"
    bad_fixture.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(ReplayFixtureError):
        load_replay_fixture(bad_fixture)


def test_verify_reports_mismatch_with_fixture_key(tmp_path: Path) -> None:
    """expected 与实际不符时必须抛 ReplayExpectationError 且带 fixture_key。"""

    payload = json.loads(_fixture_paths()[0].read_text(encoding="utf-8"))
    payload["expected"]["accepted_count"] = 3
    bad_fixture = tmp_path / "extract-wrong-expectation.json"
    bad_fixture.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    fixture = load_replay_fixture(bad_fixture)
    with pytest.raises(ReplayExpectationError) as excinfo:
        verify_replay_fixture(fixture)

    message = str(excinfo.value)
    assert fixture.fixture_key in message
    assert "accepted_count" in message


def test_every_fixture_file_can_be_loaded() -> None:
    """覆盖率断言：目录下每个 *.json 都能加载，且 fixture_key 唯一。"""

    paths = _fixture_paths()
    assert len(paths) >= 4, "至少需要 4 个 fixture"

    keys: list[str] = []
    for fixture_path in paths:
        fixture = load_replay_fixture(fixture_path)
        assert fixture.fixture_key
        keys.append(fixture.fixture_key)

    assert len(set(keys)) == len(keys), f"fixture_key 重复: {keys}"


def test_replay_directory_reports_all_pass() -> None:
    """目录摘要：所有 fixture 状态为 PASS，且排序稳定。"""

    summaries = replay_directory(FIXTURE_DIR)

    assert len(summaries) == len(_fixture_paths())
    assert all(summary["status"] == "PASS" for summary in summaries), summaries

    ordered_keys = [
        (str(summary["fixture_key"]), str(summary["fixture_file"]))
        for summary in summaries
    ]
    assert ordered_keys == sorted(ordered_keys)


def test_fixture_vocabulary_is_sourced_from_extraction_enums() -> None:
    """交叉断言：fixture 校验用的词表必须与抽取契约枚举同源，防止漂移。"""

    from app.extraction_replay import _FAILURE_VALUES, _TERMINATION_VALUES

    assert _TERMINATION_VALUES == {reason.value for reason in ExtractionTerminationReason}
    assert _FAILURE_VALUES == {reason.value for reason in ExtractionFailureReason}
