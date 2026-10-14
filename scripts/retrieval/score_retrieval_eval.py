"""为 retrieval-eval-v1 的运行记录计算 Recall@10、MRR、nDCG@10、延迟和证据充分性判定准确率。

相关性按要点（fact）判定：一条证据的标题加摘录包含某个要点任一备选词组的全部词项，即命中该要点。
这种口径不依赖片段 ID，切片方式改变后评测集仍然可用。
"""

from __future__ import annotations

import argparse
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
EVAL_DIR = ROOT / "experiments/retrieval"
DATASET = EVAL_DIR / "dataset.json"
RUNS_DIR = EVAL_DIR / "runs"
REPORTS_DIR = EVAL_DIR / "reports"
TOP_K = 10
EXPECTED_SUFFICIENCY = {"ANSWERABLE": "SUFFICIENT", "PARTIAL": "PARTIAL", "UNANSWERABLE": "INSUFFICIENT"}


def normalize(text: str) -> str:
    return "".join(text.split()).replace("`", "").lower()


def fact_matches(fact: dict[str, Any], text: str) -> bool:
    return any(all(normalize(term) in text for term in alternative) for alternative in fact["any_of"])


def final_evidence(record: dict[str, Any]) -> list[dict[str, Any]]:
    """最终进入回答上下文的全部证据（条数受预算限制，可能超过 10 条）。"""
    evidence = (record.get("evidence") or {}).get("evidence") or []
    return sorted(evidence, key=lambda item: item.get("rank", 0))


def ranked_evidence(record: dict[str, Any]) -> list[dict[str, Any]]:
    return final_evidence(record)[:TOP_K]


def ranked_candidates(record: dict[str, Any]) -> list[dict[str, Any]] | None:
    """证据挑选前的候选排序；旧记录没有这一项时返回 None。"""
    candidates = record.get("ranked_candidates")
    if not candidates:
        return None
    return sorted(candidates, key=lambda item: item.get("rank", 0))[:TOP_K]


def context_metrics(facts: list[dict[str, Any]], evidence: list[dict[str, Any]]) -> dict[str, Any]:
    """最终证据覆盖了多少要点、条数和其中有用的比例。"""
    matched: set[int] = set()
    useful = 0
    for item in evidence:
        text = normalize(f"{item.get('title', '')} {item.get('excerpt', '')}")
        hits = {index for index, fact in enumerate(facts) if fact_matches(fact, text)}
        matched |= hits
        useful += 1 if hits else 0
    return {
        "context_recall": round(len(matched) / len(facts), 4),
        "context_count": len(evidence),
        "context_precision": round(useful / len(evidence), 4) if evidence else 0.0,
    }


def ranking_metrics(facts: list[dict[str, Any]], evidence: list[dict[str, Any]]) -> dict[str, Any]:
    """Recall 按要点计；nDCG 中每条证据只为尚未被前面证据命中的要点得分，避免重复片段抬高分数。"""
    credited: set[int] = set()
    first_relevant_rank = None
    dcg = 0.0
    for position, item in enumerate(evidence, start=1):
        text = normalize(f"{item.get('title', '')} {item.get('excerpt', '')}")
        hits = {index for index, fact in enumerate(facts) if fact_matches(fact, text)}
        if hits and first_relevant_rank is None:
            first_relevant_rank = position
        if hits - credited:
            dcg += 1.0 / math.log2(position + 1)
            credited |= hits
    ideal = sum(1.0 / math.log2(position + 1) for position in range(1, min(len(facts), TOP_K) + 1))
    return {
        "recall_at_10": round(len(credited) / len(facts), 4),
        "mrr": round(1.0 / first_relevant_rank, 4) if first_relevant_rank else 0.0,
        "ndcg_at_10": round(dcg / ideal, 4) if ideal else 0.0,
        "matched_facts": sorted(credited),
    }


def score_case(case: dict[str, Any], record: dict[str, Any] | None) -> dict[str, Any]:
    result: dict[str, Any] = {
        "case_key": case["case_key"],
        "question_type": case.get("question_type"),
        "answerability": case.get("answerability"),
    }
    if record is None:
        result["status"] = "MISSING_RUN"
        return result
    result["status"] = record.get("final_status")
    result["latency_ms"] = record.get("latency_ms")
    result["retrieval_rewrite"] = record.get("retrieval_rewrite")
    if case["facts"]:
        # 排序指标优先按挑选前的候选列表计算；旧记录没有候选排序时退回最终证据
        candidates = ranked_candidates(record)
        result["ranking_source"] = "candidates" if candidates is not None else "final_evidence"
        result.update(ranking_metrics(case["facts"], candidates if candidates is not None
                                      else ranked_evidence(record)))
        result.update(context_metrics(case["facts"], final_evidence(record)))
    expected = EXPECTED_SUFFICIENCY.get(case.get("answerability") or "")
    if expected:
        actual = record.get("evidence_sufficiency")
        result["sufficiency"] = {
            "expected": expected,
            "actual": actual,
            # 后端未返回该字段或判定未执行时不计入准确率
            "correct": None if actual in (None, "NOT_RUN") else actual == expected,
        }
    return result


