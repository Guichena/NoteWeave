"""临时容器内：同一批检索词规划输入，镜像里的版本与新版本各规划一次，输出两版检索词。

用法（用 Worker 镜像起的临时容器，不要在评测用的 Worker 容器里跑）：
    python replay_queries.py <dump_query_contexts.py 的输出> <新版 cell_query_planner.py> <输出文件> [并发数]
只写检索词，不打印密钥。
"""
import importlib.util
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import app.cell_query_planner as old
from app.deep_cell_executor import (DEFAULT_MAX_QUERIES, MAX_QUERIES_PER_TASK, _EFFORT_QUERY_COUNTS,
                                    _build_execution_llm, _column_labels, _string_tuple)


def load_new(path):
    spec = importlib.util.spec_from_file_location("app.cell_query_planner_new", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def context_for(module, item):
    """与 deep_cell_executor._cell_query_context 相同的取值，列取自任务的列定义。"""
    policy, intent = item["policy"], item["intent"]
    brief = policy.get("research_brief") if isinstance(policy.get("research_brief"), dict) else {}
    question = str(brief.get("current_question") or "").strip() or str(policy.get("query") or "").strip()
    labels = _column_labels(policy)
    columns = tuple(module.CellColumn(key=key, label=value.get("label") or key, description=value.get("description", ""),
                                      requirements=tuple(value.get("requirements", ())),
                                      search_intents=tuple(value.get("search_intents", ())))
                    for key, value in labels.items())
    tried = policy.get("tried_queries")
    return module.CellQueryContext(
        question=question[:module.MAX_QUESTION_CHARS], entity_label=str(policy.get("entity_label") or "").strip(),
        columns=columns, research_goal=str(intent.get("research_goal") or "").strip(),
        time_range=str(intent.get("time_range") or "").strip(),
        constraints=tuple(str(c).strip() for c in intent.get("constraints", []) if str(c).strip())
        if isinstance(intent.get("constraints"), list) else (),
        tried_queries=tuple(str(q) for q in tried if isinstance(q, str)) if isinstance(tried, list) else (),
        role=item["role"],
        max_queries=min(MAX_QUERIES_PER_TASK, _EFFORT_QUERY_COUNTS.get(
            str(policy.get("effort_tier") or "").upper(), DEFAULT_MAX_QUERIES) + max(0, len(columns) - 1)),
        objective=str(policy.get("objective") or "").strip(), boundaries=_string_tuple(policy.get("boundaries")),
        authoritative_domains=_string_tuple(policy.get("authoritative_domains")))


def main(source, new_path, out_path, workers):
    new = load_new(new_path)
    items = json.load(open(source, encoding="utf-8"))

    def run(item):
        result = {key: item[key] for key in ("run", "case", "task_id", "role")}
        result["entity"] = item["policy"].get("entity_label")
        for side, module in (("old", old), ("new", new)):
            # 每次规划用新的客户端，调用次数上限与线上一致（生成一次，不合规时修复一次）
            llm = _build_execution_llm(lambda: None, SimpleNamespace(temperature=0.2, max_llm_calls=2))
            plan = module.plan_cell_queries(context_for(module, item), llm)
            result[side] = {"method": plan.method, "queries": [spec.query for spec in plan.specs]}
        return result

    with ThreadPoolExecutor(workers) as pool:
        results = list(pool.map(run, items))
    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    for side in ("old", "new"):
        queries = [query for result in results for query in result[side]["queries"]]
        words = [len(query.replace("site:", " ").split()) for query in queries]
        print(side, "tasks", len(results), "queries", len(queries), "mean words", round(sum(words) / max(1, len(words)), 1),
              "over 10 words", sum(count > 10 for count in words),
              "rule fallback", sum(result[side]["method"] != "LLM" for result in results))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 4)
