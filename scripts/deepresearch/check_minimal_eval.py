"""校验 Deep Research 最小简历闭环的 8 题验收清单。

该脚本只校验评测定义和真实运行证据的完整性，不执行付费 Provider 调用。
PENDING_RUNTIME 是诚实状态；一旦标为 PASSED，必须同时提供 Run ID 和证据文件。
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = REPOSITORY_ROOT / "experiments/deep-research/minimal-eval/manifest.json"
DEFAULT_DATASET = REPOSITORY_ROOT / "experiments/deep-research/datasets/research-eval-v1.json"
SCHEMA_VERSION = "deep-research-minimal-eval.v1"
EXPECTED_SCENARIOS = {
    "NORMAL": 2,
    "MISSING": 2,
    "CONFLICT": 1,
    "WORKER_RECOVERY": 1,
    "CITATION_REJECTION": 1,
    "PROVIDER_FAILURE": 1,
}
RUNTIME_STATUSES = {"PENDING_RUNTIME", "PASSED", "FAILED"}
TERMINAL_STATES = {
    "COMPLETED_VERIFIED",
    "COMPLETED_WITH_LIMITATIONS",
    "INSUFFICIENT_EVIDENCE",
    "INFRASTRUCTURE_FAILURE",
}


class ManifestError(RuntimeError):
    """最小验收定义或运行证据不满足闭环要求。"""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ManifestError(message)


def _non_blank(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def validate(manifest: dict[str, object], dataset: dict[str, object]) -> dict[str, object]:
    _require(manifest.get("schema_version") == SCHEMA_VERSION, "unexpected schema_version")
    for field in ("suite_key", "suite_version", "purpose"):
        _require(_non_blank(manifest.get(field)), f"manifest.{field} must be non-blank")

    source_cases = {
        str(case.get("case_key")): case
        for case in dataset.get("cases", [])
        if isinstance(case, dict) and _non_blank(case.get("case_key"))
    }
    cases = manifest.get("cases")
    _require(isinstance(cases, list) and len(cases) == 8, "manifest must contain exactly 8 cases")

    seen: set[str] = set()
    scenarios: Counter[str] = Counter()
    pending = 0
    passed = 0
    for case in cases:
        _require(isinstance(case, dict), "each case must be an object")
        case_key = str(case.get("case_key") or "").strip()
        _require(case_key and case_key not in seen, f"duplicate or blank case_key: {case_key!r}")
        seen.add(case_key)
        scenario = str(case.get("scenario") or "").strip()
        scenarios[scenario] += 1
        _require(_non_blank(case.get("question")), f"{case_key}: question must be non-blank")
        source_key = str(case.get("source_case_key") or "").strip()
        _require(source_key in source_cases, f"{case_key}: unknown source_case_key {source_key!r}")

        expected = case.get("expected")
        _require(isinstance(expected, dict), f"{case_key}: expected must be an object")
        allowed_states = expected.get("allowed_terminal_states")
        _require(
            isinstance(allowed_states, list)
            and allowed_states
            and set(map(str, allowed_states)) <= TERMINAL_STATES,
            f"{case_key}: invalid allowed_terminal_states",
        )
        checks = expected.get("required_checks")
        _require(
            isinstance(checks, list) and checks and all(_non_blank(item) for item in checks),
            f"{case_key}: required_checks must be a non-empty string array",
        )

        runtime = case.get("runtime")
        _require(isinstance(runtime, dict), f"{case_key}: runtime must be an object")
        status = str(runtime.get("status") or "")
        _require(status in RUNTIME_STATUSES, f"{case_key}: invalid runtime status {status!r}")
        if status == "PENDING_RUNTIME":
            pending += 1
            _require(runtime.get("run_id") is None, f"{case_key}: pending run_id must be null")
        else:
            _require(_non_blank(runtime.get("run_id")), f"{case_key}: {status} requires run_id")
            _require(_non_blank(runtime.get("evidence_file")), f"{case_key}: {status} requires evidence_file")
            if status == "PASSED":
                passed += 1
                _require(
                    runtime.get("terminal_state") in allowed_states,
                    f"{case_key}: terminal_state is not allowed",
                )

    actual_scenarios = dict(sorted(scenarios.items()))
    _require(actual_scenarios == dict(sorted(EXPECTED_SCENARIOS.items())),
             f"scenario counts drifted: {actual_scenarios}")
    return {
        "suite_key": manifest.get("suite_key"),
        "suite_version": manifest.get("suite_version"),
        "case_count": len(cases),
        "scenario_counts": actual_scenarios,
        "pending_runtime_count": pending,
        "passed_count": passed,
        "resume_claim_ready": passed == 8,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    args = parser.parse_args(argv)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    summary = validate(manifest, dataset)
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ManifestError, FileNotFoundError, json.JSONDecodeError) as error:
        sys.stderr.write(f"check_minimal_eval failed: {error}\n")
        raise SystemExit(2)
