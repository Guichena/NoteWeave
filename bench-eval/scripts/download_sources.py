#!/usr/bin/env python3
"""Prepared benchmark source download planner.

Default mode is dry-run. It prints the download plan defined in
`configs/downloads.json` and only fetches files when --execute is supplied.
"""

from __future__ import annotations

import argparse
import json
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def fetch(url: str, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url, timeout=120) as response:
        output.write_bytes(response.read())


def command_plan(args: argparse.Namespace) -> None:
    config = read_json(args.config)
    result = {
        "snapshot_date": config.get("snapshot_date"),
        "dry_run": True,
        "downloads": config.get("downloads", []),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


def command_execute(args: argparse.Namespace) -> None:
    config = read_json(args.config)
    result = {"dry_run": False, "items": []}
    for item in config.get("downloads", []):
        suite = item["suite"]
        target_dir = Path(item["target_dir"])
        for source_url in item.get("sources", []):
            file_name = source_url.rstrip("/").split("/")[-1]
            output = target_dir / file_name
            if args.execute:
                fetch(source_url, output)
                status = "downloaded"
            else:
                status = "dry_run"
            result["items"].append({"suite": suite, "url": source_url, "output": str(output), "status": status})
    print(json.dumps(result, ensure_ascii=False, indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Prepared benchmark source downloader")
    parser.add_argument("--config", type=Path, default=ROOT / "configs" / "downloads.json")
    sub = parser.add_subparsers(dest="command", required=True)

    plan = sub.add_parser("plan", help="Print download plan only")
    plan.set_defaults(func=command_plan)

    execute = sub.add_parser("execute", help="Fetch files only when explicitly requested")
    execute.add_argument("--execute", action="store_true")
    execute.set_defaults(func=command_execute)

    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
