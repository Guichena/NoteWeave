"""DR-002：校验固定评测集的版本、分层与机器可检查断言。

评测集一旦被引用就不允许静默漂移，因此结构错误必须让门禁失败，而不是打印警告。

用法：
    python scripts/deepresearch/check_eval_dataset.py
    python scripts/deepresearch/check_eval_dataset.py --dataset <path>
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = (
    REPOSITORY_ROOT / "experiments/deep-research/datasets/research-eval-v1.json"
)

DATASET_SCHEMA = "research-eval-dataset.v1"
TERMINAL_STATES = {
    "COMPLETED_VERIFIED",
    "COMPLETED_WITH_LIMITATIONS",
    "INSUFFICIENT_EVIDENCE",
    "INFRASTRUCTURE_FAILURE",
}
MIN_CASES = 20
MAX_CASES = 30


class DatasetError(RuntimeError):
    """数据集不满足 DR-002 退出条件。"""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise DatasetError(message)


def validate(dataset: dict[str, object]) -> dict[str, object]:
    _require(dataset.get("schema_version") == DATASET_SCHEMA, "unexpected dataset schema_version")
    for field in ("dataset_key", "dataset_version", "frozen_at", "purpose"):
        _require(
            isinstance(dataset.get(field), str) and str(dataset[field]).strip(),
            f"dataset.{field} must be non-blank",
        )

    categories = dataset.get("categories")
    _require(isinstance(categories, dict) and categories, "dataset.categories must be a non-empty object")
    vocabulary = dataset.get("machine_check_vocabulary")
    _require(
        isinstance(vocabulary, list) and vocabulary,
        "dataset.machine_check_vocabulary must be a non-empty array",
    )
    vocabulary_set = {str(item) for item in vocabulary}

    cases = dataset.get("cases")
    _require(isinstance(cases, list), "dataset.cases must be an array")
    _require(
        MIN_CASES <= len(cases) <= MAX_CASES,
        f"dataset must contain between {MIN_CASES} and {MAX_CASES} cases, found {len(cases)}",
    )

    seen_keys: set[str] = set()
    category_counts: dict[str, int] = {}
    for case in cases:
        _require(isinstance(case, dict), "each case must be an object")
        case_key = str(case.get("case_key") or "").strip()
        _require(bool(case_key), "case_key must be non-blank")
        _require(case_key not in seen_keys, f"duplicate case_key: {case_key}")
        seen_keys.add(case_key)

        category = str(case.get("category") or "").strip()
        _require(category in categories, f"{case_key}: unknown category {category!r}")
        category_counts[category] = category_counts.get(category, 0) + 1

        _require(bool(str(case.get("question") or "").strip()), f"{case_key}: question is blank")
        columns = case.get("schema_columns")
        _require(
            isinstance(columns, list) and columns and all(str(item).strip() for item in columns),
            f"{case_key}: schema_columns must be a non-empty string array",
        )

        expected = case.get("expected")
        _require(isinstance(expected, dict), f"{case_key}: expected must be an object")
        terminal_state = str(expected.get("terminal_state") or "").strip()
        _require(
            terminal_state in TERMINAL_STATES,
            f"{case_key}: terminal_state {terminal_state!r} is not in {sorted(TERMINAL_STATES)}",
        )
        checks = expected.get("machine_checks")
        _require(
            isinstance(checks, list) and checks,
            f"{case_key}: expected.machine_checks must be a non-empty array",
        )
        unknown = sorted({str(item) for item in checks} - vocabulary_set)
        _require(not unknown, f"{case_key}: machine_checks not in vocabulary: {unknown}")

        fault = case.get("fault_injection")
        if category == "FAULT":
            _require(isinstance(fault, dict), f"{case_key}: FAULT case requires fault_injection")
            _require(
                bool(str(fault.get("point") or "").strip()),
                f"{case_key}: fault_injection.point must be non-blank",
            )
            _require(
                bool(str(fault.get("expected_behavior") or "").strip()),
                f"{case_key}: fault_injection.expected_behavior must be non-blank",
            )
        else:
            _require(fault is None, f"{case_key}: only FAULT cases may declare fault_injection")

    for category in categories:
        _require(
            category_counts.get(category, 0) > 0,
            f"category {category} has no case; 分层评测集不允许空类别",
        )

    return {
        "dataset_key": dataset.get("dataset_key"),
        "dataset_version": dataset.get("dataset_version"),
        "case_count": len(cases),
        "category_counts": dict(sorted(category_counts.items())),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    args = parser.parse_args(argv)

    if not args.dataset.is_file():
        raise DatasetError(f"dataset not found: {args.dataset}")
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    summary = validate(dataset)
    summary["dataset"] = str(args.dataset.relative_to(REPOSITORY_ROOT))
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DatasetError as error:
        sys.stderr.write(f"check_eval_dataset failed: {error}\n")
        raise SystemExit(2)
