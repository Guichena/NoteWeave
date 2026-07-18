"""Run the required multi-rollout MA5 A/B suite and persist one comparison summary."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path

from app.benchmark import BenchmarkCase, BenchmarkProfile, BenchmarkSuiteRunner
from app.benchmark_runtime import ResearchTaskBenchmarkBoundary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run and compare a Research Agent MA5 benchmark suite.")
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--summary", required=True, type=Path)
    parser.add_argument("--rollouts-per-mode", type=int, default=4)
    parser.add_argument("--modes", default="SEQUENTIAL,PARALLEL")
    args = parser.parse_args(argv)

    payload = json.loads(args.spec.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or not isinstance(payload.get("case"), dict) \
            or not isinstance(payload.get("profile"), dict):
        raise ValueError("benchmark suite spec requires case and profile objects")
    case = BenchmarkCase(**payload["case"])
    profile = BenchmarkProfile(**payload["profile"])
    modes = tuple(item.strip().upper() for item in args.modes.split(",") if item.strip())

    records, comparison = BenchmarkSuiteRunner(
        ResearchTaskBenchmarkBoundary(), args.archive
    ).run(
        profile,
        case,
        modes=modes,
        rollouts_per_mode=args.rollouts_per_mode,
    )
    summary = {
        "schema_version": "research-agent-benchmark-suite.v1",
        "case_key": case.case_key,
        "provider_kind": profile.provider_kind,
        "provider": profile.provider,
        "model": profile.model,
        "rollout_count": len(records),
        "comparison_digest": records[0].comparison_digest,
        "comparison": asdict(comparison),
        "records": [asdict(record) for record in records],
    }
    encoded = (json.dumps(summary, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    if args.summary.exists():
        if args.summary.read_bytes() != encoded:
            raise FileExistsError(f"immutable benchmark suite summary conflict: {args.summary}")
    else:
        with args.summary.open("xb") as handle:
            handle.write(encoded)
    print(json.dumps({
        "summary": str(args.summary),
        "rollout_count": len(records),
        "comparison": asdict(comparison),
    }, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
