"""离线检验规划的主题对象规则：同一题、同一份探测结果，旧版与新版规划各跑几次，统计最终表格把问题没点名的研究列成多个研究对象的次数。

用法（Worker 镜像起的临时容器内）：python replay_topic_entity.py <dump_planning_contexts.py 的输出> <新版 planning_executor.py> <输出> [每版次数]
规划循环与 PlanningExecutor 一致：第一次规划，合格时再复核一次，不合格时修复。只打印研究对象、校验问题与统计，不打印密钥。
"""
import collections
import importlib.util
import json
import sys
from types import SimpleNamespace

from app import planning_executor as old
from app.deep_cell_executor import _build_execution_llm

# 与 RoleProfileCatalog 中 PLANNING 一致：温度 0.2，最多 2 次模型调用
PROFILE = SimpleNamespace(temperature=0.2, max_llm_calls=2)
BUDGET = 3


def load(path):
    spec = importlib.util.spec_from_file_location("planning_executor_new", path)
    module = importlib.util.module_from_spec(spec)
    # 模块里的 dataclass 解析注解时要从 sys.modules 找到模块本身
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def snapshot_of(context):
    return SimpleNamespace(
        task_id="replay-planning", workspace_id="replay", research_run_id="replay", role="PLANNING",
        entity_id="planning", plan_revision=1, target_cells=[], budget={"llm_calls": BUDGET},
        query_policy=context.get("query_policy") or {}, source_policy=context.get("source_policy") or {},
        research_intent=context.get("research_intent"), control_pack=context.get("control_pack"))


def probe(snapshot, planning_input):
    executor = old.PlanningExecutor(SimpleNamespace(require_permit=lambda *_args: None), "replay-worker")
    try:
        hits, _ = executor._probe(snapshot, planning_input, SimpleNamespace(toolbox=SimpleNamespace(require=lambda _name: None)))
        return hits
    except Exception:  # noqa: BLE001
        return []


def plan_once(module, snapshot, planning_input, probe_hits, check):
    llm = _build_execution_llm(lambda: None, PROFILE)
    draft, accepted, reasons, attempts = module.PlanDraft(None), None, [], []
    for attempt in range(3):
        if attempt == 2 and (accepted is not None or module._llm_call_count(llm) >= BUDGET):
            break
        raw = module._call_model(llm, snapshot, planning_input, probe_hits, draft.problems if attempt else [],
                                 candidate_plan=accepted,
                                 rejected_plan=draft.repair_context if accepted is None else None)
        if raw is None:
            if accepted is not None:
                reasons.append("PLANNING_REVIEW_UNAVAILABLE")
                break
            reasons.append("PLANNING_LLM_CALL_FAILED")
            draft = module.PlanDraft(None, ["Previous call returned no plan; return the complete plan object."])
            continue
        options = {"topic_entity_check": attempt == 0 or accepted is not None} if check else {}
        draft = module.validate_plan(raw, planning_input, final_attempt=attempt >= 1, **options)
        attempts.append({"attempt": attempt + 1, "review": accepted is not None,
                         "entities": [str(item.get("label")) for item in raw.get("entities", []) if isinstance(item, dict)]
                         if isinstance(raw, dict) else [], "problems": [problem[:160] for problem in draft.problems[:3]]})
        if draft.plan is not None:
            accepted = draft.plan
        elif accepted is not None:
            reasons.append("PLANNING_REVIEW_INVALID")
    return accepted, reasons, attempts, module._llm_call_count(llm)


def main(input_path, new_path, out_path, repeats):
    new = load(new_path)
    results, totals = [], collections.Counter()
    for line in open(input_path, encoding="utf-8"):
        item = json.loads(line)
        snapshot = snapshot_of(item["context"])
        planning_input = snapshot.query_policy["planning_input"]
        question = str(planning_input.get("question") or "")
        for index in range(repeats):
            hits = probe(snapshot, planning_input)
            for arm, module, check in (("old", old, False), ("new", new, True)):
                plan, reasons, attempts, calls = plan_once(module, snapshot, planning_input, hits, check)
                entities = plan["entities"] if plan else []
                study = bool(new._study_entity_problems(question, entities))
                totals[(arm, "runs")] += 1
                totals[(arm, "study_entities")] += study
                totals[(arm, "fallback")] += plan is None
                totals[(arm, "llm_calls")] += calls
                labels = [str(entity.get("label")) for entity in entities]
                results.append({"case": item["case"], "repeat": index, "arm": arm, "probe_hits": len(hits),
                                "entities": labels, "study_entities": study, "reasons": reasons,
                                "attempts": attempts, "llm_calls": calls})
                print(f"{item['case'][:28]:<28} {index} {arm} {'STUDY' if study else 'TOPIC' if plan else 'FALLBACK'} "
                      f"calls={calls} {labels} {reasons}", flush=True)
    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print("TOTALS", json.dumps({f"{arm}/{key}": value for (arm, key), value in sorted(totals.items())}))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 3)
