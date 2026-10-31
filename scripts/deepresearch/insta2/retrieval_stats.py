"""按轮次统计 DEEP_CELL 任务的检索规模：搜索命中、抓取文档、阅读窗口、证据卡与拒收原因。

用法：python3 retrieval_stats.py <标签...>
"""
import collections
import glob
import json
import statistics
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def main(labels, verbose):
    for label in labels:
        stats = collections.defaultdict(list)
        rejections = collections.Counter()
        per_case = []
        for path in sorted(glob.glob(f"{ROOT}/runs/{label}/merged/*.json")):
            record = json.load(open(path, encoding="utf-8"))
            rows = query("select t.role, c.envelope_json from research_agent_completion c join research_agent_task t "
                         f"on t.id = c.research_agent_task_id where t.research_run_id='{record['run_id']}' "
                         "and t.role in ('DEEP_CELL', 'COUNTERFACTUAL')")
            case_hits = []
            for role, envelope in rows:
                try:
                    data = json.loads(envelope)
                except ValueError:
                    continue
                telemetry = data.get("telemetry") or {}
                diagnostics = data.get("extraction_diagnostics") or {}
                for key in ("search_hits", "documents", "windows"):
                    stats[f"{role}.{key}"].append(int(telemetry.get(key, 0)))
                stats[f"{role}.evidence"].append(len(data.get("evidence") or []))
                rejections.update(diagnostics.get("rejection_counts") or {})
                case_hits.append((role[:2], telemetry.get("search_hits"), telemetry.get("documents"),
                                  telemetry.get("windows"), len(data.get("evidence") or [])))
            per_case.append((record["case_key"], case_hits))
        print(f"== {label}")
        for key in sorted(stats):
            values = stats[key]
            print(f"   {key}: n={len(values)} mean={statistics.mean(values):.1f} median={statistics.median(values)} "
                  f"zero={sum(1 for v in values if v == 0)}")
        print("   rejections:", dict(rejections.most_common(8)))
        if verbose:
            for case, hits in per_case:
                print("   ", case, hits)


if __name__ == "__main__":
    main([arg for arg in sys.argv[1:] if arg != "-v"], "-v" in sys.argv)
