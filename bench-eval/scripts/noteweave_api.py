#!/usr/bin/env python3
"""Prepared API import/export helpers for NoteWeave benchmark execution.

Default mode is dry-run. Use --execute only when the business code is ready and
you want these benchmark files imported into the live NoteWeave app.
"""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def iter_jsonl(path: Path) -> Iterable[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSONL row: {exc}") from exc
            if not isinstance(row, dict):
                raise SystemExit(f"{path}:{line_no}: expected object row")
            yield row


def load_profile(profiles_path: Path, profile_id: str) -> dict[str, Any]:
    profiles = read_json(profiles_path)
    for profile in profiles.get("profiles", []):
        if profile.get("id") == profile_id:
            return profile
    raise SystemExit(f"profile not found: {profile_id}")


def load_token(env_name: str) -> str:
    token = os.getenv(env_name)
    if not token:
        raise SystemExit(f"missing auth token env: {env_name}")
    return token


def request_json(method: str, url: str, token: str | None, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    body = None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        raise SystemExit(f"HTTP {exc.code} for {method} {url}: {detail}") from exc


def build_case_payload(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "name": row.get("name") or f"{row.get('suite')}::{row.get('case_id')}",
        "queryText": row.get("query"),
        "expectedAnswer": row.get("expected_answer"),
        "expectedSourceJson": row.get("expectedSourceJson") or json.dumps(row.get("expected_sources") or [], ensure_ascii=False),
        "tagsJson": row.get("tagsJson") or json.dumps(row.get("tags") or {}, ensure_ascii=False),
        "enabled": True,
    }


def dry_run_result(profile_id: str, count: int, items: list[dict[str, Any]]) -> dict[str, Any]:
    return {"profile": profile_id, "dry_run": True, "case_count": count, "items": items}


def command_import_cases(args: argparse.Namespace) -> None:
    profile = load_profile(args.profiles, args.profile_id)
    base_url = profile["base_url"].rstrip("/")
    space_id = profile["space_id"]
    token_env = profile.get("auth_token_env", "NOTEWEAVE_TOKEN")
    token = load_token(token_env) if args.execute else None
    rows = list(iter_jsonl(args.input))
    items = []
    for row in rows:
        payload = build_case_payload(row)
        if args.execute:
            url = f"{base_url}/api/v1/admin/spaces/{space_id}/rag-eval-cases"
            response = request_json("POST", url, token, payload)
            items.append({"name": payload["name"], "status": "imported", "response_id": response.get("data", {}).get("id") if isinstance(response, dict) else None})
        else:
            items.append({"name": payload["name"], "status": "dry_run"})
    result = dry_run_result(args.profile_id, len(rows), items)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))


def command_start_run(args: argparse.Namespace) -> None:
    profile = load_profile(args.profiles, args.profile_id)
    base_url = profile["base_url"].rstrip("/")
    space_id = profile["space_id"]
    token_env = profile.get("auth_token_env", "NOTEWEAVE_TOKEN")
    token = load_token(token_env) if args.execute else None
    payload = {"name": args.run_name}
    if args.execute:
        url = f"{base_url}/api/v1/admin/spaces/{space_id}/rag-eval-runs"
        response = request_json("POST", url, token, payload)
        output = {"profile": args.profile_id, "dry_run": False, "run_name": args.run_name, "response": response}
    else:
        output = {"profile": args.profile_id, "dry_run": True, "run_name": args.run_name, "request": payload}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        print(json.dumps(output, ensure_ascii=False, indent=2))


def command_collect_run(args: argparse.Namespace) -> None:
    profile = load_profile(args.profiles, args.profile_id)
    base_url = profile["base_url"].rstrip("/")
    space_id = profile["space_id"]
    token_env = profile.get("auth_token_env", "NOTEWEAVE_TOKEN")
    token = load_token(token_env) if args.execute else None
    if args.execute:
        run = request_json("GET", f"{base_url}/api/v1/admin/rag-eval-runs/{args.run_id}", token)
        results = request_json("GET", f"{base_url}/api/v1/admin/rag-eval-runs/{args.run_id}/results", token)
        output = {"profile": args.profile_id, "dry_run": False, "run": run, "results": results, "space_id": space_id}
    else:
        output = {
            "profile": args.profile_id,
            "dry_run": True,
            "run_id": args.run_id,
            "requests": [
                f"GET /api/v1/admin/rag-eval-runs/{args.run_id}",
                f"GET /api/v1/admin/rag-eval-runs/{args.run_id}/results",
            ],
            "space_id": space_id,
        }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        print(json.dumps(output, ensure_ascii=False, indent=2))


def command_plan(args: argparse.Namespace) -> None:
    profile = load_profile(args.profiles, args.profile_id)
    case_file = profile.get("case_file")
    case_count = sum(1 for _ in iter_jsonl(Path(case_file))) if case_file else 0
    output = {
        "profile": args.profile_id,
        "dry_run": True,
        "base_url": profile["base_url"],
        "space_id": profile["space_id"],
        "case_file": case_file,
        "case_count": case_count,
        "run_after_import": profile.get("run_after_import", False),
        "notes": profile.get("notes"),
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        print(json.dumps(output, ensure_ascii=False, indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Prepared NoteWeave API benchmark helpers")
    parser.add_argument("--profiles", type=Path, default=ROOT / "configs" / "import_profiles.json")
    sub = parser.add_subparsers(dest="command", required=True)

    plan = sub.add_parser("plan", help="Preview what a profile would import")
    plan.add_argument("--profile-id", required=True)
    plan.add_argument("--output", type=Path)
    plan.set_defaults(func=command_plan)

    imp = sub.add_parser("import-cases", help="Import rag-eval cases from a JSONL file")
    imp.add_argument("--profile-id", required=True)
    imp.add_argument("--input", type=Path, required=True)
    imp.add_argument("--execute", action="store_true")
    imp.add_argument("--output", type=Path)
    imp.set_defaults(func=command_import_cases)

    start = sub.add_parser("start-run", help="Start a rag-eval run from a profile")
    start.add_argument("--profile-id", required=True)
    start.add_argument("--run-name", required=True)
    start.add_argument("--execute", action="store_true")
    start.add_argument("--output", type=Path)
    start.set_defaults(func=command_start_run)

    collect = sub.add_parser("collect-run", help="Collect run and result data")
    collect.add_argument("--profile-id", required=True)
    collect.add_argument("--run-id", required=True)
    collect.add_argument("--execute", action="store_true")
    collect.add_argument("--output", type=Path)
    collect.set_defaults(func=command_collect_run)

    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