def mean(values: list[float]) -> float | None:
    return round(sum(values) / len(values), 4) if values else None


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(fraction * len(ordered)) - 1)
    return ordered[index]


def summarize(results: list[dict[str, Any]]) -> dict[str, Any]:
    ranked = [result for result in results if "recall_at_10" in result]
    latencies = [result["latency_ms"] for result in results if isinstance(result.get("latency_ms"), (int, float))]
    judged = [result["sufficiency"] for result in results
              if result.get("sufficiency") and result["sufficiency"]["correct"] is not None]
    summary: dict[str, Any] = {
        "cases": len(results),
        "missing_runs": sum(1 for result in results if result["status"] == "MISSING_RUN"),
        "ranked_cases": len(ranked),
        "recall_at_10": mean([result["recall_at_10"] for result in ranked]),
        "mrr": mean([result["mrr"] for result in ranked]),
        "ndcg_at_10": mean([result["ndcg_at_10"] for result in ranked]),
        "context_recall": mean([result["context_recall"] for result in ranked]),
        "context_precision": mean([result["context_precision"] for result in ranked]),
        "context_count": mean([float(result["context_count"]) for result in ranked]),
        "latency_p50_ms": percentile(latencies, 0.5),
        "latency_p95_ms": percentile(latencies, 0.95),
    }
    if any(result.get("sufficiency") for result in results):
        summary["sufficiency_judged"] = len(judged)
        summary["sufficiency_accuracy"] = mean([1.0 if item["correct"] else 0.0 for item in judged])
    return summary


def score_dataset(dataset: dict[str, Any], runs_dir: Path) -> dict[str, Any]:
    sets: dict[str, Any] = {}
    for set_name, definition in dataset["sets"].items():
        results = []
        for case in definition["cases"]:
            path = runs_dir / set_name / f"{case['case_key']}.json"
            record = json.loads(path.read_text(encoding="utf-8")) if path.exists() else None
            results.append(score_case(case, record))
        groups: dict[str, list[dict[str, Any]]] = {}
        for result in results:
            groups.setdefault(result.get("answerability") or result.get("question_type") or "ALL", []).append(result)
        sets[set_name] = {
            "overall": summarize(results),
            "by_group": {name: summarize(group) for name, group in groups.items()},
            "results": results,
        }
    return {
        "dataset_key": dataset["dataset_key"],
        "dataset_version": dataset["dataset_version"],
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "sets": sets,
    }


def render_markdown(report: dict[str, Any]) -> str:
    def fmt(value: Any) -> str:
        if value is None:
            return "-"
        return f"{value:.3f}" if isinstance(value, float) else str(value)

    lines = [f"# {report['dataset_key']} 评测报告", "",
             f"生成时间 {report['generated_at']}；数据集版本 {report['dataset_version']}。", "",
             "Recall@10、MRR、nDCG@10 按证据挑选前的候选排序计算（旧记录退回最终证据）；"
             "上下文召回、上下文精确率和平均条数按最终进入回答的证据计算。", ""]
    header = ("| 分组 | 用例 | 缺失 | Recall@10 | MRR | nDCG@10 | 上下文召回 | 上下文精确率 | 平均条数 "
              "| P50 ms | P95 ms | 充分性准确率 |")
    for set_name, data in report["sets"].items():
        lines += [f"## {set_name}", "", header, "|" + "---|" * 12]
        for name, row in [("全部", data["overall"]), *data["by_group"].items()]:
            lines.append(
                f"| {name} | {row['cases']} | {row['missing_runs']} | {fmt(row['recall_at_10'])} | {fmt(row['mrr'])} | "
                f"{fmt(row['ndcg_at_10'])} | {fmt(row.get('context_recall'))} | "
                f"{fmt(row.get('context_precision'))} | {fmt(row.get('context_count'))} | "
                f"{fmt(row['latency_p50_ms'])} | {fmt(row['latency_p95_ms'])} | "
                f"{fmt(row.get('sufficiency_accuracy'))} |")
        lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs-dir", type=Path, default=RUNS_DIR)
    args = parser.parse_args(argv)
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    report = score_dataset(dataset, args.runs_dir)
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    if args.runs_dir.resolve() != RUNS_DIR.resolve():
        stamp += f"-{args.runs_dir.name}"
    (REPORTS_DIR / f"retrieval-{stamp}.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (REPORTS_DIR / f"retrieval-{stamp}.md").write_text(render_markdown(report), encoding="utf-8")
    print(json.dumps({name: data["overall"] for name, data in report["sets"].items()}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
