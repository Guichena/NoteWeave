"""运行 research-quality-v1 评测集并保存每个 Run 的原始记录。

默认只打印计划；传入 --execute 才会创建工作台并产生真实 Provider 调用。
记录保存在 experiments/deep-research/quality-eval/runs/，由 score_quality_eval.py 打分。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_minimal_eval import (  # noqa: E402
    ApiClient,
    EvalRunError,
    authenticate,
    capture_optional_endpoint,
    wait_for_terminal,
)

ROOT = Path(__file__).resolve().parents[2]
EVAL_DIR = ROOT / "experiments/deep-research/quality-eval"
DATASET = EVAL_DIR / "dataset.json"
RUNS_DIR = EVAL_DIR / "runs"
# 除 Run 详情外需要保存的附属接口；后端未实现的接口记为 available=false，打分时按缺失处理。
RUN_RESOURCES = ("plan", "cells", "counter-checks", "replans", "discovery-proposals")


def selected_cases(dataset: dict[str, object], requested: set[str]) -> list[dict[str, object]]:
    cases = [case for case in dataset["cases"] if isinstance(case, dict)]
    unknown = requested - {str(case["case_key"]) for case in cases}
    if unknown:
        raise EvalRunError(f"unknown case keys: {sorted(unknown)}")
    return [case for case in cases if not requested or str(case["case_key"]) in requested]


def execute_case(client: ApiClient, workspace_id: str, case: dict[str, object], dataset_version: str,
                 timeout_seconds: int, poll_seconds: int) -> Path:
    created = client.request("POST", f"/api/v2/workspaces/{workspace_id}/research-runs", {
        "question": case["question"],
        "profile": "default",
        "research_goal": "形成可逐项引用的研究结论，证据不足时说明缺口",
        "deliverable_format": "Markdown research report with citation audit",
        "constraints": ["正式结论必须有 exact quote、snapshot 和 URL"],
        "time_range": "current",
        "depth": "STANDARD",
        "research_type": "AUTO",
        "retrieval_mode": "WEB_ONLY",
        "seed_source_ids": [],
        "source_scope_source_ids": [],
    })
    run_id = str(created["research_run_id"])
    started = datetime.now(timezone.utc)
    return capture_run(client, workspace_id, case, dataset_version, run_id, started,
                       timeout_seconds, poll_seconds)


def capture_run(client: ApiClient, workspace_id: str, case: dict[str, object], dataset_version: str,
                run_id: str, started: datetime, timeout_seconds: int, poll_seconds: int) -> Path:
    """等待 Run 结束并保存记录；也用于接上评测脚本中断后仍在运行的 Run。"""
    detail = wait_for_terminal(client, workspace_id, run_id, timeout_seconds, poll_seconds)
    finished = datetime.now(timezone.utc)
    base = f"/api/v2/workspaces/{workspace_id}/research-runs/{run_id}"
    payload = {
        "schema_version": "research-quality-run.v1",
        "dataset_version": dataset_version,
        "case_key": case["case_key"],
        "run_id": run_id,
        "workspace_id": workspace_id,
        "started_at": started.isoformat(),
        "finished_at": finished.isoformat(),
        "wall_seconds": round((finished - started).total_seconds(), 1),
        "run_detail": detail,
        "resources": {name: capture_optional_endpoint(client, f"{base}/{name}") for name in RUN_RESOURCES},
    }
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    output = RUNS_DIR / f"{case['case_key']}.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return output


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:3000")
    parser.add_argument("--case", action="append", default=[], dest="case_keys")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=1200)
    parser.add_argument("--poll-seconds", type=int, default=5)
    parser.add_argument(
        "--attach", default="",
        help="接上已存在的 Run 并保存记录，格式 case_key:workspace_id:run_id:started_at（ISO 时间），不新建 Run",
    )
    args = parser.parse_args(argv)
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    cases = selected_cases(dataset, set(args.case_keys))
    if not args.execute:
        print(json.dumps({"mode": "DRY_RUN", "cases": [case["case_key"] for case in cases]},
                         ensure_ascii=False, indent=2))
        return 0

    username = os.environ.get("NOTEWEAVE_EVAL_USERNAME", "").strip()
    email = os.environ.get("NOTEWEAVE_EVAL_EMAIL", "").strip()
    password = os.environ.get("NOTEWEAVE_EVAL_PASSWORD", "")
    if not username or not email or not password:
        raise EvalRunError("set NOTEWEAVE_EVAL_USERNAME, NOTEWEAVE_EVAL_EMAIL and NOTEWEAVE_EVAL_PASSWORD")
    client = ApiClient(args.base_url)
    authenticate(client, username, email, password)
    if args.attach:
        case_key, workspace_id, run_id, started_at = args.attach.split(":", 3)
        case = selected_cases(dataset, {case_key})[0]
        output = capture_run(client, workspace_id, case, str(dataset["dataset_version"]), run_id,
                             datetime.fromisoformat(started_at), args.timeout_seconds, args.poll_seconds)
        print(json.dumps({"workspace_id": workspace_id, "run_files": [str(output.relative_to(ROOT))]},
                         ensure_ascii=False, indent=2))
        return 0
    workspace = client.request("POST", "/api/v2/workspaces", {
        "name": f"Research quality eval {datetime.now(timezone.utc):%Y%m%d-%H%M%S}",
        "description": "research-quality-v1 automated evaluation workspace",
    })
    workspace_id = str(workspace["workspace_id"])
    outputs = [
        str(execute_case(client, workspace_id, case, str(dataset["dataset_version"]),
                         args.timeout_seconds, args.poll_seconds).relative_to(ROOT))
        for case in cases
    ]
    print(json.dumps({"workspace_id": workspace_id, "run_files": outputs}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvalRunError, KeyError, ValueError) as error:
        sys.stderr.write(f"run_quality_eval failed: {error}\n")
        raise SystemExit(2)
