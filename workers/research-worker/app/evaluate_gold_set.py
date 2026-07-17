from __future__ import annotations

import argparse
import json
from pathlib import Path

from app.evaluation import aggregate_rollouts, evaluate_research_result


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate Research Worker result payloads against a gold set.")
    parser.add_argument("--gold", required=True, type=Path)
    parser.add_argument("--result", action="append", required=True, type=Path)
    args = parser.parse_args()

    gold_payload = json.loads(args.gold.read_text(encoding="utf-8"))
    cases = {str(item["case_key"]): item for item in gold_payload.get("cases", [])}
    evaluations: list[dict[str, object]] = []
    costs: list[dict[str, object]] = []
    for result_path in args.result:
        payload = json.loads(result_path.read_text(encoding="utf-8"))
        result_payload = payload.get("result_payload", payload)
        case_key = str(payload.get("case_key") or result_payload.get("evaluation_case_key") or "")
        if case_key not in cases:
            raise SystemExit(f"result {result_path} has unknown case_key={case_key!r}")
        evaluations.append(evaluate_research_result(cases[case_key], result_payload))
        costs.append(result_payload.get("llm_cost_ledger", {}))

    output = {
        "gold_version": gold_payload.get("version", "unknown"),
        "evaluations": evaluations,
        "aggregate": aggregate_rollouts(evaluations, costs),
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0 if all(item["passed"] for item in evaluations) else 1


if __name__ == "__main__":
    raise SystemExit(main())
