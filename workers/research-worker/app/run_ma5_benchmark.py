"""Command-line entry point for one immutable MA5 benchmark rollout."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path

from app.benchmark import BenchmarkArchive, BenchmarkCase, BenchmarkProfile, BenchmarkRunner
from app.benchmark_runtime import ResearchTaskBenchmarkBoundary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run and archive one Research Agent MA5 benchmark rollout.")
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--archive", required=True, type=Path)
    args = parser.parse_args(argv)

    payload = json.loads(args.spec.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or not isinstance(payload.get("case"), dict) or not isinstance(payload.get("profile"), dict):
        raise ValueError("benchmark spec requires case and profile objects")
    case = BenchmarkCase(**payload["case"])
    profile = BenchmarkProfile(**payload["profile"])
    record = BenchmarkRunner(ResearchTaskBenchmarkBoundary()).run(profile, case)
    artifact = BenchmarkArchive(args.archive).write(record)
    print(json.dumps({
        "artifact": str(artifact),
        "record": asdict(record),
    }, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
