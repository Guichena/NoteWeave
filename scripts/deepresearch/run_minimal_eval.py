"""启动可直接执行的 Deep Research 最小验收题并保存公开 API 证据。

默认只打印计划；传入 --execute 才会创建用户/工作台并产生真实 Provider 调用。
故障注入题不会被该脚本伪装执行，它们必须按 DEMO.md 的注入步骤单独完成。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "experiments/deep-research/minimal-eval/manifest.json"
RUNS_DIR = ROOT / "experiments/deep-research/minimal-eval/runs"
DIRECT_SCENARIOS = {"NORMAL", "MISSING", "CONFLICT"}
TERMINAL_RUN_STATUSES = {"COMPLETED", "FAILED", "CANCELLED"}


class EvalRunError(RuntimeError):
    pass


def selected_cases(
    manifest: dict[str, object], requested: set[str], *, allow_fault_injection: bool = False
) -> list[dict[str, object]]:
    cases = [case for case in manifest["cases"] if isinstance(case, dict)]
    unknown = requested - {str(case["case_key"]) for case in cases}
    if unknown:
        raise EvalRunError(f"unknown case keys: {sorted(unknown)}")
    chosen = [
        case for case in cases
        if (str(case["case_key"]) in requested if requested else case.get("scenario") in DIRECT_SCENARIOS)
    ]
    blocked = [
        str(case["case_key"]) for case in chosen
        if case.get("scenario") not in DIRECT_SCENARIOS and not allow_fault_injection
    ]
    if blocked:
        raise EvalRunError(
            "fault-injection cases require the documented manual injection path: " + ", ".join(blocked)
        )
    return chosen


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.token: str | None = None

    def request(self, method: str, path: str, body: dict[str, object] | None = None) -> dict[str, object]:
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:1000]
            raise EvalRunError(f"{method} {path} failed ({error.code}): {detail}") from error
        except urllib.error.URLError as error:
            raise EvalRunError(f"{method} {path} unavailable: {error.reason}") from error
        if not payload.get("success"):
            raise EvalRunError(f"{method} {path} returned {payload.get('code')}: {payload.get('message')}")
        return payload["data"]


def authenticate(client: ApiClient, username: str, email: str, password: str) -> None:
    try:
        session = client.request("POST", "/api/v2/auth/login", {"login": username, "password": password})
    except EvalRunError as login_error:
        if not any(marker in str(login_error) for marker in ("401", "INVALID_CREDENTIALS", "NOT_FOUND")):
            raise
        session = client.request("POST", "/api/v2/auth/register", {
            "username": username,
            "email": email,
            "password": password,
            "display_name": "Deep Research Minimal Eval",
        })
    client.token = str(session["access_token"])


def wait_for_terminal(client: ApiClient, workspace_id: str, run_id: str,
                      timeout_seconds: int, poll_seconds: int) -> dict[str, object]:
    deadline = time.monotonic() + timeout_seconds
    while True:
        detail = client.request("GET", f"/api/v2/workspaces/{workspace_id}/research-runs/{run_id}")
        if detail.get("status") in TERMINAL_RUN_STATUSES:
            return detail
        if time.monotonic() >= deadline:
            raise EvalRunError(f"run {run_id} did not reach terminal status in {timeout_seconds}s")
        time.sleep(poll_seconds)


def capture_optional_endpoint(client: ApiClient, path: str) -> dict[str, object]:
    """Preserve an unavailable legacy projection as evidence instead of losing the Run."""
    try:
        return {"available": True, "data": client.request("GET", path)}
    except EvalRunError as error:
        return {"available": False, "error": str(error)}


def execute_case(client: ApiClient, workspace_id: str, case: dict[str, object],
                 timeout_seconds: int, poll_seconds: int) -> Path:
    constraints = ["正式结论必须有 exact quote、snapshot 和 URL"]
    allowed_domains = case.get("allowed_source_domains")
    if isinstance(allowed_domains, list) and allowed_domains:
        constraints.append("SOURCE_DOMAIN_ALLOWLIST: " + ",".join(str(item) for item in allowed_domains))
    created = client.request("POST", f"/api/v2/workspaces/{workspace_id}/research-runs", {
        "question": case["question"],
        "profile": "default",
        "research_goal": "形成可逐项引用、证据不足时诚实降级的研究报告",
        "deliverable_format": "Markdown research report with citation audit",
        "constraints": constraints,
        "time_range": "current",
        "depth": "STANDARD",
        "research_type": "AUTO",
        "retrieval_mode": "WEB_ONLY",
        "seed_source_ids": [],
        "source_scope_source_ids": [],
    })
    run_id = str(created["research_run_id"])
    detail = wait_for_terminal(client, workspace_id, run_id, timeout_seconds, poll_seconds)
    evidence = capture_optional_endpoint(
        client, f"/api/v2/workspaces/{workspace_id}/research-runs/{run_id}/evidence"
    )
    collection = capture_optional_endpoint(
        client, f"/api/v2/workspaces/{workspace_id}/research-runs/{run_id}/collection"
    )
    payload = {
        "schema_version": "deep-research-minimal-eval-public-evidence.v1",
        "case_key": case["case_key"],
        "run_id": run_id,
        "workspace_id": workspace_id,
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "truth_label": "生产待验证",
        "note": "公开 API 原始证据已采集；仍须按 manifest required_checks 审计后才能标记 PASSED。",
        "run_detail": detail,
        "evidence_manifest_capture": evidence,
        "collection_capture": collection,
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
    parser.add_argument(
        "--allow-fault-injection", action="store_true",
        help="explicitly allow a fault case after its documented runtime fault has been enabled",
    )
    parser.add_argument("--timeout-seconds", type=int, default=900)
    parser.add_argument("--poll-seconds", type=int, default=5)
    args = parser.parse_args(argv)
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    cases = selected_cases(
        manifest, set(args.case_keys), allow_fault_injection=args.allow_fault_injection
    )
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
    workspace = client.request("POST", "/api/v2/workspaces", {
        "name": f"Deep Research minimal eval {datetime.now(timezone.utc):%Y%m%d-%H%M%S}",
        "description": "Automated workspace for the 8-case minimal resume claim evaluation",
    })
    workspace_id = str(workspace["workspace_id"])
    outputs = [str(execute_case(client, workspace_id, case, args.timeout_seconds, args.poll_seconds).relative_to(ROOT))
               for case in cases]
    print(json.dumps({"workspace_id": workspace_id, "evidence_files": outputs}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvalRunError, KeyError, ValueError) as error:
        sys.stderr.write(f"run_minimal_eval failed: {error}\n")
        raise SystemExit(2)
