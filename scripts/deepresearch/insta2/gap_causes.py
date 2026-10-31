"""按轮次统计缺口单元格的成因：没有候选、只有弱支持线索、证据资格被拒、覆盖不完整等。

用法：python3 gap_causes.py <标签>
"""
import collections
import glob
import json
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/q.sh", sql], capture_output=True, text=True).stdout
    lines = out.splitlines()
    return [line.split("\t") for line in lines[1:]]


def main(label):
    totals = collections.Counter()
    for path in sorted(glob.glob(f"{ROOT}/runs/{label}/stream*/*.json")):
        record = json.load(open(path, encoding="utf-8"))
        run_id = record["run_id"]
        gaps = {row[0] for row in query(
            f"select cell_key from research_cell where research_run_id='{run_id}' and cell_status <> 'VERIFIED'")}
        merges = collections.defaultdict(list)
        for cell_key, reason in query(
                f"select cell_key, reason_code from research_cell_merge where research_run_id='{run_id}'"):
            merges[cell_key].append(reason)
        candidates = collections.Counter(row[0] for row in query(
            f"select cell_key from research_agent_candidate where research_run_id='{run_id}'"))
        causes = collections.Counter()
        for cell in gaps:
            reasons = merges.get(cell, [])
            if not reasons and not candidates.get(cell):
                cause = "NO_CANDIDATE"
            elif not reasons:
                cause = "CANDIDATE_NOT_MERGED"
            else:
                cause = "+".join(sorted(set(reasons)))
            causes[cause] += 1
        totals.update(causes)
        print(record["case_key"], len(gaps), dict(causes))
    print("TOTAL", dict(totals))


if __name__ == "__main__":
    main(sys.argv[1])
