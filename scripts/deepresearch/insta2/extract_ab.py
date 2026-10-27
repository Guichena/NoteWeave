"""在 Worker 镜像的临时容器里运行：同一列、同一批存档窗口，用新旧两版抽取提示各抽几次，看目标要点的原文是否被抽成证据卡。

用法：python extract_ab.py <新版 extractor.py> <每版次数> <excerpt_input.py 的输出>...
输入里的 keywords 任一出现在某张卡的引用原文里，算这一次抽到了目标要点。只输出统计和卡片摘要，不输出密钥。
"""
import importlib.util
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

from app import extractor as old_extractor
from app.deep_cell_executor import _build_execution_llm
from app.models import ResearchColumn, ResearchReadWindow, ResearchTaskInput
from app.planner import build_research_plan


def load_new(path):
    spec = importlib.util.spec_from_file_location("app.extractor_new", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def build(data):
    column = ResearchColumn(**data["column"])
    task_input = ResearchTaskInput.model_validate({
        "task_id": "replay", "workspace_id": "ws", "target_id": "run", "source_scope": [],
        "control_pack": {"pack_type": "research", "target_key": "DEFAULT", "task_neighborhood": "RESEARCH_DEFAULT"},
        "input_payload": {"question": data["question"], "profile_key": "DEFAULT"}})
    plan = build_research_plan(task_input)
    plan = plan.model_copy(update={
        "research_schema": plan.research_schema.model_copy(update={"columns": [column], "required_column_count": 1}),
        "stop_contract": {**plan.stop_contract, "deep_cell_entity_label": data["entity"],
                          "evidence_horizon_window_budget": 8}})
    windows, seen = [], set()
    for item in data["windows"]:
        if item["url"] in seen:
            continue
        seen.add(item["url"])
        windows.append(ResearchReadWindow(
            window_id=item["window_id"], source_id=item["window_id"], source_title=item["title"], query="q",
            read_focus="f", window_text=item["text"], url=item["url"], hit_id=item["window_id"],
            retention_reason="replay", token_estimate=len(item["text"]) // 4))
    return plan, windows


def run_once(module, plan, windows, keywords):
    llm = _build_execution_llm(lambda: None, SimpleNamespace(temperature=0.2, max_llm_calls=4))
    result = module._extract_evidence_cards_detailed_with_llm(plan, windows, llm)
    cards = list(result.accepted_cards)
    hit = any(any(keyword in card.quote_text for keyword in keywords) for card in cards)
    return len(cards), hit, [card.claim_text[:70] for card in cards]


def main(new_path, repeats, inputs):
    new_extractor = load_new(new_path)
    totals = {"old": [0, 0, 0], "new": [0, 0, 0]}
    for path in inputs:
        data = json.load(open(path, encoding="utf-8"))
        plan, windows = build(data)
        jobs = [(arm, module) for arm, module in (("old", old_extractor), ("new", new_extractor)) for _ in range(repeats)]
        with ThreadPoolExecutor(4) as pool:
            outcomes = list(pool.map(lambda job: (job[0], *run_once(job[1], plan, windows, data["keywords"])), jobs))
        print(f"## {path.rsplit('/', 1)[-1]} windows {len(windows)}", flush=True)
        for arm, count, hit, claims in outcomes:
            totals[arm][0] += 1
            totals[arm][1] += hit
            totals[arm][2] += count
            print(f"   {arm} cards {count} hit {int(hit)} | " + " / ".join(claims), flush=True)
    for arm, (runs, hits, cards) in totals.items():
        print(f"TOTAL {arm} runs {runs} hits {hits} cards {cards}")


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]), sys.argv[3:])
