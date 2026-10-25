"""离线重放规划：读取规划任务当时的输入快照，用同一模型重跑两次规划并打印校验问题。

用法（在 worker 容器内）：python replay_planning.py < contexts.jsonl
每行是 {"case": ..., "context": <execution_context_json>}。只打印校验问题和表格结构，不打印密钥。
"""
import json
import sys
from types import SimpleNamespace

from app.deep_cell_executor import _build_execution_llm
from app.planning_executor import _call_model, validate_plan


def main():
    for line in sys.stdin:
        item = json.loads(line)
        context = item["context"]
        snapshot = SimpleNamespace(query_policy=context.get("query_policy", {}),
                                   source_policy=context.get("source_policy", {}))
        planning_input = snapshot.query_policy["planning_input"]
        profile = SimpleNamespace(temperature=0.2, max_llm_calls=6)
        llm = _build_execution_llm(lambda: None, profile)
        print("=====", item["case"], "| question:", str(planning_input.get("question"))[:160])
        draft = SimpleNamespace(problems=[], repair_context=None, plan=None)
        for attempt in range(2):
            raw = _call_model(llm, snapshot, planning_input, [], draft.problems if attempt else [],
                              rejected_plan=draft.repair_context if attempt else None)
            if raw is None:
                print("  attempt", attempt + 1, "CALL_FAILED")
                continue
            draft = validate_plan(raw, planning_input, final_attempt=attempt == 1)
            entities = [entity.get("label") for entity in raw.get("entities", []) if isinstance(entity, dict)] \
                if isinstance(raw, dict) else []
            dimensions = [dimension.get("label") for dimension in raw.get("dimensions", []) if isinstance(dimension, dict)] \
                if isinstance(raw, dict) else []
            print("  attempt", attempt + 1, "tier", raw.get("effort_tier") if isinstance(raw, dict) else None,
                  "entities", entities, "dimensions", dimensions)
            print("    problems:", draft.problems)
            if draft.plan is not None:
                break


if __name__ == "__main__":
    main()
