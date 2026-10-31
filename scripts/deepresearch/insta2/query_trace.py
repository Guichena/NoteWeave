"""对照题目的每个取证任务：实体、列、列要求、实际检索词与抓到的网页标题。

用法：python3 exp/query_trace.py <标签> <题目>
"""
import json
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def main(label, case):
    record = json.load(open(f"{ROOT}/runs/{label}/merged/{case}.json", encoding="utf-8"))
    run_id = record["run_id"]
    rows = query("select t.role, t.attempt_count, t.execution_context_json, c.envelope_json from research_agent_task t "
                 "left join research_agent_completion c on t.id = c.research_agent_task_id "
                 f"where t.research_run_id='{run_id}' and t.role in ('DEEP_CELL','COUNTERFACTUAL') order by t.created_at")
    for role, attempts, context_json, envelope_json in rows:
        context = json.loads(context_json)
        policy = context.get("query_policy") or {}
        specs = policy.get("column_specs") or []
        print(f"== {role} {policy.get('entity_label')} attempts={attempts}")
        for spec in specs:
            print("   col:", spec.get("label"), "|", str(spec.get("description") or "")[:240].replace("\n", " "))
        if envelope_json in ("", "NULL", None):
            print("   (no completion)")
            continue
        envelope = json.loads(envelope_json)
        report = (envelope.get("extraction_diagnostics") or {}).get("execution_report") or {}
        for item in report.get("queries") or []:
            print("   q:", item if isinstance(item, str) else json.dumps(item, ensure_ascii=False)[:200])
        titles = list(dict.fromkeys(e.get("source_title", "")[:70] for e in envelope.get("evidence") or []))
        print("   cards:", len(envelope.get("evidence") or []), "docs:", (envelope.get("telemetry") or {}).get("documents"),
              "| evidence from:", "; ".join(titles[:6]))
        for candidate in envelope.get("candidates") or []:
            print("   value:", candidate.get("candidate_value", "")[:200].replace("\n", " "))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
